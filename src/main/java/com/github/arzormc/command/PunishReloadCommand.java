/* =============================================================================
 * 🧩 PunishReloadCommand: Reloads config.yml / layout.yml / messages.yml
 *
 * 📋 What it does
 * • Reloads ConfigManager + MessageService.
 * • If configured, cancels active sessions + prompts and closes tracked menus.
 *
 * 🔧 Examples
 * • /punishreload
 *
 * ✨ Feedback (messages.yml keys)
 * • command.reload.no-permission
 * • command.reload.done
 * • command.reload.sessions-cancelled
 * =============================================================================
 */
package com.github.arzormc.command;

import com.github.arzormc.config.ConfigManager;
import com.github.arzormc.config.MessageService;
import com.github.arzormc.gui.GuiManager;
import com.github.arzormc.punish.PermissionService;
import com.github.arzormc.punish.ReasonPromptService;
import com.github.arzormc.punish.SessionManager;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;
import java.util.UUID;

// ======================
// 🧩 Command
// ======================

public final class PunishReloadCommand implements CommandExecutor {

    private static final String MSG_NO_PERMISSION = "command.reload.no-permission";
    private static final String MSG_DONE = "command.reload.done";
    private static final String MSG_SESSIONS_CANCELLED = "command.reload.sessions-cancelled";

    private final ConfigManager config;
    private final MessageService messages;

    private final PermissionService perms;
    private final GuiManager gui;
    private final SessionManager sessions;
    private final ReasonPromptService reasonPrompts;

    public PunishReloadCommand(
            ConfigManager config,
            MessageService messages,
            PermissionService perms,
            GuiManager gui,
            SessionManager sessions,
            ReasonPromptService reasonPrompts
    ) {
        this.config = Objects.requireNonNull(config, "config");
        this.messages = Objects.requireNonNull(messages, "messages");
        this.perms = Objects.requireNonNull(perms, "perms");
        this.gui = Objects.requireNonNull(gui, "gui");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.reasonPrompts = Objects.requireNonNull(reasonPrompts, "reasonPrompts");
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command cmd, @NotNull String label, String @NotNull [] args) {
        if (sender instanceof Player player) {
            if (!perms.canReload(player)) {
                player.sendMessage(messages.component(MSG_NO_PERMISSION));
                return true;
            }
        } else if (!sender.hasPermission("litebansgui.reload")) {
            sender.sendMessage(messages.component(MSG_NO_PERMISSION));
            return true;
        }

        config.reload();
        messages.reload();

        if (config.snapshot().behavior().cancelSessionsOnReload()) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                UUID uuid = p.getUniqueId();

                if (gui.openMenuType(uuid) != null) {
                    p.closeInventory();
                    gui.clearOpenMenu(uuid);
                }

                reasonPrompts.cancelPrompt(uuid);
            }

            gui.clearAll();
            sessions.handleReload();

            sender.sendMessage(messages.component(MSG_SESSIONS_CANCELLED));
        }

        sender.sendMessage(messages.component(MSG_DONE));
        return true;
    }
}
