/* =============================================================================
 * 🧩 PermissionService: Central permission checks + denied-click feedback
 *
 * 📋 What it does
 * • Centralizes permission node construction for LiteBansGui.
 * • Provides checks for:
 *     - base /punish access
 *     - category visibility/clickability
 *     - category severity level clickability
 *     - editor/reload permissions
 *     - punishment history visibility + filters
 *     - punishment history entry actions (pardon/reinstate)
 * • Plays the configured deny-click sound (if enabled) on denied interactions.
 *
 * 🔧 Examples
 * • if (!perms.canUsePunish(player)) { ... }
 * • if (!perms.canUseCategory(player, "griefing")) { ... }
 * • if (!perms.canViewHistory(player)) { ... }
 * • if (!perms.canPardonHistory(player)) { ... }
 * • perms.playDenyClick(player);
 *
 * ✨ Feedback (messages.yml keys)
 * • (N/A — permission-only)
 * =============================================================================
 */
package com.github.arzormc.punish;

import com.github.arzormc.config.ConfigManager;

import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

import java.util.Locale;
import java.util.Objects;

// ======================
// 🧩 Service
// ======================

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

    private final ConfigManager config;

    public PermissionService(ConfigManager config) {
        this.config = Objects.requireNonNull(config, "config");
    }

    // ======================
    // 🧩 Core checks
    // ======================

    public boolean canUsePunish(Player player) {
        return player != null && player.hasPermission(NODE_BASE);
    }

    public boolean canReload(Player player) {
        return player != null && player.hasPermission(NODE_RELOAD);
    }

    public boolean canUseEditor(Player player) {
        return player != null && player.hasPermission(NODE_EDITOR);
    }

    public boolean canUseCategory(Player player, String categoryId) {
        if (player == null) return false;
        if (categoryId == null || categoryId.trim().isEmpty()) return false;
        return player.hasPermission(NODE_CATEGORY_PREFIX + normalize(categoryId));
    }

    public boolean canUseCategoryLevel(Player player, String categoryId, int levelId) {
        if (player == null) return false;
        if (categoryId == null || categoryId.trim().isEmpty()) return false;
        if (levelId <= 0) return false;

        String node = NODE_CATEGORY_PREFIX + normalize(categoryId) + NODE_LEVEL_SUFFIX + levelId;
        return player.hasPermission(node);
    }

    // ======================
    // 🧩 History checks
    // ======================

    public boolean canViewHistory(Player player) {
        return player != null && player.hasPermission(NODE_HISTORY_BASE);
    }

    public boolean isHistoryFilterDenied(Player player, String filterKey) {
        if (player == null) return true;
        if (filterKey == null || filterKey.isBlank()) return true;

        return !player.hasPermission(
                NODE_HISTORY_FILTER_PREFIX + normalize(filterKey)
        );
    }

    // ======================
    // 🧩 History entry actions
    // ======================

    public boolean canPardonHistory(Player player) {
        return player != null && (
                player.hasPermission(NODE_HISTORY_PARDON)
                        || player.hasPermission(NODE_HISTORY_ACTION_WILDCARD)
        );
    }

    public boolean canReinstateHistory(Player player) {
        return player != null && (
                player.hasPermission(NODE_HISTORY_REINSTATE)
                        || player.hasPermission(NODE_HISTORY_ACTION_WILDCARD)
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
