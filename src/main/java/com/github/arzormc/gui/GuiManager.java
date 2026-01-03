/* =============================================================================
 * 🧩 GuiManager: Opens menus and coordinates the punishment flow UI
 *
 * 📋 What it does
 * • Single entry point to open Category/Severity/Confirm/History menus.
 * • Tracks which menu type is currently open per moderator (for close handling).
 * • Keeps GUI flow wiring centralized (commands call into here).
 *
 * 🔧 Examples
 * • gui.openCategory(moderator, session)
 * • gui.openSeverity(moderator, session)
 * • gui.openConfirm(moderator, session)
 * • gui.openHistory(moderator, session)
 *
 * ✨ Feedback (messages.yml keys)
 * • (N/A — routing-only)
 * =============================================================================
 */
package com.github.arzormc.gui;

import com.github.arzormc.config.ConfigManager;
import com.github.arzormc.config.MessageService;
import com.github.arzormc.punish.PermissionService;
import com.github.arzormc.punish.PunishSession;
import com.github.arzormc.punish.ReasonPromptService;
import com.github.arzormc.punish.SessionManager;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

// ======================
// 🧩 Manager
// ======================

public final class GuiManager {

    public enum MenuType {
        CATEGORY,
        SEVERITY,
        CONFIRM,
        HISTORY,
        PROMPT
    }

    private final ConfigManager config;
    private final MessageService messages;

    private final ItemFactory items;
    private final PermissionService perms;
    private final SessionManager sessions;
    private final ReasonPromptService reasonPrompts;

    private final Map<UUID, MenuType> openMenus = new ConcurrentHashMap<>();

    public GuiManager(
            ConfigManager config,
            MessageService messages,
            ItemFactory items,
            PermissionService perms,
            SessionManager sessions,
            ReasonPromptService reasonPrompts
    ) {
        this.config = Objects.requireNonNull(config, "config");
        this.messages = Objects.requireNonNull(messages, "messages");
        this.items = Objects.requireNonNull(items, "items");
        this.perms = Objects.requireNonNull(perms, "perms");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.reasonPrompts = Objects.requireNonNull(reasonPrompts, "reasonPrompts");
    }

    // ======================
    // 🧩 Open menus
    // ======================

    public void openCategory(Player moderator, PunishSession session) {
        Objects.requireNonNull(moderator, "moderator");
        Objects.requireNonNull(session, "session");

        openMenus.put(moderator.getUniqueId(), MenuType.CATEGORY);

        new CategoryMenu(config, messages, items, perms, sessions, this)
                .open(moderator, session);
    }

    public void openSeverity(Player moderator, PunishSession session) {
        Objects.requireNonNull(moderator, "moderator");
        Objects.requireNonNull(session, "session");

        openMenus.put(moderator.getUniqueId(), MenuType.SEVERITY);

        new SeverityMenu(config, messages, items, perms, sessions, reasonPrompts, this)
                .open(moderator, session);
    }

    public void openConfirm(Player moderator, PunishSession session) {
        Objects.requireNonNull(moderator, "moderator");
        Objects.requireNonNull(session, "session");

        openMenus.put(moderator.getUniqueId(), MenuType.CONFIRM);

        new ConfirmMenu(config, messages, items, perms, sessions, reasonPrompts, this)
                .open(moderator, session);
    }

    public void openHistory(Player moderator, PunishSession session) {
        Objects.requireNonNull(moderator, "moderator");
        Objects.requireNonNull(session, "session");

        openMenus.put(moderator.getUniqueId(), MenuType.HISTORY);

        new PunishmentHistoryMenu(config, messages, items, perms, sessions, reasonPrompts, this)
                .open(moderator, session);
    }

    // ======================
    // 🧩 Prompt state helpers
    // ======================

    public void markPrompting(UUID moderatorUuid) {
        if (moderatorUuid == null) return;
        openMenus.put(moderatorUuid, MenuType.PROMPT);
    }

    public void markHistory(UUID moderatorUuid) {
        if (moderatorUuid == null) return;
        openMenus.put(moderatorUuid, MenuType.HISTORY);
    }

    public boolean isPrompting(UUID moderatorUuid) {
        if (moderatorUuid == null) return false;
        return openMenus.get(moderatorUuid) == MenuType.PROMPT;
    }

    public void clearPrompting(UUID moderatorUuid) {
        if (moderatorUuid == null) return;
        if (openMenus.get(moderatorUuid) == MenuType.PROMPT) {
            openMenus.remove(moderatorUuid);
        }
    }

    // ======================
    // 🧩 Tracking
    // ======================

    public MenuType openMenuType(UUID moderatorUuid) {
        if (moderatorUuid == null) return null;
        return openMenus.get(moderatorUuid);
    }

    public void clearOpenMenu(UUID moderatorUuid) {
        if (moderatorUuid == null) return;
        openMenus.remove(moderatorUuid);
    }

    public void clearAll() {
        openMenus.clear();
    }

    // ======================
    // 🧩 Holder contract (canonical for ALL menus)
    // ======================

    public record Holder(MenuType type, UUID moderatorUuid)
            implements InventoryListeners.MenuHolder, InventoryHolder {

        private static final Inventory DUMMY = Bukkit.createInventory(null, 9);

        public Holder {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(moderatorUuid, "moderatorUuid");
        }

        @Override
        public @NotNull Inventory getInventory() {
            return DUMMY;
        }
    }
}
