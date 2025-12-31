/* =============================================================================
 * 🧩 ConfigModels: Typed config + layout domain models
 *
 * 📋 What it does
 * • Defines all strongly-typed models used by config.yml + layout.yml.
 * • Keeps parsing/validation helpers close to the data they describe.
 * • Avoids any Bukkit/plugin state (safe to use anywhere).
 *
 * 🔧 Examples
 * • ConfigModels.CategoryDef category = ...
 * • ConfigModels.LevelSpec spec = ConfigModels.LevelSpec.parse("3=TEMPMUTE:30m");
 *
 * ✨ Feedback (messages.yml keys)
 * • (N/A — model-only)
 * =============================================================================
 */
package com.github.arzormc.config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

// ======================
// 🧩 Root Enums
// ======================

public final class ConfigModels {

    private ConfigModels() {
    }

    public enum OnInventoryCloseAction {
        CANCEL,
        KEEP;

        public static OnInventoryCloseAction parse(String raw, OnInventoryCloseAction fallback) {
            if (raw == null) return fallback;
            try {
                return OnInventoryCloseAction.valueOf(raw.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                return fallback;
            }
        }
    }

    public enum DenyAppearance {
        LOCKED,
        HIDE,
        REPLACE;

        public static DenyAppearance parse(String raw, DenyAppearance fallback) {
            if (raw == null) return fallback;
            try {
                return DenyAppearance.valueOf(raw.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                return fallback;
            }
        }
    }

    public enum Rarity {
        COMMON,
        UNCOMMON,
        RARE,
        UNCATEGORIZED;

        public static Rarity parse(String raw, Rarity fallback) {
            if (raw == null) return fallback;
            try {
                return Rarity.valueOf(raw.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                return fallback;
            }
        }
    }

    public enum PunishType {
        WARN(false),
        KICK(false),
        TEMPMUTE(true),
        TEMPBAN(true),
        BAN(true);

        private final boolean supportsDuration;

        PunishType(boolean supportsDuration) {
            this.supportsDuration = supportsDuration;
        }

        public boolean supportsDuration() {
            return supportsDuration;
        }

        public static PunishType parse(String raw) {
            if (raw == null) throw new IllegalArgumentException("PunishType cannot be null");
            return PunishType.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        }
    }

    // ======================
    // 🧩 Behavior (config.yml)
    // ======================

    public record BehaviorSettings(
            boolean cancelSessionsOnReload,
            boolean allowSessionReplace,
            OnInventoryCloseAction onInventoryClose,
            boolean silentDefault
    ) {
        public BehaviorSettings {
            Objects.requireNonNull(onInventoryClose, "onInventoryClose");
        }
    }

    // ======================
    // 🧩 Reason Input (config.yml)
    // ======================

    public static final class ReasonInputSettings {
        private final int timeoutSeconds;
        private final List<String> cancelWordsLower;
        private final boolean allowNoneWord;
        private final List<String> noneWordsLower;

        public ReasonInputSettings(
                int timeoutSeconds,
                List<String> cancelWords,
                boolean allowNoneWord,
                List<String> noneWords
        ) {
            this.timeoutSeconds = Math.max(timeoutSeconds, 0);
            this.cancelWordsLower = toLowerUnmodifiable(cancelWords);
            this.allowNoneWord = allowNoneWord;
            this.noneWordsLower = toLowerUnmodifiable(noneWords);
        }

        public int timeoutSeconds() { return timeoutSeconds; }
        public List<String> cancelWordsLower() { return cancelWordsLower; }
        public boolean allowNoneWord() { return allowNoneWord; }
        public List<String> noneWordsLower() { return noneWordsLower; }
    }

    // ======================
    // 🧩 Permissions UI (config.yml)
    // ======================

    public record DenyClickSound(boolean enabled, String sound, float volume, float pitch) {
        public DenyClickSound {
            sound = sound == null ? "" : sound.trim();
            volume = clampVolume(volume);
            pitch = clampPitch(pitch);
        }
    }

    public record PermissionUiSettings(DenyAppearance denyAppearance, DenyClickSound denyClickSound) {
        public PermissionUiSettings {
            Objects.requireNonNull(denyAppearance, "denyAppearance");
            Objects.requireNonNull(denyClickSound, "denyClickSound");
        }
    }

    // ======================
    // 🧩 Categories (config.yml)
    // ======================

    public static final class LevelSpec {
        private final int id;
        private final PunishType type;
        private final String duration;

        private LevelSpec(int id, PunishType type, String duration) {
            this.id = id;
            this.type = Objects.requireNonNull(type, "type");
            this.duration = duration == null ? "" : duration.trim();
        }

        public int id() { return id; }
        public PunishType type() { return type; }
        public String duration() { return duration; }

        public static LevelSpec parse(String raw) {
            if (raw == null) throw new IllegalArgumentException("LevelSpec cannot be null");

            String trimmed = raw.trim();
            int eq = trimmed.indexOf('=');
            if (eq <= 0 || eq == trimmed.length() - 1) {
                throw new IllegalArgumentException("Invalid level format (missing '='): " + raw);
            }

            String idPart = trimmed.substring(0, eq).trim();
            String rest = trimmed.substring(eq + 1).trim();

            int id;
            try {
                id = Integer.parseInt(idPart);
            } catch (NumberFormatException ex) {
                throw new IllegalArgumentException("Invalid level id: " + raw, ex);
            }

            String typePart;
            String durationPart = "";

            int colon = rest.indexOf(':');
            if (colon >= 0) {
                typePart = rest.substring(0, colon).trim();
                durationPart = rest.substring(colon + 1).trim();
            } else {
                typePart = rest.trim();
            }

            PunishType type = PunishType.parse(typePart);

            if (!type.supportsDuration() && !durationPart.isBlank()) {
                throw new IllegalArgumentException("Duration not allowed for " + type.name() + ": " + raw);
            }

            return new LevelSpec(id, type, durationPart);
        }
    }

    public record CategoryDef(String id, Rarity rarity, List<LevelSpec> levels) {
        public CategoryDef {
            id = normalizeId(id);
            Objects.requireNonNull(rarity, "rarity");

            List<LevelSpec> safe = (levels == null) ? new ArrayList<>() : new ArrayList<>(levels);
            levels = Collections.unmodifiableList(safe);
        }

        public boolean hasSingleLevel() {
            return levels.size() == 1;
        }
    }

    // ======================
    // 🧩 Layout models (layout.yml)
    // ======================

    public record MenuDefinition(int rows, boolean fillEmptySlots, String fillerMaterial) {
        public MenuDefinition {
            rows = clampRows(rows);
            fillerMaterial = fillerMaterial == null ? "" : fillerMaterial.trim();
        }

        public int size() { return rows * 9; }
    }

    public record LayoutIcon(int slot, String material) {
        public LayoutIcon {
            material = material == null ? "" : material.trim();
        }
    }

    public record TempPermIcon(String tempMaterial, String permMaterial) {
        public TempPermIcon {
            tempMaterial = tempMaterial == null ? "" : tempMaterial.trim();
            permMaterial = permMaterial == null ? "" : permMaterial.trim();
        }
    }

    public record SeverityIcons(
            String warnMaterial,
            String kickMaterial,
            TempPermIcon ban,
            TempPermIcon mute
    ) {
        public SeverityIcons {
            warnMaterial = warnMaterial == null ? "" : warnMaterial.trim();
            kickMaterial = kickMaterial == null ? "" : kickMaterial.trim();
            Objects.requireNonNull(ban, "ban");
            Objects.requireNonNull(mute, "mute");
        }

        public String materialFor(LevelSpec level) {
            if (level == null) return "PAPER";

            return switch (level.type()) {
                case WARN -> defaultOr(warnMaterial, "PAPER");
                case KICK -> defaultOr(kickMaterial, "LEATHER_BOOTS");

                case TEMPBAN -> defaultOr(ban.tempMaterial(), "IRON_SWORD");
                case BAN -> defaultOr(ban.permMaterial(), "NETHERITE_SWORD");

                case TEMPMUTE -> {
                    boolean isPermMute = level.duration() == null || level.duration().isBlank();
                    yield isPermMute
                            ? defaultOr(mute.permMaterial(), "OAK_SIGN")
                            : defaultOr(mute.tempMaterial(), "CLOCK");
                }
            };
        }

        private static String defaultOr(String val, String fallback) {
            return (val == null || val.isBlank()) ? fallback : val;
        }
    }

    public record HistoryFilterDef(int slot, String material) {
        public HistoryFilterDef {
            slot = slot < 0 ? -1 : slot;
            material = material == null ? "" : material.trim();
        }

        public boolean isValid() {
            return slot >= 0 && !material.isBlank();
        }
    }

    public record HistoryMenuLayout(
            List<Integer> contentSlots,
            String entryMaterial,
            String emptyMaterial,
            HistoryFilterDef filterAll,
            HistoryFilterDef filterBans,
            HistoryFilterDef filterMutes,
            HistoryFilterDef filterWarns,
            HistoryFilterDef filterKicks,
            LayoutIcon prevButton,
            LayoutIcon nextButton,
            LayoutIcon backButton
    ) {
        public HistoryMenuLayout {
            contentSlots = (contentSlots == null) ? List.of() : List.copyOf(contentSlots);
            entryMaterial = entryMaterial == null ? "" : entryMaterial.trim();
            emptyMaterial = emptyMaterial == null ? "" : emptyMaterial.trim();

            Objects.requireNonNull(filterAll, "filterAll");
            Objects.requireNonNull(filterBans, "filterBans");
            Objects.requireNonNull(filterMutes, "filterMutes");
            Objects.requireNonNull(filterWarns, "filterWarns");
            Objects.requireNonNull(filterKicks, "filterKicks");

            Objects.requireNonNull(prevButton, "prevButton");
            Objects.requireNonNull(nextButton, "nextButton");
            Objects.requireNonNull(backButton, "backButton");
        }
    }

    // ======================
    // 🧩 Helpers
    // ======================

    private static List<String> toLowerUnmodifiable(List<String> in) {
        if (in == null || in.isEmpty()) return Collections.emptyList();
        List<String> out = new ArrayList<>(in.size());
        for (String s : in) {
            if (s == null) continue;
            String t = s.trim();
            if (!t.isEmpty()) out.add(t.toLowerCase(Locale.ROOT));
        }
        return Collections.unmodifiableList(out);
    }

    private static String normalizeId(String raw) {
        if (raw == null) throw new IllegalArgumentException("id cannot be null");
        String id = raw.trim();
        if (id.isEmpty()) throw new IllegalArgumentException("id cannot be empty");
        return id;
    }

    private static int clampRows(int rows) {
        return Math.max(1, Math.min(rows, 6));
    }

    private static float clampVolume(float volume) {
        return Math.max(0.0f, Math.min(volume, 2.0f));
    }

    private static float clampPitch(float pitch) {
        return Math.max(0.5f, Math.min(pitch, 2.0f));
    }
}
