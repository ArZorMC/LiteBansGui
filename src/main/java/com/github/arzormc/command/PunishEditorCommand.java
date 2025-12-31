/* =============================================================================
 * 🧩 PunishEditorCommand: /punisheditor [save]
 *
 * 📋 What it does
 * • /punisheditor      -> opens editor GUI
 * • /punisheditor save -> saves current editor GUI layout to layout.yml
 *
 * 🔧 Examples
 * • /punisheditor
 * • /punisheditor save
 *
 * ✨ Feedback (messages.yml keys)
 * • command.editor.usage
 * • command.editor.no-permission
 * =============================================================================
 */
package com.github.arzormc.command;

import com.github.arzormc.config.MessageService;
import com.github.arzormc.editor.EditorManager;
import com.github.arzormc.punish.PermissionService;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;

// ======================
// 🧩 Command
// ======================

public final class PunishEditorCommand implements CommandExecutor {

    private static final String MSG_PLAYERS_ONLY = "command.players-only";
    private static final String MSG_NO_PERMISSION = "command.editor.no-permission";
    private static final String MSG_USAGE = "command.editor.usage";

    private final MessageService messages;
    private final PermissionService perms;
    private final EditorManager editor;

    public PunishEditorCommand(MessageService messages, PermissionService perms, EditorManager editor) {
        this.messages = Objects.requireNonNull(messages, "messages");
        this.perms = Objects.requireNonNull(perms, "perms");
        this.editor = Objects.requireNonNull(editor, "editor");
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command cmd, @NotNull String label, String @NotNull [] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(messages.component(MSG_PLAYERS_ONLY));
            return true;
        }

        if (!perms.canUseEditor(player)) {
            player.sendMessage(messages.component(MSG_NO_PERMISSION));
            return true;
        }

        if (args.length == 0) {
            editor.open(player);
            return true;
        }

        if (args.length == 1 && args[0].equalsIgnoreCase("save")) {
            editor.save(player);
            return true;
        }

        player.sendMessage(messages.component(MSG_USAGE));
        return true;
    }
}
