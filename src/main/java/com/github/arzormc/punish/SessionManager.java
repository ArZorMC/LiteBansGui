/* =============================================================================
 * 🧩 SessionManager: In-memory PunishSession registry (per moderator)
 *
 * 📋 What it does
 * • Stores one active PunishSession per moderator UUID.
 * • Enforces behavior rules:
 *   - allow-session-replace
 *   - cancel-sessions-on-reload
 * • Applies behavior defaults:
 *   - silent-default
 *
 * ✅ Target lock
 * • Only ONE moderator may run a punish flow for a specific target at a time.
 * • Other moderators are denied and subscribed as "waiters" for that target.
 * • When the owner cancels/exits WITHOUT punishing:
 *     - all waiters are notified that the owner cancelled.
 * • When the owner successfully completes punishment:
 *     - the target lock is released (no cancellation broadcast).
 *
 * ✅ Command integration helper
 * • startOrReuse(...) returns StartResult(acquired, session)
 *   - acquired=false => caller MUST NOT open menus (lock denied)
 *   - session may be null when acquired=false and no existing session exists
 *
 * ✨ Feedback (messages.yml keys)
 * • session.target.locked
 * • session.target.cancelled-by-owner
 * =============================================================================
 */
package com.github.arzormc.punish;

import com.github.arzormc.config.ConfigManager;
import com.github.arzormc.config.MessageService;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

// ======================
// 🧩 Service
// ======================

public final class SessionManager {

    private static final String KEY_TARGET_LOCKED = "session.target.locked";
    private static final String KEY_TARGET_CANCELLED_BY_OWNER = "session.target.cancelled-by-owner";

    private final ConfigManager config;
    private final MessageService messages;
    private final JavaPlugin plugin;

    private final Map<UUID, PunishSession> sessions = new ConcurrentHashMap<>();

    private final Map<UUID, TargetLock> targetLocks = new ConcurrentHashMap<>();

    private final Map<UUID, Set<UUID>> waiterTargetsByModerator = new ConcurrentHashMap<>();

    public SessionManager(ConfigManager config, MessageService messages) {
        this.config = Objects.requireNonNull(config, "config");
        this.messages = Objects.requireNonNull(messages, "messages");
        this.plugin = JavaPlugin.getProvidingPlugin(getClass());
    }

    // ======================
    // 🧩 Access
    // ======================

    public Optional<PunishSession> get(UUID moderatorUuid) {
        if (moderatorUuid == null) return Optional.empty();
        return Optional.ofNullable(sessions.get(moderatorUuid));
    }

    public Optional<PunishSession> get(Player moderator) {
        if (moderator == null) return Optional.empty();
        return get(moderator.getUniqueId());
    }

    public boolean has(UUID moderatorUuid) {
        if (moderatorUuid == null) return false;
        return sessions.containsKey(moderatorUuid);
    }

    // ======================
    // 🧩 Create / Replace (with target lock)
    // ======================

    public StartResult startOrReuse(Player moderator, UUID targetUuid, String targetName) {
        Objects.requireNonNull(moderator, "moderator");
        Objects.requireNonNull(targetUuid, "targetUuid");

        UUID modUuid = moderator.getUniqueId();
        String modName = moderator.getName();

        clearWaiterSubscriptions(modUuid);

        AcquireLockResult lock = tryAcquireTargetLock(targetUuid, targetName, modUuid, modName);

        if (!lock.acquired()) {
            moderator.sendMessage(messages.component(
                    KEY_TARGET_LOCKED,
                    Map.of(
                            "target", lock.targetName(),
                            "owner", lock.ownerName()
                    )
            ));

            return new StartResult(false, sessions.get(modUuid));
        }

        PunishSession existing = sessions.get(modUuid);
        if (existing == null) {
            PunishSession created = createSession(targetUuid, targetName);
            sessions.put(modUuid, created);
            return new StartResult(true, created);
        }

        boolean allowReplace = config.snapshot().behavior().allowSessionReplace();
        if (!allowReplace) {
            if (!existing.targetUuid().equals(targetUuid)) {
                releaseTargetLock(targetUuid, modUuid);
            }
            return new StartResult(true, existing);
        }

        releaseTargetLock(existing.targetUuid(), modUuid);

        PunishSession created = createSession(targetUuid, targetName);
        sessions.put(modUuid, created);
        return new StartResult(true, created);
    }

    private PunishSession createSession(UUID targetUuid, String targetName) {
        PunishSession session = PunishSession.start(targetUuid, targetName);

        boolean silentDefault = config.snapshot().behavior().silentDefault();
        session.applySilentDefaultIfUnset(silentDefault);

        return session;
    }

    // ======================
    // 🧩 Cancel / Complete
    // ======================

    public Optional<PunishSession> cancel(UUID moderatorUuid) {
        if (moderatorUuid == null) return Optional.empty();

        clearWaiterSubscriptions(moderatorUuid);

        PunishSession removed = sessions.remove(moderatorUuid);
        if (removed == null) return Optional.empty();

        releaseTargetLockAndNotifyCancelled(removed.targetUuid(), removed.targetName(), moderatorUuid);
        return Optional.of(removed);
    }

    public Optional<PunishSession> cancel(Player moderator) {
        if (moderator == null) return Optional.empty();
        return cancel(moderator.getUniqueId());
    }

    public void complete(UUID moderatorUuid) {
        if (moderatorUuid == null) return;

        clearWaiterSubscriptions(moderatorUuid);

        PunishSession removed = sessions.remove(moderatorUuid);
        if (removed == null) return;

        releaseTargetLock(removed.targetUuid(), moderatorUuid);
    }

    public void cancelAll() {
        for (Map.Entry<UUID, PunishSession> e : sessions.entrySet()) {
            UUID moderatorUuid = e.getKey();
            PunishSession session = e.getValue();
            if (moderatorUuid == null || session == null) continue;

            releaseTargetLockAndNotifyCancelled(session.targetUuid(), session.targetName(), moderatorUuid);
        }

        sessions.clear();
        targetLocks.clear();
        waiterTargetsByModerator.clear();
    }

    public void handleReload() {
        if (config.snapshot().behavior().cancelSessionsOnReload()) {
            cancelAll();
        }
    }

    // ======================
    // 🧩 Target Lock internals
    // ======================

    public record StartResult(boolean acquired, PunishSession session) {}

    private record AcquireLockResult(boolean acquired, String ownerName, String targetName) {}

    private static final class TargetLock {
        private final UUID ownerUuid;
        private final String ownerName;
        private final String targetName;
        private final Set<UUID> waiters = ConcurrentHashMap.newKeySet();

        private TargetLock(UUID ownerUuid, String ownerName, String targetName) {
            this.ownerUuid = ownerUuid;
            this.ownerName = ownerName == null ? "" : ownerName;
            this.targetName = targetName == null ? "" : targetName;
        }
    }

    private AcquireLockResult tryAcquireTargetLock(UUID targetUuid, String targetName, UUID moderatorUuid, String moderatorName) {
        String safeTargetName = targetName == null ? "" : targetName;
        String safeModName = moderatorName == null ? "" : moderatorName;

        TargetLock lock = targetLocks.compute(targetUuid, (k, existing) -> {
            if (existing == null) {
                return new TargetLock(moderatorUuid, safeModName, safeTargetName);
            }

            if (existing.ownerUuid.equals(moderatorUuid)) {
                return existing;
            }

            existing.waiters.add(moderatorUuid);
            waiterTargetsByModerator
                    .computeIfAbsent(moderatorUuid, u -> ConcurrentHashMap.newKeySet())
                    .add(targetUuid);

            return existing;
        });

        boolean acquired = lock.ownerUuid.equals(moderatorUuid);
        return new AcquireLockResult(acquired, lock.ownerName, lock.targetName);
    }

    private void releaseTargetLock(UUID targetUuid, UUID moderatorUuid) {
        if (targetUuid == null || moderatorUuid == null) return;

        TargetLock lock = targetLocks.get(targetUuid);
        if (lock == null) return;
        if (!lock.ownerUuid.equals(moderatorUuid)) return;

        targetLocks.remove(targetUuid);
    }

    private void releaseTargetLockAndNotifyCancelled(UUID targetUuid, String targetName, UUID moderatorUuid) {
        if (targetUuid == null || moderatorUuid == null) return;

        TargetLock lock = targetLocks.get(targetUuid);
        if (lock == null) return;
        if (!lock.ownerUuid.equals(moderatorUuid)) return;

        targetLocks.remove(targetUuid);

        if (lock.waiters.isEmpty()) return;

        Set<UUID> waitersSnapshot = new HashSet<>(lock.waiters);

        String ownerName = lock.ownerName;
        String safeTargetName = (targetName == null || targetName.isBlank()) ? lock.targetName : targetName;

        Bukkit.getScheduler().runTask(plugin, () -> {
            for (UUID waiter : waitersSnapshot) {
                Player p = Bukkit.getPlayer(waiter);
                if (p == null || !p.isOnline()) continue;

                p.sendMessage(messages.component(
                        KEY_TARGET_CANCELLED_BY_OWNER,
                        Map.of(
                                "target", safeTargetName,
                                "owner", ownerName
                        )
                ));
            }
        });
    }

    private void clearWaiterSubscriptions(UUID moderatorUuid) {
        if (moderatorUuid == null) return;

        Set<UUID> targets = waiterTargetsByModerator.remove(moderatorUuid);
        if (targets == null || targets.isEmpty()) return;

        for (UUID targetUuid : targets) {
            TargetLock lock = targetLocks.get(targetUuid);
            if (lock == null) continue;
            lock.waiters.remove(moderatorUuid);
        }
    }
}
