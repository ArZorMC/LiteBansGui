/* =============================================================================
 * 🧩 PlaceholderUtil: Canonical placeholder map builder for messages/menus
 *
 * 📋 What it does
 * • Produces consistent placeholder maps for MessageService rendering.
 * • Uses "industry standard" placeholder names commonly seen in Minecraft plugins:
 *     - player/target, staff/executor, reason, type, duration, category, severity, silent
 * • Keeps keys stable across GUI + commands + dispatching.
 *
 * 🔧 Examples
 * • Map<String,String> ph = PlaceholderUtil.forSession(session, staffUuid, staffName)
 * • messages.component("confirm.summary.title", ph)
 *
 * ✨ Feedback (messages.yml keys)
 * • (N/A — utility-only)
 * =============================================================================
 */
package com.github.arzormc.config;

import com.github.arzormc.config.ConfigModels.PunishType;
import com.github.arzormc.punish.PunishSession;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

// ======================
// 🧩 Builder
// ======================

public final class PlaceholderUtil {

    private static final String NA = "N/A";
    private static final String PERMANENT = "Permanent";

    private static final String ONLINE_TEXT = "Online";
    private static final String OFFLINE_TEXT = "Offline";

    private PlaceholderUtil() {
    }

    public static Map<String, String> forSession(PunishSession session, UUID staffUuid, String staffName) {
        Objects.requireNonNull(session, "session");

        Map<String, String> out = new LinkedHashMap<>();

        // ---- Target / player ----
        String targetName = safe(session.targetName());
        out.put("player", targetName);
        out.put("target", targetName);
        out.put("player_uuid", stringify(session.targetUuid()));
        out.put("target_uuid", stringify(session.targetUuid()));

        // ---- Staff / executor ----
        out.put("staff", safe(staffName));
        out.put("executor", safe(staffName));
        out.put("staff_uuid", stringify(staffUuid));
        out.put("executor_uuid", stringify(staffUuid));

        // ---- Category / severity / type / duration ----
        if (session.categoryId() != null) {
            out.put("category", session.categoryId());
        }

        if (session.level() != null) {
            out.put("severity", Integer.toString(session.level().id()));

            PunishType type = session.level().type();
            out.put("type", type.name());

            String durationRaw = safe(session.level().duration());

            out.put("duration", normalizeDuration(type, durationRaw));
            out.put("punishment", buildCompound(type, durationRaw));

            out.put("type_display", buildTypeDisplay(type, durationRaw));
        } else {
            out.put("severity", "");
            out.put("type", "");
            out.put("duration", "");
            out.put("punishment", "");
            out.put("type_display", "");
        }

        // ---- Reason / silent ----
        // Reason can be: null (not set yet), "" (explicit none), or actual text.
        String reason = session.reason();
        out.put("reason", reason == null ? "" : reason);

        out.put("silent", session.silent() ? "true" : "false");

        return Map.copyOf(out);
    }

    public static Map<String, String> forSession(
            PunishSession session,
            UUID staffUuid,
            String staffName,
            boolean targetOnline
    ) {
        Map<String, String> base = forSession(session, staffUuid, staffName);

        Map<String, String> out = new LinkedHashMap<>(base);
        applyTargetPresence(out, targetOnline);

        return Map.copyOf(out);
    }

    public static Map<String, String> merge(Map<String, String> base, Map<String, String> extra) {
        Map<String, String> safeExtra = (extra == null) ? Map.of() : extra;

        if (safeExtra.isEmpty() && base != null) {
            return base;
        }
        if (base == null || base.isEmpty()) {
            return safeExtra;
        }

        Map<String, String> out = new LinkedHashMap<>(base);
        out.putAll(safeExtra);
        return Map.copyOf(out);
    }

    public static String buildCompound(PunishType type, String durationToken) {
        if (type == null) return "";

        if (!type.supportsDuration()) {
            return type.name();
        }

        String d = durationToken == null ? "" : durationToken.trim();
        if (d.isEmpty()) {
            return type.name();
        }

        return "TEMP" + type.name() + ":" + d;
    }

    private static String normalizeDuration(PunishType type, String durationToken) {
        if (type == null) return safe(durationToken);

        if (!type.supportsDuration()) {
            return NA;
        }

        String d = durationToken == null ? "" : durationToken.trim();
        if (d.isEmpty()) {
            return PERMANENT;
        }

        return d;
    }

    private static String buildTypeDisplay(PunishType type, String durationToken) {
        if (type == null) return "";

        String base = toTitleCase(type.name());

        if (!type.supportsDuration()) {
            return base;
        }

        String d = durationToken == null ? "" : durationToken.trim();
        if (d.isEmpty()) {
            return base;
        }

        return "Temp" + base;
    }

    private static void applyTargetPresence(Map<String, String> out, boolean targetOnline) {
        out.put("target_online", Boolean.toString(targetOnline));

        String status = targetOnline ? ONLINE_TEXT : OFFLINE_TEXT;

        out.put("target_status", status);

        out.put("status", status);
    }

    private static String toTitleCase(String upper) {
        if (upper == null || upper.isBlank()) return "";
        String low = upper.toLowerCase(Locale.ROOT);
        return Character.toUpperCase(low.charAt(0)) + low.substring(1);
    }

    private static String stringify(UUID uuid) {
        return uuid == null ? "" : uuid.toString();
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
