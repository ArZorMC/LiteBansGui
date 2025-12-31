/* =============================================================================
 * 🧩 ReasonPromptService: Chat-based reason input with timeout + keywords
 *
 * 📋 What it does
 * • Starts a "type reason in chat" prompt for a moderator.
 * • Intercepts their next chat message and converts it into:
 *     - cancel (cancel words)
 *     - no-reason (none words)
 *     - normal reason text
 * • Enforces a timeout (config.yml reason-input.timeout-seconds).
 *     - timeout <= 0 means INFINITE (no timeout)
 * • Cancels BOTH Paper AsyncChatEvent and legacy AsyncPlayerChatEvent for
 *   compatibility with chat plugins that still broadcast from the legacy event.
 *
 * 🔧 Examples
 * • reasonPrompts.begin(moderator, session, onComplete, onCancel)
 *
 * ✨ Feedback (messages.yml keys)
 * • reason.prompt-start
 * • reason.prompt-cancelled
 * • reason.prompt-timeout
 * =============================================================================
 */
package com.github.arzormc.punish;

import com.github.arzormc.config.ConfigManager;
import com.github.arzormc.config.MessageService;

import io.papermc.paper.event.player.AsyncChatEvent;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;

// ======================
// 🧩 Service
// ======================

public final class ReasonPromptService implements Listener {

    private static final String MSG_PROMPT_START = "reason.prompt-start";
    private static final String MSG_PROMPT_CANCELLED = "reason.prompt-cancelled";
    private static final String MSG_PROMPT_TIMEOUT = "reason.prompt-timeout";

    private final JavaPlugin plugin;
    private final ConfigManager config;
    private final MessageService messages;

    private final PlainTextComponentSerializer plain = PlainTextComponentSerializer.plainText();

    private final ConcurrentHashMap<UUID, Prompt> prompts = new ConcurrentHashMap<>();

    public ReasonPromptService(JavaPlugin plugin, ConfigManager config, MessageService messages) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.config = Objects.requireNonNull(config, "config");
        this.messages = Objects.requireNonNull(messages, "messages");
    }

    // ======================
    // 🧩 Public API
    // ======================

    public void begin(
            Player moderator,
            PunishSession session,
            BiConsumer<Player, PunishSession> onComplete,
            BiConsumer<Player, PunishSession> onCancel
    ) {
        Objects.requireNonNull(moderator, "moderator");
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(onComplete, "onComplete");
        Objects.requireNonNull(onCancel, "onCancel");

        UUID modUuid = moderator.getUniqueId();

        var reasonCfg = config.snapshot().reasonInput();
        int timeoutSeconds = reasonCfg.timeoutSeconds();

        final boolean infinite = timeoutSeconds <= 0;
        final String secondsText = infinite ? "∞" : Integer.toString(timeoutSeconds);

        Prompt prompt = new Prompt(session, onComplete, onCancel);
        prompts.put(modUuid, prompt);

        moderator.sendMessage(messages.component(
                MSG_PROMPT_START,
                Map.of("seconds", secondsText)
        ));

        if (!infinite) {
            long ticks = timeoutSeconds * 20L;
            Bukkit.getScheduler().runTaskLater(plugin, () -> handleTimeout(modUuid, prompt), ticks);
        }
    }

    public void cancelPrompt(UUID moderatorUuid) {
        if (moderatorUuid == null) return;
        prompts.remove(moderatorUuid);
    }

    public void cancelAll() {
        prompts.clear();
    }

    // ======================
    // 🧩 Listener (Paper)
    // ======================

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChatCapture(AsyncChatEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        Prompt prompt = prompts.get(uuid);
        if (prompt == null) return;

        String msg = plain.serialize(event.message()).trim();

        suppressPaper(event);

        if (!prompt.consuming.compareAndSet(false, true)) return;

        prompt.captured = msg;

        Bukkit.getScheduler().runTask(plugin, () -> processCaptured(player, uuid, prompt));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChatEnforce(AsyncChatEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        if (!prompts.containsKey(uuid)) return;

        suppressPaper(event);
    }

    // ======================
    // 🧩 Listener (Legacy Bukkit Chat)
    // ======================

    @EventHandler(priority = EventPriority.LOWEST)
    @SuppressWarnings("deprecation")
    public void onLegacyChatCapture(org.bukkit.event.player.AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        Prompt prompt = prompts.get(uuid);
        if (prompt == null) return;

        String msg = event.getMessage().trim();

        suppressLegacy(event);

        if (!prompt.consuming.compareAndSet(false, true)) return;

        prompt.captured = msg;

        Bukkit.getScheduler().runTask(plugin, () -> processCaptured(player, uuid, prompt));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    @SuppressWarnings("deprecation")
    public void onLegacyChatEnforce(org.bukkit.event.player.AsyncPlayerChatEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        if (!prompts.containsKey(uuid)) return;

        suppressLegacy(event);
    }

    // ======================
    // 🧩 Processing (sync)
    // ======================

    private void processCaptured(Player player, UUID uuid, Prompt prompt) {
        Prompt active = prompts.get(uuid);
        if (active != prompt) return;

        String raw = prompt.captured;
        prompt.captured = null;

        String reason = (raw == null) ? "" : raw.trim();
        if (reason.isEmpty()) {
            prompt.consuming.set(false);
            return;
        }

        String normalized = normalize(reason);
        var reasonCfg = config.snapshot().reasonInput();

        if (matchesAny(normalized, reasonCfg.cancelWordsLower())) {
            if (!prompts.remove(uuid, prompt)) return;

            player.sendMessage(messages.component(MSG_PROMPT_CANCELLED));
            prompt.onCancel.accept(player, prompt.session);
            return;
        }

        if (reasonCfg.allowNoneWord() && matchesAny(normalized, reasonCfg.noneWordsLower())) {
            if (!prompts.remove(uuid, prompt)) return;

            prompt.session.setReason(""); // explicitly none
            prompt.onComplete.accept(player, prompt.session);
            return;
        }

        if (!prompts.remove(uuid, prompt)) return;

        prompt.session.setReason(reason);
        prompt.onComplete.accept(player, prompt.session);
    }

    // ======================
    // 🧩 Internals
    // ======================

    private void handleTimeout(UUID moderatorUuid, Prompt expected) {
        Prompt active = prompts.get(moderatorUuid);
        if (active == null) return;
        if (active != expected) return;

        prompts.remove(moderatorUuid, expected);

        Player player = Bukkit.getPlayer(moderatorUuid);
        if (player != null) {
            player.sendMessage(messages.component(MSG_PROMPT_TIMEOUT));
        }

        active.onCancel.accept(null, active.session);
    }

    private static void suppressPaper(AsyncChatEvent event) {
        event.setCancelled(true);

        try { event.viewers().clear(); } catch (Throwable ignored) {}

        try {
            event.renderer((source, sourceDisplayName, message, viewer) -> Component.empty());
        } catch (Throwable ignored) {}
    }

    @SuppressWarnings("deprecation")
    private static void suppressLegacy(org.bukkit.event.player.AsyncPlayerChatEvent event) {
        event.setCancelled(true);

        try { event.getRecipients().clear(); } catch (Throwable ignored) {}
    }

    private static boolean matchesAny(String normalized, Iterable<String> wordsLower) {
        if (normalized.isEmpty()) return false;
        if (wordsLower == null) return false;

        for (String w : wordsLower) {
            if (w == null) continue;
            if (normalized.equals(w)) return true;
        }
        return false;
    }

    private static String normalize(String raw) {
        if (raw == null) return "";
        return raw.trim().toLowerCase(Locale.ROOT);
    }

    private static final class Prompt {

        private final PunishSession session;
        private final BiConsumer<Player, PunishSession> onComplete;
        private final BiConsumer<Player, PunishSession> onCancel;

        private final AtomicBoolean consuming = new AtomicBoolean(false);

        private volatile String captured;

        private Prompt(
                PunishSession session,
                BiConsumer<Player, PunishSession> onComplete,
                BiConsumer<Player, PunishSession> onCancel
        ) {
            this.session = session;
            this.onComplete = onComplete;
            this.onCancel = onCancel;
        }
    }
}
