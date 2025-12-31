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
 * 🔧 Examples
 * • gui.openHistory(moderator, session)
 *
 * ✨ Feedback (messages.yml keys)
 * • menu.history.title
 * • menu.history.empty
 * • menu.history.entry.name
 * • menu.history.entry.lore
 * • menu.history.entry.lore-removed
 * • menu.history.filters.*.name / lore
 * • menu.history.prev.name / lore
 * • menu.history.next.name / lore
 * • menu.denied.click
 * • menu.back.name
 * • menu.back.lore
 * =============================================================================
 */
package com.github.arzormc.gui;

import com.github.arzormc.config.ConfigManager;
import com.github.arzormc.config.ConfigModels;
import com.github.arzormc.config.MessageService;
import com.github.arzormc.config.PlaceholderUtil;
import com.github.arzormc.punish.PermissionService;
import com.github.arzormc.punish.PunishSession;
import com.github.arzormc.punish.SessionManager;

import litebans.api.Database;

import org.bukkit.Bukkit;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

// ======================
// 🧩 Menu
// ======================

public final class PunishmentHistoryMenu implements Listener {

    private static final String TITLE_KEY = "menu.history.title";
    private static final String EMPTY_KEY = "menu.history.empty";

    private static final String ENTRY_NAME_KEY = "menu.history.entry.name";
    private static final String ENTRY_LORE_KEY = "menu.history.entry.lore";
    private static final String ENTRY_LORE_REMOVED_KEY = "menu.history.entry.lore-removed";

    private static final String BACK_NAME_KEY = "menu.back.name";
    private static final String BACK_LORE_KEY = "menu.back.lore";

    private static final String PREV_NAME_KEY = "menu.history.prev.name";
    private static final String PREV_LORE_KEY = "menu.history.prev.lore";

    private static final String NEXT_NAME_KEY = "menu.history.next.name";
    private static final String NEXT_LORE_KEY = "menu.history.next.lore";

    private static final String DENIED_CLICK_KEY = "menu.denied.click";

    private static final int PAGE_SIZE = 28;
    private static final int FETCH_MULTIPLIER = 4;

    private static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
                    .withZone(ZoneId.systemDefault());

    // LiteBans H2 schema (common fields on bans/mutes/warnings):
    // UUID, REASON, BANNED_BY_NAME, TIME, UNTIL, ACTIVE, REMOVED_BY_NAME, REMOVED_BY_REASON, REMOVED_BY_DATE
    // KICKS doesn't have removal fields; we synthesize defaults.
    private static final String SQL_UNION = """
            SELECT u.type AS type,
                   u.reason AS reason,
                   u.staff AS staff,
                   u.time AS time,
                   u.until AS until,
                   u.active AS active,
                   u.removed_by_name AS removed_by_name,
                   u.removed_by_reason AS removed_by_reason,
                   u.removed_by_date AS removed_by_date
            FROM (
                SELECT 'BAN'  AS type,
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

                SELECT 'MUTE' AS type,
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

                SELECT 'WARN' AS type,
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

                SELECT 'KICK' AS type,
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

    private final ConfigManager config;
    private final MessageService messages;
    private final ItemFactory items;
    private final PermissionService perms;
    private final SessionManager sessions;
    private final GuiManager gui;

    private final JavaPlugin plugin;

    private final Map<UUID, Integer> pageByModerator = new HashMap<>();
    private final Map<UUID, Filter> filterByModerator = new HashMap<>();
    private final Map<UUID, EnumMap<Filter, Integer>> filterSlotByModerator = new HashMap<>();

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

    private record Entry(
            String type,
            String reason,
            String staff,
            long timeMillis,
            long untilMillis,
            boolean active,
            String removedByName,
            String removedByReason,
            long removedByDateMillis
    ) {
    }

    public PunishmentHistoryMenu(
            ConfigManager config,
            MessageService messages,
            ItemFactory items,
            PermissionService perms,
            SessionManager sessions,
            GuiManager gui
    ) {
        this.config = Objects.requireNonNull(config, "config");
        this.messages = Objects.requireNonNull(messages, "messages");
        this.items = Objects.requireNonNull(items, "items");
        this.perms = Objects.requireNonNull(perms, "perms");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
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

                if (rows.isEmpty() && page > 0) {
                    int newPage = page - 1;
                    pageByModerator.put(modUuid, newPage);

                    Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                        EnumMap<Filter, Integer> totals2 = fetchTotals(session.targetUuid());
                        List<Entry> rows2 = fetch(session.targetUuid(), filter, newPage);

                        Bukkit.getScheduler().runTask(plugin, () -> {
                            if (isNotViewingHistory(moderator, modUuid)) return;
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

                    long timeMillis = normalizeEpochMillis(rs.getLong("time"));

                    long untilMillis = 0L;
                    try {
                        untilMillis = normalizeEpochMillis(rs.getLong("until"));
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
                            type,
                            safe(rs.getString("reason")),
                            safe(rs.getString("staff")),
                            timeMillis,
                            untilMillis,
                            active,
                            removedByName,
                            removedByReason,
                            removedByDateMillis
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
        if (!(top.getHolder() instanceof GuiManager.Holder(GuiManager.MenuType type, UUID modUuid))) return true;

        return type != GuiManager.MenuType.HISTORY || !modUuid.equals(moderatorUuid);
    }

    // ======================
    // 🧩 Render
    // ======================

    private void render(Player moderator, PunishSession session, List<Entry> entries, EnumMap<Filter, Integer> totals) {
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

            boolean removed = isRemoved(e);
            boolean activeNow = isActiveNow(e, removed, now);

            String status;
            if (removed) status = "REVERTED";
            else if (activeNow) status = "ACTIVE";
            else status = "INACTIVE";

            ph.put("status", status);

            // Only meaningful when ACTIVE, but we can still provide a value (templates decide if they show it)
            String remaining;
            if (activeNow) {
                if (e.untilMillis() <= 0L) remaining = "Permanent";
                else remaining = formatCompactDuration(Math.max(0L, e.untilMillis() - now));
            } else {
                remaining = "N/A";
            }
            ph.put("remaining", remaining);

            // Removed metadata placeholders (only used by the removed template)
            ph.put("removed_by", safe(e.removedByName()).isBlank() ? "N/A" : e.removedByName());
            ph.put("removed_reason", safe(e.removedByReason()).isBlank() ? "N/A" : e.removedByReason());
            ph.put("removed_date", e.removedByDateMillis() <= 0L ? "N/A" : DATE_FMT.format(Instant.ofEpochMilli(e.removedByDateMillis())));

            String loreKey = removed ? ENTRY_LORE_REMOVED_KEY : ENTRY_LORE_KEY;

            String entryMat = entryMaterialForType(history, e.type());
            ItemStack item = items.icon(entryMat, ENTRY_NAME_KEY, loreKey, ph);

            int slot = contentSlots.get(idx++);
            if (slot < 0 || slot >= inv.getSize()) continue;
            inv.setItem(slot, item);
        }
    }

    private static boolean isRemoved(Entry e) {
        if (e == null) return false;
        if (e.removedByDateMillis() > 0L) return true;
        return !safe(e.removedByName()).isBlank();
    }

    private static boolean isActiveNow(Entry e, boolean removed, long nowMillis) {
        if (e == null) return false;
        if (removed) return false;
        if (!e.active()) return false;

        // If UNTIL is present and already passed, treat as not active.
        long until = e.untilMillis();
        return until <= 0L || until > nowMillis;
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
        if (!(top.getHolder() instanceof GuiManager.Holder(GuiManager.MenuType type, UUID ignored))) return;
        if (type != GuiManager.MenuType.HISTORY) return;

        if (event.getClickedInventory() != top) {
            event.setCancelled(true);
            return;
        }

        event.setCancelled(true);

        UUID uuid = player.getUniqueId();
        int slot = event.getRawSlot();

        ConfigModels.HistoryMenuLayout history = config.snapshot().layout().history();

        if (slot == history.backButton().slot()) {
            sessions.get(player).ifPresent(s -> gui.openCategory(player, s));
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
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder() instanceof GuiManager.Holder(GuiManager.MenuType type, UUID moderatorUuid))) return;
        if (type != GuiManager.MenuType.HISTORY) return;

        pageByModerator.remove(moderatorUuid);
        filterByModerator.remove(moderatorUuid);
        filterSlotByModerator.remove(moderatorUuid);

        HandlerList.unregisterAll(this);
    }

    private static Filter filterForSlot(EnumMap<Filter, Integer> map, int slot) {
        for (Map.Entry<Filter, Integer> e : map.entrySet()) {
            Integer v = e.getValue();
            if (v != null && v == slot) return e.getKey();
        }
        return null;
    }
}
