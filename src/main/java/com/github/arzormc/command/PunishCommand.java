/* =============================================================================
 * 🧩 PunishCommand: Entry point for /punish <player>
 *
 * 📋 What it does
 * • Validates target (online-only MVP).
 * • Creates (or reuses) a PunishSession for the moderator via SessionManager.
 * • If session replacement is disabled and a session exists, informs user and resumes that session.
 * • Opens Category menu.
 *
 * ✅ Target lock behavior
 * • If another moderator already owns the target lock:
 *   - shows session.target.locked (sent by SessionManager)
 *   - does NOT open any GUI
 *
 * 🔧 Examples
 * • /punish Notch
 *
 * ✨ Feedback (messages.yml keys)
 * • command.punish.usage
 * • command.punish.no-permission
 * • command.punish.player-not-found
 * • command.punish.session-exists
 * • command.punish.session-replaced
 * =============================================================================
 */
package com.github.arzormc.command;

import com.github.arzormc.config.ConfigManager;
import com.github.arzormc.config.MessageService;
import com.github.arzormc.gui.GuiManager;
import com.github.arzormc.punish.PermissionService;
import com.github.arzormc.punish.PunishSession;
import com.github.arzormc.punish.SessionManager;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
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

public final class PunishCommand implements CommandExecutor {

    private static final String MSG_USAGE = "command.punish.usage";
    private static final String MSG_PLAYERS_ONLY = "command.players-only";
    private static final String MSG_NO_PERMISSION = "command.punish.no-permission";
    private static final String MSG_SESSION_EXISTS = "command.punish.session-exists";
    private static final String MSG_SESSION_REPLACED = "command.punish.session-replaced";

    private final ConfigManager config;
    private final MessageService messages;

    private final PermissionService perms;
    private final SessionManager sessions;
    private final GuiManager gui;

    public PunishCommand(
            ConfigManager config,
            MessageService messages,
            PermissionService perms,
            SessionManager sessions,
            GuiManager gui
    ) {
        this.config = Objects.requireNonNull(config, "config");
        this.messages = Objects.requireNonNull(messages, "messages");
        this.perms = Objects.requireNonNull(perms, "perms");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.gui = Objects.requireNonNull(gui, "gui");
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command cmd, @NotNull String label, String @NotNull [] args) {
        if (!(sender instanceof Player moderator)) {
            sender.sendMessage(messages.component(MSG_PLAYERS_ONLY));
            return true;
        }

        if (!perms.canUsePunish(moderator)) {
            moderator.sendMessage(messages.component(MSG_NO_PERMISSION));
            return true;
        }

        if (args.length < 1) {
            moderator.sendMessage(messages.component(MSG_USAGE));
            return true;
        }

        String targetInput = args[0] == null ? "" : args[0].trim();
        if (targetInput.isEmpty()) {
            moderator.sendMessage(messages.component(MSG_USAGE));
            return true;
        }

        OfflinePlayer target = Bukkit.getPlayerExact(targetInput);
        if (target == null) {
            target = Bukkit.getOfflinePlayerIfCached(targetInput);
        }
        if (target == null) {
            target = Bukkit.getOfflinePlayer(targetInput);
        }

        UUID targetUuid = target.getUniqueId();
        String targetName = target.getName();
        if (targetName == null || targetName.isBlank()) {
            targetName = targetInput;
        }

        UUID modUuid = moderator.getUniqueId();

        boolean hadExisting = sessions.has(modUuid);
        boolean allowReplace = config.snapshot().behavior().allowSessionReplace();

        SessionManager.StartResult res = sessions.startOrReuse(moderator, targetUuid, targetName);

        if (!res.acquired()) {
            return true;
        }

        PunishSession session = res.session();
        if (session == null) {
            return true;
        }

        if (hadExisting && !allowReplace) {
            moderator.sendMessage(messages.component(MSG_SESSION_EXISTS));
        } else if (hadExisting) {
            moderator.sendMessage(messages.component(MSG_SESSION_REPLACED));
            session.setSilent(config.snapshot().behavior().silentDefault());
        } else {
            session.setSilent(config.snapshot().behavior().silentDefault());
        }

        gui.openCategory(moderator, session);
        return true;
    }
}
