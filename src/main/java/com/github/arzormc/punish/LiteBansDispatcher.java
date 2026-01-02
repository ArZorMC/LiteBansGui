/* =============================================================================
 * 🧩 LiteBansDispatcher: Executes punishments through LiteBans commands
 *
 * 📋 What it does
 * • Converts a completed PunishSession into a LiteBans command invocation.
 * • Dispatches via the moderator when possible so LiteBans records correct staff.
 * • Falls back to console only if moderator is null/offline.
 * • Supports:
 *     - WARN, KICK, TEMPMUTE, TEMPBAN, BAN (with duration token where applicable)
 *     - silent flag (-s) when enabled
 *
 * 🔧 Examples
 * • dispatcher.dispatch(moderator, session)
 *
 * ✨ Feedback (messages.yml keys)
 * • punish.dispatch.failed
 * • punish.dispatch.missing-plugin
 * =============================================================================
 */
package com.github.arzormc.punish;

import com.github.arzormc.config.ConfigModels;
import com.github.arzormc.config.MessageService;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.Map;
import java.util.Objects;

// ======================
// 🧩 Service
// ======================

public final class LiteBansDispatcher {

    private static final String MSG_MISSING_PLUGIN = "punish.dispatch.missing-plugin";
    private static final String MSG_DISPATCH_FAILED = "punish.dispatch.failed";

    private final MessageService messages;

    public LiteBansDispatcher(MessageService messages) {
        this.messages = Objects.requireNonNull(messages, "messages");
    }

    public boolean dispatch(Player moderator, PunishSession session) {
        Objects.requireNonNull(session, "session");

        if (!session.isDispatchReady()) {
            if (moderator != null) {
                moderator.sendMessage(messages.component(MSG_DISPATCH_FAILED, Map.of("reason", "not_ready")));
            }
            return false;
        }

        Plugin litebans = Bukkit.getPluginManager().getPlugin("LiteBans");
        if (litebans == null || !litebans.isEnabled()) {
            if (moderator != null) {
                moderator.sendMessage(messages.component(MSG_MISSING_PLUGIN));
            }
            return false;
        }

        String target = session.targetName();
        if (target == null || target.trim().isEmpty()) {
            if (moderator != null) {
                moderator.sendMessage(messages.component(MSG_DISPATCH_FAILED, Map.of("reason", "missing_target")));
            }
            return false;
        }

        ConfigModels.LevelSpec level = session.level();

        String reason = session.reason();
        if (reason == null || reason.isBlank()) {
            reason = "";
        }

        String cmd = buildCommand(level, session.silent(), target, reason);

        CommandSender sender = pickSender(moderator);

        boolean ok = Bukkit.dispatchCommand(sender, cmd);

        if (!ok && moderator != null) {
            moderator.sendMessage(messages.component(MSG_DISPATCH_FAILED, Map.of(
                    "reason", sender instanceof Player ? "dispatch_false_player" : "dispatch_false_console"
            )));
        }

        return ok;
    }

    // ======================
    // 🧩 Internals
    // ======================

    private static CommandSender pickSender(Player moderator) {
        if (moderator != null && moderator.isOnline()) {
            return moderator;
        }
        return Bukkit.getConsoleSender();
    }

    private static String buildCommand(ConfigModels.LevelSpec level, boolean silent, String target, String reason) {
        ConfigModels.PunishType type = level.type();
        String duration = level.duration() == null ? "" : level.duration().trim();

        String silentFlag = silent ? " -s" : "";
        String reasonArg = (reason == null || reason.isBlank()) ? "" : " " + reason;

        return switch (type) {
            case WARN -> // /warn <player> [reason...] [-s]
                    "warn " + target + reasonArg + silentFlag;

            case KICK -> // /kick <player> [reason...] [-s]
                    "kick " + target + reasonArg + silentFlag;

            case TEMPMUTE -> {
                // Convention:
                // - TEMPMUTE with blank duration (or perm/permanent) is treated as a permanent mute.
                // - Otherwise: tempmute <time>
                if (isPerm(duration)) {
                    yield "mute " + target + reasonArg + silentFlag;
                }
                yield "tempmute " + target + " " + normalizeDuration(duration) + reasonArg + silentFlag;
            }

            case TEMPBAN -> // /tempban <player> <time> [reason...] [-s]
                    "tempban " + target + " " + normalizeDuration(duration) + reasonArg + silentFlag;

            case BAN -> {
                if (isPerm(duration)) {
                    yield "ban " + target + reasonArg + silentFlag;
                }
                yield "tempban " + target + " " + normalizeDuration(duration) + reasonArg + silentFlag;
            }
        };
    }

    private static boolean isPerm(String token) {
        if (token == null) return true;
        String t = token.trim();
        if (t.isEmpty()) return true;
        return t.equalsIgnoreCase("perm") || t.equalsIgnoreCase("permanent");
    }

    private static String normalizeDuration(String token) {
        if (token == null) return "1d";
        String t = token.trim();
        if (t.isEmpty()) return "1d";
        return t;
    }
}
