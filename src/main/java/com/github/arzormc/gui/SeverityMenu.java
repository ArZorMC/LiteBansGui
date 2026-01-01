/* =============================================================================
 * 🧩 SeverityMenu: Severity selection GUI (step 2)
 *
 * 📋 What it does
 * • Displays severity levels for the selected category.
 * • Enforces per-level permissions: litebansgui.category.<id>.level.<n>
 * • On select -> goes to reason prompt.
 * • Optional Back button (layout.yml).
 *
 * ✨ Feedback (messages.yml keys)
 * • menu.severity.title
 * • menu.severity.option.name
 * • menu.severity.option.lore.with-duration
 * • menu.severity.option.lore.no-duration
 * • menu.back.name / menu.back.lore
 * • session.cancelled
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

import org.bukkit.Bukkit;
import org.bukkit.Material;
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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

// ======================
// 🧩 Menu
// ======================

public final class SeverityMenu implements Listener {

    private static final String TITLE_KEY = "menu.severity.title";

    private static final String OPTION_NAME_KEY = "menu.severity.option.name";
    private static final String OPTION_LORE_WITH_DURATION_KEY = "menu.severity.option.lore.with-duration";
    private static final String OPTION_LORE_NO_DURATION_KEY = "menu.severity.option.lore.no-duration";

    private static final String BACK_NAME_KEY = "menu.back.name";
    private static final String BACK_LORE_KEY = "menu.back.lore";

    private static final String MSG_SESSION_CANCELLED = "session.cancelled";

    private final ConfigManager config;
    private final MessageService messages;
    private final ItemFactory items;
    private final PermissionService perms;
    private final SessionManager sessions;
    private final ReasonPromptService reasonPrompts;
    private final GuiManager gui;

    private final JavaPlugin plugin;

    private final Map<UUID, Map<Integer, ConfigModels.LevelSpec>> levelsByModerator = new HashMap<>();
    private final Map<UUID, Integer> backSlotByModerator = new HashMap<>();

    public SeverityMenu(
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

    public void open(Player moderator, PunishSession session) {
        Objects.requireNonNull(moderator, "moderator");
        Objects.requireNonNull(session, "session");

        UUID moderatorUuid = moderator.getUniqueId();

        ConfigManager.Snapshot snap = config.snapshot();
        ConfigModels.MenuDefinition menuDef = snap.layout().severityMenu();

        int size = menuDef.size();
        Inventory inv = Bukkit.createInventory(
                new GuiManager.Holder(GuiManager.MenuType.SEVERITY, moderatorUuid),
                size,
                messages.component(TITLE_KEY)
        );

        ItemStack filler = items.filler(menuDef);

        if (menuDef.fillEmptySlots()) {
            for (int i = 0; i < size; i++) {
                inv.setItem(i, filler);
            }
        }

        String categoryId = session.categoryId();
        ConfigModels.CategoryDef cat = snap.categories().get(categoryId);

        Map<Integer, ConfigModels.LevelSpec> levelBySlot = new HashMap<>();

        if (cat != null) {
            List<Integer> slots = snap.layout().severitySlots();
            ConfigModels.SeverityIcons icons = snap.layout().severityIcons();

            for (int i = 0; i < cat.levels().size(); i++) {
                if (i >= slots.size()) break;

                int slot = slots.get(i);
                if (slot < 0 || slot >= size) continue;

                ConfigModels.LevelSpec level = cat.levels().get(i);

                String matName = icons.materialFor(level);
                Material mat = Material.matchMaterial(matName);
                if (mat == null) mat = Material.PAPER;

                boolean allowed = perms.canUseCategoryLevel(moderator, categoryId, level.id());

                String duration = level.type().supportsDuration() ? level.duration() : "";

                Map<String, String> ph = PlaceholderUtil.merge(
                        PlaceholderUtil.forSession(session, moderatorUuid, moderator.getName()),
                        Map.of(
                                "category", categoryId == null ? "" : categoryId.toUpperCase(java.util.Locale.ROOT),
                                "severity", Integer.toString(level.id()),
                                "type", level.type().name(),
                                "duration", duration
                        )
                );

                String loreKey = level.type().supportsDuration()
                        ? OPTION_LORE_WITH_DURATION_KEY
                        : OPTION_LORE_NO_DURATION_KEY;

                ItemStack built = items.icon(mat, OPTION_NAME_KEY, loreKey, ph);

                if (!allowed) {
                    ConfigModels.DenyAppearance deny = snap.permissionUi().denyAppearance();

                    ItemStack deniedItem = switch (deny) {
                        case LOCKED -> items.locked(built, ph);
                        case REPLACE -> filler;
                        case HIDE -> menuDef.fillEmptySlots() ? filler : null;
                    };

                    inv.setItem(slot, deniedItem);
                    continue;
                }

                inv.setItem(slot, built);
                levelBySlot.put(slot, level);
            }
        }

        int backSlot = -1;
        if (snap.layout().severityBackEnabled()) {
            ConfigModels.LayoutIcon back = snap.layout().severityBack();
            if (back.slot() >= 0 && back.slot() < size) {
                backSlot = back.slot();
                inv.setItem(backSlot, items.icon(back.material(), BACK_NAME_KEY, BACK_LORE_KEY, Map.of()));
            }
        }

        levelsByModerator.put(moderatorUuid, levelBySlot);
        backSlotByModerator.put(moderatorUuid, backSlot);

        Bukkit.getPluginManager().registerEvents(this, plugin);
        moderator.openInventory(inv);
    }

    // ======================
    // 🧩 Events
    // ======================

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;

        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder() instanceof InventoryListeners.MenuHolder holder)) return;
        if (holder.type() != GuiManager.MenuType.SEVERITY) return;

        UUID moderatorUuid = holder.moderatorUuid();
        if (!player.getUniqueId().equals(moderatorUuid)) return;

        if (event.getClickedInventory() != top) {
            event.setCancelled(true);
            return;
        }

        event.setCancelled(true);

        int slot = event.getRawSlot();
        if (slot < 0 || slot >= top.getSize()) return;

        PunishSession session = sessions.get(player).orElse(null);
        if (session == null) return;

        int backSlot = backSlotByModerator.getOrDefault(moderatorUuid, -1);
        if (slot == backSlot) {
            player.closeInventory();
            gui.openCategory(player, session);
            return;
        }

        Map<Integer, ConfigModels.LevelSpec> levelBySlot = levelsByModerator.get(moderatorUuid);
        if (levelBySlot == null) return;

        ConfigModels.LevelSpec level = levelBySlot.get(slot);
        if (level == null) return;

        String categoryId = session.categoryId();
        if (categoryId == null || categoryId.isBlank()) return;

        if (!perms.canUseCategoryLevel(player, categoryId, level.id())) {
            perms.playDenyClick(player);
            return;
        }

        session.setLevel(level);
        beginReason(player, session);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder() instanceof InventoryListeners.MenuHolder holder)) return;
        if (holder.type() != GuiManager.MenuType.SEVERITY) return;

        UUID uuid = holder.moderatorUuid();
        levelsByModerator.remove(uuid);
        backSlotByModerator.remove(uuid);

        HandlerList.unregisterAll(this);
    }

    // ======================
    // 🧩 Internals
    // ======================

    private void beginReason(Player moderator, PunishSession session) {
        UUID uuid = moderator.getUniqueId();

        gui.markPrompting(uuid);
        moderator.closeInventory();

        reasonPrompts.begin(
                moderator,
                session,
                (p, s) -> {
                    gui.clearPrompting(uuid);

                    s.setLastMenu(GuiManager.MenuType.SEVERITY);

                    gui.openConfirm(p, s);
                },
                (p, s) -> {
                    gui.clearPrompting(uuid);

                    sessions.cancel(uuid);
                    if (p != null) {
                        p.sendMessage(messages.component(MSG_SESSION_CANCELLED));
                    }
                }
        );
    }
}
