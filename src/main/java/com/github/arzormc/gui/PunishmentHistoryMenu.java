/* =============================================================================
 * 🧩 PunishmentHistoryMenu: Target punishment history viewer
 *
 * 📋 What it does
 * • Displays a paginated list of a target's LiteBans punishment history.
 * • Supports filtering by punishment type (ALL / BANS / MUTES / WARNS / KICKS).
 * • Enforces permission-gated visibility via PermissionService.
 * • Loads LiteBans data asynchronously and renders safely on the main thread.
 * • Provides a back button to return to the category menu without cancelling session.
 *
 * ✅ Shift-click entry actions (permission gated)
 * • SHIFT + LEFT on ACTIVE/REINSTATED entry => Pardon (requires reason + confirm)
 * • SHIFT + RIGHT on REVERTED entry         => Reinstate (requires reason + confirm)
 *
 * ✅ Flip rules (requested tweak)
 * • After a punishment is reverted then reinstated, it can be reverted again.
 * • You can keep flipping between REVERTED <-> REINSTATED while:
 *     - it is still not expired (until > now) OR
 *     - it is permanent (until == 0)
 *
 * ✅ Reinstate rules
 * • Cannot reinstate if the punishment has already expired (until <= now).
 * • Reinstating does NOT wipe "Removed By/Date/Reason" history.
 * • Reinstating appends "Reissued By/Date/Reason" lines for audit trail.
 * • Status becomes REINSTATED while active (not ACTIVE).
 *
 * ✨ Feedback (messages.yml keys)
 * • menu.history.title
 * • menu.history.empty
 * • menu.history.entry.name
 * • menu.history.entry.lore
 * • menu.history.entry.lore-removed
 * • menu.history.entry.reissued-by
 * • menu.history.entry.reissued-date
 * • menu.history.entry.reissued-reason
 * • menu.history.entry.action-pardon
 * • menu.history.entry.action-reinstate
 * • menu.history.filters.*.name / lore
 * • menu.history.prev.name / lore
 * • menu.history.next.name / lore
 * • menu.denied.click
 * • menu.back.name
 * • menu.back.lore
 * • menu.history.action.line
 * • menu.history.action.reinstate-expired
 * =============================================================================
 */
package com.github.arzormc.gui;

import com.github.arzormc.config.ConfigManager;
import com.github.arzormc.config.ConfigModels;
import com.github.arzormc.config.MessageService;
import com.github.arzormc.config.PlaceholderUtil;
import com.github.arzormc.punish.PermissionService;
import com.github.arzormc.punish.PunishSession;
import com.github.arzormc.punish.ReasonPromptService;
import com.github.arzormc.punish.SessionManager;

import litebans.api.Database;
import net.kyori.adventure.text.Component;

import org.bukkit.Bukkit;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.charset.StandardCharsets;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// ======================
// 🧩 Menu
// ======================

public final class PunishmentHistoryMenu implements Listener {

    private static final String TITLE_KEY = "menu.history.title";
    private static final String EMPTY_KEY = "menu.history.empty";

    private static final String ENTRY_NAME_KEY = "menu.history.entry.name";
    private static final String ENTRY_LORE_KEY = "menu.history.entry.lore";
    private static final String ENTRY_LORE_REMOVED_KEY = "menu.history.entry.lore-removed";
    private static final String ENTRY_ACTION_PARDON_KEY = "menu.history.entry.action-pardon";
    private static final String ENTRY_ACTION_REINSTATE_KEY = "menu.history.entry.action-reinstate";

    private static final String ENTRY_REISSUED_BY_KEY = "menu.history.entry.reissued-by";
    private static final String ENTRY_REISSUED_DATE_KEY = "menu.history.entry.reissued-date";
    private static final String ENTRY_REISSUED_REASON_KEY = "menu.history.entry.reissued-reason";

    private static final String BACK_NAME_KEY = "menu.back.name";
    private static final String BACK_LORE_KEY = "menu.back.lore";

    private static final String PREV_NAME_KEY = "menu.history.prev.name";
    private static final String PREV_LORE_KEY = "menu.history.prev.lore";

    private static final String NEXT_NAME_KEY = "menu.history.next.name";
    private static final String NEXT_LORE_KEY = "menu.history.next.lore";

    private static final String DENIED_CLICK_KEY = "menu.denied.click";

    private static final String ACTION_LINE_KEY = "menu.history.action.line";
    private static final String REINSTATE_EXPIRED_KEY = "menu.history.action.reinstate-expired";

    private static final String CONFIRM_NAME_KEY = "menu.confirm-button.name";
    private static final String CONFIRM_LORE_KEY = "menu.confirm-button.lore";
    private static final String CANCEL_NAME_KEY = "menu.cancel-button.name";
    private static final String CANCEL_LORE_KEY = "menu.cancel-button.lore";

    private static final int PAGE_SIZE = 28;
    private static final int FETCH_MULTIPLIER = 4;

    private static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
                    .withZone(ZoneId.systemDefault());

    private static final String REISSUE_MARKER = "||REISSUE||";
    private static final Pattern HEX_COLOR = Pattern.compile("<#([0-9a-fA-F]{6})>");

    private static final String SQL_UNION = """
            SELECT u.id AS id,
                   u.type AS type,
                   u.reason AS reason,
                   u.staff AS staff,
                   u.time AS time,
                   u.until AS until,
                   u.active AS active,
                   u.removed_by_name AS removed_by_name,
                   u.removed_by_reason AS removed_by_reason,
                   u.removed_by_date AS removed_by_date
            FROM (
                SELECT ID AS id,
                       'BAN'  AS type,
                       REASON AS reason,
                       BANNED_BY_NAME AS staff,
                       TIME AS time,
                       UNTIL AS until,
                       ACTIVE AS active,
                       REMOVED_BY_NAME AS removed_by_name,
                       REMOVED_BY_REASON AS removed_by_reason,
                       REMOVED_BY_DATE AS removed_by_date
                FROM PUBLIC.LITEBANS_BANS
                WHERE UUID = ?

                UNION ALL

                SELECT ID AS id,
                       'MUTE' AS type,
                       REASON AS reason,
                       BANNED_BY_NAME AS staff,
                       TIME AS time,
                       UNTIL AS until,
                       ACTIVE AS active,
                       REMOVED_BY_NAME AS removed_by_name,
                       REMOVED_BY_REASON AS removed_by_reason,
                       REMOVED_BY_DATE AS removed_by_date
                FROM PUBLIC.LITEBANS_MUTES
                WHERE UUID = ?

                UNION ALL

                SELECT ID AS id,
                       'WARN' AS type,
                       REASON AS reason,
                       BANNED_BY_NAME AS staff,
                       TIME AS time,
                       UNTIL AS until,
                       ACTIVE AS active,
                       REMOVED_BY_NAME AS removed_by_name,
                       REMOVED_BY_REASON AS removed_by_reason,
                       REMOVED_BY_DATE AS removed_by_date
                FROM PUBLIC.LITEBANS_WARNINGS
                WHERE UUID = ?

                UNION ALL

                SELECT ID AS id,
                       'KICK' AS type,
                       REASON AS reason,
                       BANNED_BY_NAME AS staff,
                       TIME AS time,
                       0 AS until,
                       0 AS active,
                       '' AS removed_by_name,
                       '' AS removed_by_reason,
                       NULL AS removed_by_date
                FROM PUBLIC.LITEBANS_KICKS
                WHERE UUID = ?
            ) u
            ORDER BY u.time DESC
            LIMIT ? OFFSET ?
            """;

    private static final String SQL_COUNTS = """
            SELECT u.type AS type, u.c AS c
            FROM (
                SELECT 'BAN'  AS type, COUNT(*) AS c FROM PUBLIC.LITEBANS_BANS     WHERE UUID = ?
                UNION ALL
                SELECT 'MUTE' AS type, COUNT(*) AS c FROM PUBLIC.LITEBANS_MUTES    WHERE UUID = ?
                UNION ALL
                SELECT 'WARN' AS type, COUNT(*) AS c FROM PUBLIC.LITEBANS_WARNINGS WHERE UUID = ?
                UNION ALL
                SELECT 'KICK' AS type, COUNT(*) AS c FROM PUBLIC.LITEBANS_KICKS    WHERE UUID = ?
            ) u
            """;

    private static final String SQL_PARDON_BAN = """
            UPDATE PUBLIC.LITEBANS_BANS
            SET ACTIVE = 0,
                REMOVED_BY_NAME = ?,
                REMOVED_BY_REASON = ?,
                REMOVED_BY_DATE = ?
            WHERE ID = ?
            """;

    private static final String SQL_PARDON_MUTE = """
            UPDATE PUBLIC.LITEBANS_MUTES
            SET ACTIVE = 0,
                REMOVED_BY_NAME = ?,
                REMOVED_BY_REASON = ?,
                REMOVED_BY_DATE = ?
            WHERE ID = ?
            """;

    private static final String SQL_PARDON_WARN = """
            UPDATE PUBLIC.LITEBANS_WARNINGS
            SET ACTIVE = 0,
                REMOVED_BY_NAME = ?,
                REMOVED_BY_REASON = ?,
                REMOVED_BY_DATE = ?
            WHERE ID = ?
            """;

    private static final String SQL_GET_REMOVED_REASON_BAN =
            "SELECT REMOVED_BY_REASON AS r FROM PUBLIC.LITEBANS_BANS WHERE ID = ?";
    private static final String SQL_GET_REMOVED_REASON_MUTE =
            "SELECT REMOVED_BY_REASON AS r FROM PUBLIC.LITEBANS_MUTES WHERE ID = ?";
    private static final String SQL_GET_REMOVED_REASON_WARN =
            "SELECT REMOVED_BY_REASON AS r FROM PUBLIC.LITEBANS_WARNINGS WHERE ID = ?";

    private static final String SQL_REINSTATE_BAN = """
            UPDATE PUBLIC.LITEBANS_BANS
            SET ACTIVE = 1,
                UNTIL = ?,
                REMOVED_BY_REASON = ?
            WHERE ID = ?
            """;

    private static final String SQL_REINSTATE_MUTE = """
            UPDATE PUBLIC.LITEBANS_MUTES
            SET ACTIVE = 1,
                UNTIL = ?,
                REMOVED_BY_REASON = ?
            WHERE ID = ?
            """;

    private static final String SQL_REINSTATE_WARN = """
            UPDATE PUBLIC.LITEBANS_WARNINGS
            SET ACTIVE = 1,
                UNTIL = ?,
                REMOVED_BY_REASON = ?
            WHERE ID = ?
            """;

    private final ConfigManager config;
    private final MessageService messages;
    private final ItemFactory items;
    private final PermissionService perms;
    private final SessionManager sessions;
    private final ReasonPromptService reasonPrompts;
    private final GuiManager gui;

    private final JavaPlugin plugin;

    private final Map<UUID, Integer> pageByModerator = new HashMap<>();
    private final Map<UUID, Filter> filterByModerator = new HashMap<>();
    private final Map<UUID, EnumMap<Filter, Integer>> filterSlotByModerator = new HashMap<>();

    private final Map<UUID, Map<Integer, Entry>> entryBySlotByModerator = new HashMap<>();

    private final Map<UUID, PendingAction> pendingByModerator = new HashMap<>();
    private final Map<UUID, ViewState> viewStateByModerator = new HashMap<>();

    // ======================
    // 🧩 Filters
    // ======================

    private enum Filter {
        ALL("all"),
        BANS("bans"),
        MUTES("mutes"),
        WARNS("warns"),
        KICKS("kicks");

        private final String id;

        Filter(String id) {
            this.id = id;
        }

        public String id() {
            return id;
        }
    }

    private enum ViewState {
        LIST,
        ACTION_CONFIRM
    }

    private enum ActionType {
        PARDON,
        REINSTATE
    }

    private record Entry(
            long id,
            String type,
            String reason,
            String staff,
            long timeMillis,
            long untilMillis,
            boolean active,
            String removedByName,
            String removedByReason,
            long removedByDateMillis,
            boolean untilWasSeconds
    ) {
    }

    private record PendingAction(
            ActionType actionType,
            long id,
            String baseType, // BAN / MUTE / WARN
            UUID targetUuid,
            String targetName,
            long timeMillis,
            long untilMillis,
            boolean untilWasSeconds,
            long removedByDateMillis,
            String reason,
            String staffName
    ) {
    }

    private record ReissueInfo(
            String removedReason,
            String reissuedBy,
            long reissuedAtMillis,
            String reissuedReason
    ) {
    }

    public PunishmentHistoryMenu(
            ConfigManager config,
            MessageService messages,
            ItemFactory items,
            PermissionService perms,
            SessionManager sessions,
            ReasonPromptService reasonPrompts,
            GuiManager gui
    ) {
        this.config = Objects.requireNonNull(config, "config");
        this.messages = Objects.requireNonNull(messages, "messages");
        this.items = Objects.requireNonNull(items, "items");
        this.perms = Objects.requireNonNull(perms, "perms");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.reasonPrompts = Objects.requireNonNull(reasonPrompts, "reasonPrompts");
        this.gui = Objects.requireNonNull(gui, "gui");

        this.plugin = JavaPlugin.getProvidingPlugin(getClass());
    }

    // ======================
    // 🧩 Open
    // ======================

    public void open(Player moderator, PunishSession session) {
        Objects.requireNonNull(moderator, "moderator");
        Objects.requireNonNull(session, "session");

        if (!perms.canViewHistory(moderator)) {
            perms.playDenyClick(moderator);
            moderator.sendMessage(messages.component(DENIED_CLICK_KEY));
            return;
        }

        UUID modUuid = moderator.getUniqueId();

        pageByModerator.putIfAbsent(modUuid, 0);
        filterByModerator.putIfAbsent(modUuid, Filter.ALL);
        filterSlotByModerator.putIfAbsent(modUuid, new EnumMap<>(Filter.class));
        viewStateByModerator.put(modUuid, ViewState.LIST);

        ConfigManager.Snapshot snap = config.snapshot();
        ConfigModels.MenuDefinition menuDef = snap.layout().historyMenu();

        Inventory inv = Bukkit.createInventory(
                new GuiManager.Holder(GuiManager.MenuType.HISTORY, modUuid),
                menuDef.size(),
                messages.component(TITLE_KEY)
        );

        if (menuDef.fillEmptySlots()) {
            ItemStack filler = items.filler(menuDef);
            for (int i = 0; i < inv.getSize(); i++) inv.setItem(i, filler);
        }

        Bukkit.getPluginManager().registerEvents(this, plugin);
        moderator.openInventory(inv);

        loadAsync(moderator, session);
    }

    // ======================
    // 🧩 Data loading
    // ======================

    private void loadAsync(Player moderator, PunishSession session) {
        UUID modUuid = moderator.getUniqueId();
        int page = pageByModerator.getOrDefault(modUuid, 0);
        Filter filter = filterByModerator.getOrDefault(modUuid, Filter.ALL);

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            EnumMap<Filter, Integer> totals = fetchTotals(session.targetUuid());
            List<Entry> rows = fetch(session.targetUuid(), filter, page);

            Bukkit.getScheduler().runTask(plugin, () -> {
                if (isNotViewingHistory(moderator, modUuid)) return;
                if (viewStateByModerator.getOrDefault(modUuid, ViewState.LIST) != ViewState.LIST) return;

                if (rows.isEmpty() && page > 0) {
                    int newPage = page - 1;
                    pageByModerator.put(modUuid, newPage);

                    Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                        EnumMap<Filter, Integer> totals2 = fetchTotals(session.targetUuid());
                        List<Entry> rows2 = fetch(session.targetUuid(), filter, newPage);

                        Bukkit.getScheduler().runTask(plugin, () -> {
                            if (isNotViewingHistory(moderator, modUuid)) return;
                            if (viewStateByModerator.getOrDefault(modUuid, ViewState.LIST) != ViewState.LIST) return;
                            render(moderator, session, rows2, totals2);
                        });
                    });

                    return;
                }

                render(moderator, session, rows, totals);
            });
        });
    }

    private EnumMap<Filter, Integer> fetchTotals(UUID targetUuid) {
        EnumMap<Filter, Integer> out = new EnumMap<>(Filter.class);
        out.put(Filter.BANS, 0);
        out.put(Filter.MUTES, 0);
        out.put(Filter.WARNS, 0);
        out.put(Filter.KICKS, 0);
        out.put(Filter.ALL, 0);

        try (PreparedStatement ps = Database.get().prepareStatement(SQL_COUNTS)) {
            String uuidStr = targetUuid.toString();

            ps.setString(1, uuidStr);
            ps.setString(2, uuidStr);
            ps.setString(3, uuidStr);
            ps.setString(4, uuidStr);

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String type = upper(safe(rs.getString("type")));
                    int count = (int) rs.getLong("c");

                    if (type.contains("BAN")) out.put(Filter.BANS, count);
                    else if (type.contains("MUTE")) out.put(Filter.MUTES, count);
                    else if (type.equals("WARN")) out.put(Filter.WARNS, count);
                    else if (type.equals("KICK")) out.put(Filter.KICKS, count);
                }
            }
        } catch (Exception ex) {
            plugin.getLogger().warning(
                    "[PunishmentHistoryMenu] Failed to load punishment totals for "
                            + targetUuid + ": " + ex.getMessage()
            );
        }

        int sum = out.getOrDefault(Filter.BANS, 0)
                + out.getOrDefault(Filter.MUTES, 0)
                + out.getOrDefault(Filter.WARNS, 0)
                + out.getOrDefault(Filter.KICKS, 0);

        out.put(Filter.ALL, sum);

        return out;
    }

    private List<Entry> fetch(UUID targetUuid, Filter filter, int page) {
        List<Entry> out = new ArrayList<>();

        int fetchLimit = PAGE_SIZE * FETCH_MULTIPLIER;
        int offset = page * fetchLimit;

        try (PreparedStatement ps = Database.get().prepareStatement(SQL_UNION)) {
            String uuidStr = targetUuid.toString();

            ps.setString(1, uuidStr);
            ps.setString(2, uuidStr);
            ps.setString(3, uuidStr);
            ps.setString(4, uuidStr);

            ps.setInt(5, fetchLimit);
            ps.setInt(6, offset);

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String type = upper(safe(rs.getString("type")));
                    if (type.isEmpty()) continue;
                    if (!matches(type, filter)) continue;

                    long id;
                    try {
                        id = rs.getLong("id");
                    } catch (Exception ignored) {
                        continue;
                    }
                    if (id <= 0L) continue;

                    long timeRaw = rs.getLong("time");
                    long timeMillis = normalizeEpochMillis(timeRaw);

                    long untilMillis = 0L;
                    boolean untilWasSeconds = false;
                    try {
                        long untilRaw = rs.getLong("until");
                        untilWasSeconds = (untilRaw > 0L && untilRaw < 100_000_000_000L);
                        untilMillis = normalizeEpochMillis(untilRaw);
                    } catch (Exception ignored) {
                    }

                    boolean active = false;
                    try {
                        active = rs.getBoolean("active");
                    } catch (Exception ignored) {
                    }

                    String removedByName = "";
                    String removedByReason = "";
                    long removedByDateMillis = 0L;

                    try {
                        removedByName = safe(rs.getString("removed_by_name"));
                    } catch (Exception ignored) {
                    }
                    try {
                        removedByReason = safe(rs.getString("removed_by_reason"));
                    } catch (Exception ignored) {
                    }
                    try {
                        Timestamp ts = rs.getTimestamp("removed_by_date");
                        if (ts != null) removedByDateMillis = ts.getTime();
                    } catch (Exception ignored) {
                    }

                    out.add(new Entry(
                            id,
                            type,
                            safe(rs.getString("reason")),
                            safe(rs.getString("staff")),
                            timeMillis,
                            untilMillis,
                            active,
                            removedByName,
                            removedByReason,
                            removedByDateMillis,
                            untilWasSeconds
                    ));

                    if (out.size() >= PAGE_SIZE) break;
                }
            }
        } catch (Exception ex) {
            plugin.getLogger().warning(
                    "[PunishmentHistoryMenu] Failed to load punishment history for "
                            + targetUuid + ": " + ex.getMessage()
            );
        }

        return out;
    }

    private static boolean matches(String type, Filter filter) {
        return switch (filter) {
            case ALL -> true;
            case BANS -> type.contains("BAN");
            case MUTES -> type.contains("MUTE");
            case WARNS -> type.equals("WARN");
            case KICKS -> type.equals("KICK");
        };
    }

    private static long normalizeEpochMillis(long raw) {
        if (raw <= 0L) return 0L;
        if (raw < 100_000_000_000L) return raw * 1000L;
        return raw;
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    private static String upper(String s) {
        return s == null ? "" : s.toUpperCase(Locale.ROOT);
    }

    private static boolean isNotViewingHistory(Player player, UUID moderatorUuid) {
        if (player == null || !player.isOnline()) return true;

        Inventory top = player.getOpenInventory().getTopInventory();
        if (!(top.getHolder() instanceof GuiManager.Holder(GuiManager.MenuType type, UUID holderUuid))) return true;

        return type != GuiManager.MenuType.HISTORY
                || !holderUuid.equals(moderatorUuid);
    }

    // ======================
    // 🧩 Render
    // ======================

    private void render(Player moderator, PunishSession session, List<Entry> entries, EnumMap<Filter, Integer> totals) {
        UUID modUuid = moderator.getUniqueId();

        if (viewStateByModerator.getOrDefault(modUuid, ViewState.LIST) != ViewState.LIST) return;

        ConfigManager.Snapshot snap = config.snapshot();
        ConfigModels.MenuDefinition menuDef = snap.layout().historyMenu();
        ConfigModels.HistoryMenuLayout history = snap.layout().history();

        Inventory inv = moderator.getOpenInventory().getTopInventory();

        inv.clear();
        if (menuDef.fillEmptySlots()) {
            ItemStack filler = items.filler(menuDef);
            for (int i = 0; i < inv.getSize(); i++) inv.setItem(i, filler);
        }

        renderFilters(moderator, inv, totals);

        inv.setItem(history.backButton().slot(), items.icon(history.backButton().material(), BACK_NAME_KEY, BACK_LORE_KEY, Map.of()));
        inv.setItem(history.prevButton().slot(), items.icon(history.prevButton().material(), PREV_NAME_KEY, PREV_LORE_KEY, Map.of()));
        inv.setItem(history.nextButton().slot(), items.icon(history.nextButton().material(), NEXT_NAME_KEY, NEXT_LORE_KEY, Map.of()));

        Map<Integer, Entry> slotMap = new HashMap<>();
        entryBySlotByModerator.put(modUuid, slotMap);

        List<Integer> contentSlots = history.contentSlots();
        if (entries.isEmpty()) {
            if (!contentSlots.isEmpty()) {
                int s = contentSlots.getFirst();
                inv.setItem(s, items.icon(history.emptyMaterial(), EMPTY_KEY, null, Map.of()));
            }
            return;
        }

        long now = System.currentTimeMillis();

        int idx = 0;
        for (Entry e : entries) {
            if (idx >= contentSlots.size()) break;

            Map<String, String> ph = new HashMap<>(
                    PlaceholderUtil.forSession(
                            session,
                            moderator.getUniqueId(),
                            moderator.getName()
                    )
            );

            ph.put("type", buildHistoryTypeDisplay(e.type(), e.timeMillis(), e.untilMillis()));
            ph.put("duration", buildHistoryDurationDisplay(e.type(), e.timeMillis(), e.untilMillis()));

            ph.put("reason", e.reason());
            ph.put("staff", e.staff());
            ph.put("date", DATE_FMT.format(Instant.ofEpochMilli(e.timeMillis())));

            boolean activeNow = isActiveNow(e, now);

            ReissueInfo audit = parseReissueInfo(e.removedByReason());
            boolean wasRemovedEver = wasRemovedEver(e) || !safe(audit.removedReason()).isBlank();
            boolean removedNow = wasRemovedEver && !e.active();

            String status;
            if (removedNow) status = "REVERTED";
            else if (activeNow && wasRemovedEver) status = "REINSTATED";
            else if (activeNow) status = "ACTIVE";
            else status = "INACTIVE";

            ph.put("status", status);

            String remaining;
            if (activeNow) {
                if (e.untilMillis() <= 0L) remaining = "Permanent";
                else remaining = formatCompactDuration(Math.max(0L, e.untilMillis() - now));
            } else {
                remaining = "N/A";
            }
            ph.put("remaining", remaining);

            ph.put("removed_by", safe(e.removedByName()).isBlank() ? "N/A" : e.removedByName());
            ph.put("removed_reason", safe(audit.removedReason()).isBlank() ? "N/A" : audit.removedReason());
            ph.put("removed_date", e.removedByDateMillis() <= 0L ? "N/A" : DATE_FMT.format(Instant.ofEpochMilli(e.removedByDateMillis())));

            String loreKey = wasRemovedEver ? ENTRY_LORE_REMOVED_KEY : ENTRY_LORE_KEY;

            String entryMat = entryMaterialForType(history, e.type());
            ItemStack item = items.icon(entryMat, ENTRY_NAME_KEY, loreKey, ph);

            if (activeNow && wasRemovedEver && audit.reissuedAtMillis() > 0L) {
                appendReissueLines(item, ph, audit);
            }

            appendActionHintLine(moderator, item, removedNow, activeNow, now, e);

            int slot = contentSlots.get(idx++);
            if (slot < 0 || slot >= inv.getSize()) continue;

            inv.setItem(slot, item);
            slotMap.put(slot, e);
        }
    }

    private void appendReissueLines(ItemStack item, Map<String, String> basePh, ReissueInfo audit) {
        if (item == null) return;

        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;

        List<Component> lore = meta.lore();
        if (lore == null) lore = new ArrayList<>();
        else lore = new ArrayList<>(lore);

        Map<String, String> ph = new HashMap<>(basePh);

        ph.put("reissued_by", safe(audit.reissuedBy()));
        ph.put("reissued_date", DATE_FMT.format(Instant.ofEpochMilli(audit.reissuedAtMillis())));
        ph.put("reissued_reason", safe(audit.reissuedReason()));

        lore.add(messages.component(ENTRY_REISSUED_BY_KEY, ph));
        lore.add(messages.component(ENTRY_REISSUED_DATE_KEY, ph));
        lore.add(messages.component(ENTRY_REISSUED_REASON_KEY, ph));

        meta.lore(lore);
        item.setItemMeta(meta);
    }

    private void appendActionHintLine(
            Player moderator,
            ItemStack item,
            boolean removedNow,
            boolean activeNow,
            long nowMillis,
            Entry entry
    ) {
        if (item == null) return;

        String key = null;

        if (activeNow && perms.canPardonHistory(moderator)) {
            key = ENTRY_ACTION_PARDON_KEY;
        }

        if (removedNow && perms.canReinstateHistory(moderator)) {
            if (canReinstate(entry, nowMillis)) {
                key = ENTRY_ACTION_REINSTATE_KEY;
            }
        }

        if (key == null) return;

        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;

        List<Component> lore = meta.lore();
        if (lore == null) lore = new ArrayList<>();
        else lore = new ArrayList<>(lore);

        // Always keep the spacer line immediately before the action hint line.
        lore.add(Component.empty());
        lore.add(messages.component(key));

        meta.lore(lore);
        item.setItemMeta(meta);
    }

    private static boolean wasRemovedEver(Entry e) {
        if (e == null) return false;
        if (e.removedByDateMillis() > 0L) return true;
        return !safe(e.removedByName()).isBlank();
    }

    private static boolean isActiveNow(Entry e, long nowMillis) {
        if (e == null) return false;
        if (!e.active()) return false;

        long until = e.untilMillis();
        return until <= 0L || until > nowMillis;
    }

    private static boolean canReinstate(Entry e, long nowMillis) {
        if (e == null) return false;

        if (e.untilMillis() <= 0L) return true;
        return e.untilMillis() > nowMillis;
    }

    private static ReissueInfo parseReissueInfo(String rawRemovedReason) {
        String raw = safe(rawRemovedReason);
        if (raw.isBlank()) {
            return new ReissueInfo("", "", 0L, "");
        }

        int firstIdx = raw.indexOf(REISSUE_MARKER);
        String removedReason = (firstIdx >= 0 ? raw.substring(0, firstIdx) : raw).trim();

        int lastIdx = raw.lastIndexOf(REISSUE_MARKER);
        if (lastIdx < 0) {
            return new ReissueInfo(removedReason, "", 0L, "");
        }

        String payload = raw.substring(lastIdx + REISSUE_MARKER.length()).trim();
        String[] parts = payload.split(":", 3);
        if (parts.length != 3) {
            return new ReissueInfo(removedReason, "", 0L, "");
        }

        long at;
        try {
            at = Long.parseLong(parts[0]);
        } catch (Exception ignored) {
            at = 0L;
        }

        String by = decodeB64(parts[1]);
        String reason = decodeB64(parts[2]);

        return new ReissueInfo(removedReason, by, at, reason);
    }

    private static String keepReissueTrail(String existingRemovedReason) {
        String raw = safe(existingRemovedReason);
        int idx = raw.indexOf(REISSUE_MARKER);
        if (idx < 0) return "";
        return raw.substring(idx).trim();
    }

    private static String encodeB64(String s) {
        String v = safe(s);
        if (v.isEmpty()) return "";
        return Base64.getEncoder().encodeToString(v.getBytes(StandardCharsets.UTF_8));
    }

    private static String decodeB64(String b64) {
        String v = safe(b64);
        if (v.isEmpty()) return "";
        try {
            byte[] bytes = Base64.getDecoder().decode(v);
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            return "";
        }
    }

    private String lastHexFromMessage(String key) {
        String raw = safe(messages.raw(key));
        String last = null;

        Matcher m = HEX_COLOR.matcher(raw);
        while (m.find()) last = m.group(1);

        return last;
    }

    private static String wrapWithHex(String hex, String text) {
        if (hex == null || hex.isBlank()) return text;
        return "<#" + hex + ">" + text + "</#" + hex + ">";
    }

    private static String buildHistoryTypeDisplay(String baseType, long timeMillis, long untilMillis) {
        String t = baseType == null ? "" : baseType.trim().toUpperCase(Locale.ROOT);
        boolean temp = isTemporary(timeMillis, untilMillis);

        return switch (t) {
            case "BAN" -> temp ? "TEMPBAN" : "BAN";
            case "MUTE" -> temp ? "TEMPMUTE" : "MUTE";
            case "WARN" -> temp ? "TEMPWARN" : "WARN";
            case "KICK" -> "KICK";
            default -> t;
        };
    }

    private static String buildHistoryDurationDisplay(String baseType, long timeMillis, long untilMillis) {
        String t = baseType == null ? "" : baseType.trim().toUpperCase(Locale.ROOT);

        boolean supports = t.equals("BAN") || t.equals("MUTE") || t.equals("WARN");
        if (!supports) return "N/A";

        boolean temp = isTemporary(timeMillis, untilMillis);
        if (!temp) return "Permanent";

        long delta = Math.max(0L, untilMillis - timeMillis);
        if (delta == 0L) return "Permanent";

        return formatCompactDuration(delta);
    }

    private static boolean isTemporary(long timeMillis, long untilMillis) {
        if (untilMillis <= 0L) return false;
        if (timeMillis <= 0L) return true;
        return untilMillis > timeMillis;
    }

    private static String formatCompactDuration(long millis) {
        long seconds = millis / 1000L;

        long days = seconds / 86400L;
        seconds %= 86400L;

        long hours = seconds / 3600L;
        seconds %= 3600L;

        long minutes = seconds / 60L;
        seconds %= 60L;

        if (days > 0) {
            return hours > 0 ? (days + "d" + hours + "h") : (days + "d");
        }
        if (hours > 0) {
            return minutes > 0 ? (hours + "h" + minutes + "m") : (hours + "h");
        }
        if (minutes > 0) {
            return seconds > 0 ? (minutes + "m" + seconds + "s") : (minutes + "m");
        }
        return Math.max(1L, seconds) + "s";
    }

    private static String entryMaterialForType(ConfigModels.HistoryMenuLayout history, String typeRaw) {
        String unknown = unknownEntryMaterial(history);

        String type = (typeRaw == null) ? "" : typeRaw.trim().toUpperCase(Locale.ROOT);
        if (type.isEmpty()) return unknown;

        if (type.contains("BAN")) return requiredMaterial(history.filterBans(), unknown);
        if (type.contains("MUTE")) return requiredMaterial(history.filterMutes(), unknown);
        if (type.equals("WARN")) return requiredMaterial(history.filterWarns(), unknown);
        if (type.equals("KICK")) return requiredMaterial(history.filterKicks(), unknown);

        return unknown;
    }

    private static String requiredMaterial(ConfigModels.HistoryFilterDef def, String unknown) {
        if (def == null) return unknown;
        String mat = def.material();
        return (mat == null || mat.isBlank()) ? unknown : mat.trim();
    }

    private static String unknownEntryMaterial(ConfigModels.HistoryMenuLayout history) {
        if (history == null) return "BARRIER";
        String mat = history.emptyMaterial();
        return (mat == null || mat.isBlank()) ? "BARRIER" : mat.trim();
    }

    private void renderFilters(Player moderator, Inventory inv, EnumMap<Filter, Integer> totals) {
        UUID uuid = moderator.getUniqueId();
        Filter selected = filterByModerator.getOrDefault(uuid, Filter.ALL);

        ConfigModels.HistoryMenuLayout history = config.snapshot().layout().history();

        EnumMap<Filter, Integer> slots = new EnumMap<>(Filter.class);

        renderFilter(moderator, inv, slots, selected, totals, Filter.ALL, history.filterAll());
        renderFilter(moderator, inv, slots, selected, totals, Filter.BANS, history.filterBans());
        renderFilter(moderator, inv, slots, selected, totals, Filter.MUTES, history.filterMutes());
        renderFilter(moderator, inv, slots, selected, totals, Filter.WARNS, history.filterWarns());
        renderFilter(moderator, inv, slots, selected, totals, Filter.KICKS, history.filterKicks());

        filterSlotByModerator.put(uuid, slots);
    }

    private void renderFilter(
            Player moderator,
            Inventory inv,
            EnumMap<Filter, Integer> slotsOut,
            Filter selected,
            EnumMap<Filter, Integer> totals,
            Filter filter,
            ConfigModels.HistoryFilterDef def
    ) {
        if (def == null || !def.isValid()) return;
        if (perms.isHistoryFilterDenied(moderator, filter.id())) return;

        String base = "menu.history.filters." + filter.id();

        int total = totals == null ? 0 : totals.getOrDefault(filter, 0);

        ItemStack item = items.icon(
                def.material(),
                base + ".name",
                base + ".lore",
                Map.of(
                        "filter", filter.id(),
                        "selected", Boolean.toString(selected == filter),
                        "total", Integer.toString(total)
                )
        );

        item.addUnsafeEnchantment(Enchantment.UNBREAKING, 1);

        inv.setItem(def.slot(), item);
        slotsOut.put(filter, def.slot());
    }

    // ======================
    // 🧩 Events
    // ======================

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;

        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder() instanceof GuiManager.Holder(GuiManager.MenuType type, UUID holderUuid))) return;
        if (type != GuiManager.MenuType.HISTORY) return;

        UUID uuid = player.getUniqueId();
        if (!uuid.equals(holderUuid)) return;

        event.setCancelled(true);

        if (event.getClickedInventory() != top) return;

        int slot = event.getRawSlot();
        if (slot < 0 || slot >= top.getSize()) return;

        ViewState view = viewStateByModerator.getOrDefault(uuid, ViewState.LIST);

        if (view == ViewState.ACTION_CONFIRM) {
            handleActionConfirmClick(player, slot);
            return;
        }

        ConfigModels.HistoryMenuLayout history = config.snapshot().layout().history();

        if (slot == history.backButton().slot()) {
            sessions.get(player).ifPresent(s -> {
                GuiManager.MenuType last = s.lastMenu();
                if (last == GuiManager.MenuType.SEVERITY) {
                    gui.openSeverity(player, s);
                } else if (last == GuiManager.MenuType.CONFIRM) {
                    gui.openConfirm(player, s);
                } else {
                    gui.openCategory(player, s);
                }
            });
            return;
        }

        EnumMap<Filter, Integer> filterSlots = filterSlotByModerator.get(uuid);
        if (filterSlots != null) {
            Filter clicked = filterForSlot(filterSlots, slot);
            if (clicked != null) {
                if (perms.isHistoryFilterDenied(player, clicked.id())) {
                    perms.playDenyClick(player);
                    player.sendMessage(messages.component(DENIED_CLICK_KEY));
                    return;
                }

                filterByModerator.put(uuid, clicked);
                pageByModerator.put(uuid, 0);
                sessions.get(player).ifPresent(s -> loadAsync(player, s));
                return;
            }
        }

        if (slot == history.prevButton().slot()) {
            pageByModerator.compute(uuid, (k, v) -> Math.max(0, (v == null ? 0 : v) - 1));
            sessions.get(player).ifPresent(s -> loadAsync(player, s));
            return;
        }

        if (slot == history.nextButton().slot()) {
            pageByModerator.compute(uuid, (k, v) -> (v == null ? 1 : v + 1));
            sessions.get(player).ifPresent(s -> loadAsync(player, s));
            return;
        }

        if (!event.isShiftClick()) return;

        Map<Integer, Entry> slotMap = entryBySlotByModerator.get(uuid);
        if (slotMap == null) return;

        Entry entry = slotMap.get(slot);
        if (entry == null) return;

        String baseType = normalizeActionBaseType(entry.type());
        if (baseType.isEmpty()) return;

        sessions.get(player).ifPresent(session -> beginShiftAction(player, session, entry, event.getClick()));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;

        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder() instanceof GuiManager.Holder(GuiManager.MenuType type, UUID holderUuid))) return;
        if (type != GuiManager.MenuType.HISTORY) return;

        if (!player.getUniqueId().equals(holderUuid)) return;

        int topSize = top.getSize();
        for (int rawSlot : event.getRawSlots()) {
            if (rawSlot < topSize) {
                event.setCancelled(true);
                return;
            }
        }
    }

    private void beginShiftAction(Player player, PunishSession session, Entry entry, ClickType clickType) {
        UUID uuid = player.getUniqueId();

        long now = System.currentTimeMillis();

        ReissueInfo audit = parseReissueInfo(entry.removedByReason());
        boolean wasRemovedEver = wasRemovedEver(entry) || !safe(audit.removedReason()).isBlank();
        boolean removedNow = wasRemovedEver && !entry.active();

        boolean activeNow = isActiveNow(entry, now);

        ActionType action;
        if (clickType == ClickType.SHIFT_LEFT) {
            if (!activeNow) return;

            if (!perms.canPardonHistory(player)) {
                perms.playDenyClick(player);
                player.sendMessage(messages.component(DENIED_CLICK_KEY));
                return;
            }
            action = ActionType.PARDON;

        } else if (clickType == ClickType.SHIFT_RIGHT) {
            if (!removedNow) return;

            if (!perms.canReinstateHistory(player)) {
                perms.playDenyClick(player);
                player.sendMessage(messages.component(DENIED_CLICK_KEY));
                return;
            }

            if (!canReinstate(entry, now)) {
                player.sendMessage(messages.component(
                        REINSTATE_EXPIRED_KEY,
                        PlaceholderUtil.merge(
                                PlaceholderUtil.forSession(session, uuid, player.getName()),
                                Map.of("id", Long.toString(entry.id()))
                        )
                ));
                return;
            }

            action = ActionType.REINSTATE;
        } else {
            return;
        }

        PunishSession promptSession = PunishSession.start(session.targetUuid(), session.targetName());

        gui.markPrompting(uuid);
        player.closeInventory();

        reasonPrompts.begin(
                player,
                promptSession,
                (p, s) -> {
                    gui.clearPrompting(uuid);

                    String reason = (s == null ? "" : s.reason());
                    if (reason == null) reason = "";

                    PendingAction pending = new PendingAction(
                            action,
                            entry.id(),
                            normalizeActionBaseType(entry.type()),
                            session.targetUuid(),
                            session.targetName(),
                            entry.timeMillis(),
                            entry.untilMillis(),
                            entry.untilWasSeconds(),
                            entry.removedByDateMillis(),
                            reason,
                            player.getName()
                    );

                    pendingByModerator.put(uuid, pending);
                    openActionConfirm(player, session, pending);
                },
                (p, s) -> gui.clearPrompting(uuid)
        );
    }

    private void openActionConfirm(Player player, PunishSession session, PendingAction pending) {
        UUID uuid = player.getUniqueId();

        ConfigManager.Snapshot snap = config.snapshot();
        ConfigModels.MenuDefinition menuDef = snap.layout().confirmMenu();

        int size = menuDef.size();
        Inventory inv = Bukkit.createInventory(
                new GuiManager.Holder(GuiManager.MenuType.HISTORY, uuid),
                size,
                messages.component("menu.confirm.title")
        );

        if (menuDef.fillEmptySlots()) {
            ItemStack filler = items.filler(menuDef);
            for (int i = 0; i < size; i++) inv.setItem(i, filler);
        }

        Map<String, String> ph = new HashMap<>(PlaceholderUtil.forSession(session, uuid, player.getName()));

        String typeDisplay = buildHistoryTypeDisplay(pending.baseType(), pending.timeMillis(), pending.untilMillis());
        String durationDisplay = buildHistoryDurationDisplay(pending.baseType(), pending.timeMillis(), pending.untilMillis());

        String tag = null;
        String tagHex = null;

        if (pending.actionType() == ActionType.PARDON) {
            tag = "(Pardon)";
            tagHex = lastHexFromMessage(ENTRY_ACTION_PARDON_KEY);
        } else if (pending.actionType() == ActionType.REINSTATE) {
            tag = "(Reissue)"; // requested label; uses the Reinstate hint color
            tagHex = lastHexFromMessage(ENTRY_ACTION_REINSTATE_KEY);
        }

        if (tag != null) {
            typeDisplay = typeDisplay + " " + wrapWithHex(tagHex, tag);
        }

        ph.put("type", typeDisplay);
        ph.put("duration", durationDisplay);
        ph.put("reason", pending.reason() == null ? "" : pending.reason());

        if (safe(ph.get("category")).isBlank()) ph.put("category", "History");
        if (safe(ph.get("severity")).isBlank()) ph.put("severity", "—");

        String actionWord = (pending.actionType() == ActionType.PARDON) ? "PARDON" : "REINSTATE";
        ph.put("history_action", actionWord);
        ph.put("history_id", Long.toString(pending.id()));
        ph.put("history_action_line", messages.raw(ACTION_LINE_KEY));

        ConfigModels.LayoutIcon confirm = snap.layout().confirmConfirm();
        ConfigModels.LayoutIcon cancel = snap.layout().confirmCancel();

        int confirmSlot = clampSlot(confirm.slot(), size);
        int cancelSlot = clampSlot(cancel.slot(), size);

        inv.setItem(confirmSlot, items.icon(confirm.material(), CONFIRM_NAME_KEY, CONFIRM_LORE_KEY, ph));
        inv.setItem(cancelSlot, items.icon(cancel.material(), CANCEL_NAME_KEY, CANCEL_LORE_KEY, ph));

        if (snap.layout().confirmSummaryEnabled()) {
            ConfigModels.LayoutIcon summary = snap.layout().confirmSummary();
            int summarySlot = clampSlot(summary.slot(), size);
            inv.setItem(summarySlot, items.iconTrusted(summary.material(), "menu.summary.name", "menu.summary.lore", ph));
        }

        viewStateByModerator.put(uuid, ViewState.ACTION_CONFIRM);
        gui.markHistory(uuid);
        player.openInventory(inv);
    }

    private void handleActionConfirmClick(Player player, int slot) {
        UUID uuid = player.getUniqueId();

        ConfigManager.Snapshot snap = config.snapshot();

        ConfigModels.MenuDefinition menuDef = snap.layout().confirmMenu();
        int size = menuDef.size();

        ConfigModels.LayoutIcon confirm = snap.layout().confirmConfirm();
        ConfigModels.LayoutIcon cancel = snap.layout().confirmCancel();

        int confirmSlot = clampSlot(confirm.slot(), size);
        int cancelSlot = clampSlot(cancel.slot(), size);

        PendingAction pending = pendingByModerator.get(uuid);
        if (pending == null) {
            viewStateByModerator.put(uuid, ViewState.LIST);
            player.closeInventory();
            return;
        }

        if (slot == cancelSlot) {
            pendingByModerator.remove(uuid);
            viewStateByModerator.put(uuid, ViewState.LIST);
            player.closeInventory();
            return;
        }

        if (slot != confirmSlot) return;

        pendingByModerator.remove(uuid);

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            boolean ok;
            if (pending.actionType() == ActionType.PARDON) {
                ok = applyPardon(pending);
            } else {
                ok = applyReinstate(pending);
            }

            Bukkit.getScheduler().runTask(plugin, () -> {
                viewStateByModerator.put(uuid, ViewState.LIST);

                if (!player.isOnline()) return;

                if (!ok) {
                    player.sendMessage(messages.component(
                            "punish.dispatch.failed",
                            Map.of("reason", "history_action_failed")
                    ));
                } else {
                    String resultWord = (pending.actionType() == ActionType.PARDON) ? "reverted" : "reinstated";

                    player.sendMessage(messages.component(
                            "punish.success",
                            PlaceholderUtil.merge(
                                    PlaceholderUtil.merge(
                                            PlaceholderUtil.forSession(
                                                    PunishSession.start(pending.targetUuid(), pending.targetName()),
                                                    uuid,
                                                    player.getName()
                                            ),
                                            Map.of("reason", pending.reason() == null ? "" : pending.reason())
                                    ),
                                    Map.of("history_result", resultWord)
                            )
                    ));
                }

                player.closeInventory();
            });
        });
    }

    // ======================
    // 🧩 DB mutations
    // ======================

    private boolean applyPardon(PendingAction pending) {
        String type = pending.baseType();
        if (type.isEmpty()) return false;

        String sqlUpdate = switch (type) {
            case "BAN" -> SQL_PARDON_BAN;
            case "MUTE" -> SQL_PARDON_MUTE;
            case "WARN" -> SQL_PARDON_WARN;
            default -> throw new IllegalStateException("Unexpected baseType: " + type);
        };

        String sqlGet = switch (type) {
            case "BAN" -> SQL_GET_REMOVED_REASON_BAN;
            case "MUTE" -> SQL_GET_REMOVED_REASON_MUTE;
            case "WARN" -> SQL_GET_REMOVED_REASON_WARN;
            default -> throw new IllegalStateException("Unexpected baseType: " + type);
        };

        String existingRemovedReason = "";
        try (PreparedStatement ps = Database.get().prepareStatement(sqlGet)) {
            ps.setLong(1, pending.id());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) existingRemovedReason = safe(rs.getString("r"));
            }
        } catch (Exception ex) {
            plugin.getLogger().warning("[PunishmentHistoryMenu] Pardon read failed for ID " + pending.id() + ": " + ex.getMessage());
            return false;
        }

        String newRemovedReason = pending.reason() == null ? "" : pending.reason();
        String trail = keepReissueTrail(existingRemovedReason);

        String combined = safe(newRemovedReason).trim();
        if (!trail.isBlank()) {
            if (!combined.isBlank()) combined += "\n";
            combined += trail;
        }

        try (PreparedStatement ps = Database.get().prepareStatement(sqlUpdate)) {
            ps.setString(1, safe(pending.staffName()));
            ps.setString(2, combined);
            ps.setTimestamp(3, new Timestamp(System.currentTimeMillis()));
            ps.setLong(4, pending.id());

            return ps.executeUpdate() > 0;
        } catch (Exception ex) {
            plugin.getLogger().warning("[PunishmentHistoryMenu] Pardon failed for ID " + pending.id() + ": " + ex.getMessage());
            return false;
        }
    }

    private boolean applyReinstate(PendingAction pending) {
        String type = pending.baseType();
        if (type.isEmpty()) return false;

        String sqlGet = switch (type) {
            case "BAN" -> SQL_GET_REMOVED_REASON_BAN;
            case "MUTE" -> SQL_GET_REMOVED_REASON_MUTE;
            case "WARN" -> SQL_GET_REMOVED_REASON_WARN;
            default -> throw new IllegalStateException("Unexpected baseType: " + type);
        };

        String sqlUpdate = switch (type) {
            case "BAN" -> SQL_REINSTATE_BAN;
            case "MUTE" -> SQL_REINSTATE_MUTE;
            case "WARN" -> SQL_REINSTATE_WARN;
            default -> throw new IllegalStateException("Unexpected baseType: " + type);
        };

        long now = System.currentTimeMillis();

        if (pending.untilMillis() > 0L && pending.untilMillis() <= now) {
            return false;
        }

        long newUntilMillis = Math.max(pending.untilMillis(), 0L);

        long newUntilRaw = pending.untilWasSeconds()
                ? (newUntilMillis == 0L ? 0L : (newUntilMillis / 1000L))
                : newUntilMillis;

        String existingRemovedReason = "";
        try (PreparedStatement ps = Database.get().prepareStatement(sqlGet)) {
            ps.setLong(1, pending.id());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) existingRemovedReason = safe(rs.getString("r"));
            }
        } catch (Exception ex) {
            plugin.getLogger().warning("[PunishmentHistoryMenu] Reinstate read failed for ID " + pending.id() + ": " + ex.getMessage());
            return false;
        }

        String payload = now + ":" + encodeB64(pending.staffName()) + ":" + encodeB64(pending.reason());
        String appended = safe(existingRemovedReason);
        if (!appended.isBlank() && !appended.endsWith("\n")) appended += "\n";
        appended += REISSUE_MARKER + payload;

        try (PreparedStatement ps = Database.get().prepareStatement(sqlUpdate)) {
            ps.setLong(1, newUntilRaw);
            ps.setString(2, appended);
            ps.setLong(3, pending.id());
            return ps.executeUpdate() > 0;
        } catch (Exception ex) {
            plugin.getLogger().warning("[PunishmentHistoryMenu] Reinstate failed for ID " + pending.id() + ": " + ex.getMessage());
            return false;
        }
    }

    private static String normalizeActionBaseType(String rawType) {
        if (rawType == null) return "";
        String t = rawType.trim().toUpperCase(Locale.ROOT);

        if (t.contains("BAN")) return "BAN";
        if (t.contains("MUTE")) return "MUTE";
        if (t.equals("WARN")) return "WARN";
        return "";
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder() instanceof GuiManager.Holder(GuiManager.MenuType type, UUID holderUuid))) return;
        if (type != GuiManager.MenuType.HISTORY) return;

        if (gui.isPrompting(holderUuid)) return;

        pageByModerator.remove(holderUuid);
        filterByModerator.remove(holderUuid);
        filterSlotByModerator.remove(holderUuid);
        entryBySlotByModerator.remove(holderUuid);
        pendingByModerator.remove(holderUuid);
        viewStateByModerator.remove(holderUuid);

        if (pageByModerator.isEmpty()) {
            HandlerList.unregisterAll(this);
        }
    }

    private static Filter filterForSlot(EnumMap<Filter, Integer> map, int slot) {
        for (Map.Entry<Filter, Integer> e : map.entrySet()) {
            Integer v = e.getValue();
            if (v != null && v == slot) return e.getKey();
        }
        return null;
    }

    private static int clampSlot(int slot, int size) {
        if (size <= 0) return 0;
        if (slot < 0) return 0;
        if (slot >= size) return size - 1;
        return slot;
    }
}
