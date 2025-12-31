/* =============================================================================
 * 🧩 PunishSession: Moderator punishment flow state (per /punish interaction)
 *
 * 📋 What it does
 * • Holds all temporary state for one moderator’s guided punishment flow.
 * • Tracks target identity, chosen category, chosen severity/level, reason input, and silent toggle.
 * • Provides completion checks for dispatching to LiteBans.
 *
 * 🔧 Examples
 * • PunishSession session = PunishSession.start(targetUuid, targetName);
 * • session.setCategoryId("griefing");
 * • session.setLevel(selectedLevel);
 * • session.setReason("Griefed spawn");
 *
 * ✨ Feedback (messages.yml keys)
 * • (N/A — state-only)
 * =============================================================================
 */
package com.github.arzormc.punish;

import com.github.arzormc.config.ConfigModels;

import java.util.Objects;
import java.util.UUID;

// ======================
// 🧩 State
// ======================

public final class PunishSession {

    private final UUID targetUuid;
    private final String targetName;

    private String categoryId;
    private ConfigModels.LevelSpec level;

    private String reason;

    private boolean silent;
    private boolean silentExplicitlySet;

    private PunishSession(UUID targetUuid, String targetName) {
        this.targetUuid = Objects.requireNonNull(targetUuid, "targetUuid");
        this.targetName = normalizeName(targetName);

        this.categoryId = null;
        this.level = null;

        this.reason = null;

        this.silent = false;
        this.silentExplicitlySet = false;
    }

    public static PunishSession start(UUID targetUuid, String targetName) {
        return new PunishSession(targetUuid, targetName);
    }

    // ======================
    // 🧩 Identity
    // ======================

    public UUID targetUuid() {
        return targetUuid;
    }

    public String targetName() {
        return targetName;
    }

    // ======================
    // 🧩 Selections
    // ======================

    public String categoryId() {
        return categoryId;
    }

    public void setCategoryId(String categoryId) {
        this.categoryId = normalizeCategoryId(categoryId);
    }

    public ConfigModels.LevelSpec level() {
        return level;
    }

    public void setLevel(ConfigModels.LevelSpec level) {
        this.level = Objects.requireNonNull(level, "level");
    }

    public String reason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = normalizeReason(reason);
    }

    // ======================
    // 🧩 Silent toggle
    // ======================

    public boolean silent() {
        return silent;
    }

    public void setSilent(boolean silent) {
        this.silent = silent;
        this.silentExplicitlySet = true;
    }

    public void applySilentDefaultIfUnset(boolean silentDefault) {
        if (silentExplicitlySet) return;
        this.silent = silentDefault;
    }

    // ======================
    // 🧩 Completion checks
    // ======================

    public boolean hasCategory() {
        return categoryId != null && !categoryId.isEmpty();
    }

    public boolean hasLevel() {
        return level != null;
    }

    public boolean hasReasonSet() {
        return reason != null;
    }

    public boolean isDispatchReady() {
        return hasCategory() && hasLevel() && hasReasonSet();
    }

    // ======================
    // 🧩 Internals
    // ======================

    private static String normalizeCategoryId(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("categoryId cannot be null");
        }
        String s = raw.trim();
        if (s.isEmpty()) {
            throw new IllegalArgumentException("categoryId cannot be empty");
        }
        return s;
    }

    private static String normalizeName(String raw) {
        if (raw == null) return "";
        return raw.trim();
    }

    private static String normalizeReason(String raw) {
        if (raw == null) return null;
        String trimmed = raw.trim();
        return trimmed.isEmpty() ? "" : trimmed;
    }
}
