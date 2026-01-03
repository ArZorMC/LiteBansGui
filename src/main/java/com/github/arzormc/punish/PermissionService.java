package com.github.arzormc.punish;

import com.github.arzormc.config.ConfigManager;
import com.github.arzormc.config.ConfigModels.LoadoutDef;

import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.permissions.PermissionAttachmentInfo;

import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class PermissionService {

    private static final String NODE_BASE = "litebansgui.use";
    private static final String NODE_RELOAD = "litebansgui.reload";
    private static final String NODE_EDITOR = "litebansgui.editor";

    private static final String NODE_CATEGORY_PREFIX = "litebansgui.category.";
    private static final String NODE_LEVEL_SUFFIX = ".level.";

    private static final String NODE_HISTORY_BASE = "litebansgui.history";
    private static final String NODE_HISTORY_FILTER_PREFIX = "litebansgui.history.filter.";

    private static final String NODE_HISTORY_ACTION_WILDCARD = "litebansgui.history.action.*";
    private static final String NODE_HISTORY_PARDON = "litebansgui.history.pardon";
    private static final String NODE_HISTORY_REINSTATE = "litebansgui.history.reinstate";

    private static final String NODE_LOADOUT_PREFIX = "litebansgui.loadout.";

    private final ConfigManager config;

    public PermissionService(ConfigManager config) {
        this.config = Objects.requireNonNull(config, "config");
    }

    // ======================
    // 🧩 Core checks
    // ======================

    public boolean canUsePunish(Player player) {
        return has(player, NODE_BASE);
    }

    public boolean canReload(Player player) {
        return has(player, NODE_RELOAD);
    }

    public boolean canUseEditor(Player player) {
        return has(player, NODE_EDITOR);
    }

    public boolean canUseCategory(Player player, String categoryId) {
        if (player == null) return false;
        if (categoryId == null || categoryId.trim().isEmpty()) return false;
        return has(player, NODE_CATEGORY_PREFIX + normalize(categoryId));
    }

    public boolean canUseCategoryLevel(Player player, String categoryId, int levelId) {
        if (player == null) return false;
        if (categoryId == null || categoryId.trim().isEmpty()) return false;
        if (levelId <= 0) return false;

        String node = NODE_CATEGORY_PREFIX + normalize(categoryId) + NODE_LEVEL_SUFFIX + levelId;
        return has(player, node);
    }

    // ======================
    // 🧩 History checks
    // ======================

    public boolean canViewHistory(Player player) {
        return has(player, NODE_HISTORY_BASE);
    }

    public boolean isHistoryFilterDenied(Player player, String filterKey) {
        if (player == null) return true;
        if (filterKey == null || filterKey.isBlank()) return true;

        return !has(player, NODE_HISTORY_FILTER_PREFIX + normalize(filterKey));
    }

    // ======================
    // 🧩 History entry actions
    // ======================

    public boolean canPardonHistory(Player player) {
        return (
                has(player, NODE_HISTORY_PARDON)
                        || has(player, NODE_HISTORY_ACTION_WILDCARD)
        );
    }

    public boolean canReinstateHistory(Player player) {
        return (
                has(player, NODE_HISTORY_REINSTATE)
                        || has(player, NODE_HISTORY_ACTION_WILDCARD)
        );
    }

    // ======================
    // 🧩 Denied feedback
    // ======================

    public void playDenyClick(Player player) {
        if (player == null) return;

        ConfigManager.Snapshot snap = config.snapshot();
        if (snap == null) return;

        var soundCfg = snap.permissionUi().denyClickSound();
        if (!soundCfg.enabled()) return;

        Sound sound = parseSound(soundCfg.sound());
        if (sound == null) return;

        player.playSound(player.getLocation(), sound, soundCfg.volume(), soundCfg.pitch());
    }

    // ======================
    // 🧩 Permission resolution
    // ======================

    private boolean has(Player player, String node) {
        if (player == null) return false;
        if (node == null || node.isBlank()) return false;

        // Direct permission always wins
        if (player.hasPermission(node)) return true;

        ConfigManager.Snapshot snap = config.snapshot();
        if (snap == null) return false;

        Map<String, LoadoutDef> loadouts = snap.loadouts();
        if (loadouts.isEmpty()) return false;

        Set<String> grantedLoadoutIds = findGrantedLoadoutIds(player);
        if (grantedLoadoutIds.isEmpty()) return false;

        for (String loadoutId : grantedLoadoutIds) {
            Set<String> implied = resolveLoadoutPermissions(loadouts, loadoutId);
            if (implies(implied, node)) return true;
        }

        return false;
    }

    private static Set<String> findGrantedLoadoutIds(Player player) {
        Set<String> ids = new HashSet<>();
        for (PermissionAttachmentInfo info : player.getEffectivePermissions()) {
            if (info == null) continue;
            if (!info.getValue()) continue;

            // Bukkit/Paper contracts treat this as @NotNull, so no null-check needed.
            String perm = info.getPermission();

            String p = perm.trim().toLowerCase(Locale.ROOT);
            if (!p.startsWith(NODE_LOADOUT_PREFIX)) continue;

            String id = p.substring(NODE_LOADOUT_PREFIX.length()).trim();
            if (id.isEmpty()) continue;

            ids.add(id);
        }
        return ids;
    }

    private static boolean implies(Set<String> impliedPerms, String node) {
        if (impliedPerms.contains(node)) return true;

        // Wildcard support for suffix ".*"
        for (String p : impliedPerms) {
            if (p == null) continue;
            if (!p.endsWith(".*")) continue;

            String prefix = p.substring(0, p.length() - 2);
            if (node.startsWith(prefix)) return true;
        }

        return false;
    }

    private static Set<String> resolveLoadoutPermissions(Map<String, LoadoutDef> loadouts, String rootId) {
        Set<String> out = new HashSet<>();
        Set<String> visiting = new HashSet<>();
        resolveLoadoutPermissions0(loadouts, normalizeId(rootId), out, visiting);
        return out;
    }

    private static void resolveLoadoutPermissions0(
            Map<String, LoadoutDef> loadouts,
            String id,
            Set<String> out,
            Set<String> visiting
    ) {
        if (id == null || id.isEmpty()) return;
        if (!visiting.add(id)) return; // cycle detected

        LoadoutDef def = loadouts.get(id);
        if (def != null) {
            for (String inc : def.includes()) {
                resolveLoadoutPermissions0(loadouts, normalizeId(inc), out, visiting);
            }
            for (String perm : def.permissions()) {
                if (perm == null) continue;
                String p = perm.trim();
                if (!p.isEmpty()) out.add(p);
            }
        }

        visiting.remove(id);
    }

    private static String normalizeId(String id) {
        if (id == null) return null;
        String s = id.trim().toLowerCase(Locale.ROOT);
        return s.isEmpty() ? null : s;
    }

    // ======================
    // 🧩 Internals
    // ======================

    private static String normalize(String id) {
        return id.trim().toLowerCase(Locale.ROOT);
    }

    private static Sound parseSound(String raw) {
        if (raw == null) return null;

        String s = raw.trim();
        if (s.isEmpty()) return null;

        NamespacedKey key;

        if (s.indexOf(':') >= 0) {
            key = NamespacedKey.fromString(s.toLowerCase(Locale.ROOT));
            if (key == null) return null;
            return Registry.SOUNDS.get(key);
        }

        String lowered = s.toLowerCase(Locale.ROOT);
        if (lowered.indexOf('.') >= 0 || lowered.indexOf('/') >= 0) {
            key = NamespacedKey.minecraft(lowered);
            return Registry.SOUNDS.get(key);
        }

        key = NamespacedKey.minecraft(lowered.replace('_', '.'));
        return Registry.SOUNDS.get(key);
    }
}
