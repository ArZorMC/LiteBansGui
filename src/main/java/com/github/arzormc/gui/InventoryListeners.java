/* =============================================================================
 * 🧩 InventoryListeners: Close/quit handling for the GUI punishment flow
 *
 * 📋 What it does
 * • Cancels the punishment flow when the moderator closes one of OUR GUIs (if configured).
 * • Does NOT cancel when switching between our menus (category -> severity -> confirm), since that
 *   naturally closes the old inventory first.
 * • Cleans up open-menu tracking for the moderator.
 * • Cancels prompts/sessions when moderators quit.
 *
 * ✨ Feedback (messages.yml keys)
 * • session.cancelled-on-close
 * =============================================================================
 */
package com.github.arzormc.gui;

import com.github.arzormc.config.ConfigManager;
import com.github.arzormc.config.ConfigModels.OnInventoryCloseAction;
import com.github.arzormc.config.MessageService;
import com.github.arzormc.punish.ReasonPromptService;
import com.github.arzormc.punish.SessionManager;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Objects;
import java.util.UUID;

// ======================
// 🧩 Listener
// ======================

public final class InventoryListeners implements Listener {

    private static final String MSG_CANCELLED_ON_CLOSE = "session.cancelled-on-close";

    private final ConfigManager config;
    private final MessageService messages;

    private final GuiManager gui;
    private final SessionManager sessions;
    private final ReasonPromptService reasonPrompts;

    private final JavaPlugin plugin;

    public InventoryListeners(
            ConfigManager config,
            MessageService messages,
            GuiManager gui,
            SessionManager sessions,
            ReasonPromptService reasonPrompts
    ) {
        this.config = Objects.requireNonNull(config, "config");
        this.messages = Objects.requireNonNull(messages, "messages");
        this.gui = Objects.requireNonNull(gui, "gui");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.reasonPrompts = Objects.requireNonNull(reasonPrompts, "reasonPrompts");

        this.plugin = JavaPlugin.getProvidingPlugin(getClass());
    }

    // ======================
    // 🧩 Inventory close
    // ======================

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }

        UUID uuid = player.getUniqueId();

        InventoryHolder holder = event.getInventory().getHolder();
        if (!(holder instanceof MenuHolder menuHolder)) {
            return;
        }

        if (!uuid.equals(menuHolder.moderatorUuid())) {
            return;
        }

        GuiManager.MenuType closingType = menuHolder.type();

        if (gui.openMenuType(uuid) == closingType) {
            gui.clearOpenMenu(uuid);
        }

        OnInventoryCloseAction action = config.snapshot().behavior().onInventoryClose();
        if (action != OnInventoryCloseAction.CANCEL) {
            return;
        }

        player.getServer().getScheduler().runTask(
                plugin,
                () -> {
                    GuiManager.MenuType state = gui.openMenuType(uuid);

                    if (state == GuiManager.MenuType.PROMPT) {
                        return;
                    }

                    if (state != null) {
                        return;
                    }

                    if (!sessions.has(uuid)) {
                        return;
                    }

                    reasonPrompts.cancelPrompt(uuid);
                    sessions.cancel(uuid);

                    String raw = messages.raw(MSG_CANCELLED_ON_CLOSE);
                    if (!raw.trim().isEmpty()) {
                        player.sendMessage(messages.component(MSG_CANCELLED_ON_CLOSE));
                    }
                }
        );
    }

    // ======================
    // 🧩 Quit cleanup
    // ======================

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        gui.clearOpenMenu(uuid);
        reasonPrompts.cancelPrompt(uuid);
        sessions.cancel(uuid);
    }

    // ======================
    // 🧩 Holder contract (must match menus)
    // ======================

    public interface MenuHolder {
        GuiManager.MenuType type();
        UUID moderatorUuid();
    }
}
