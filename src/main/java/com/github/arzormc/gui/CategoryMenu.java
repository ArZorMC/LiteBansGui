/* =============================================================================
 * 🧩 CategoryMenu: Category selection GUI (step 1)
 *
 * 📋 What it does
 * • Renders configured categories from config.yml + layout.yml.
 * • Enforces per-category permissions via PermissionService.
 * • Provides access to punishment history for the current target.
 * • Provides a Silent toggle button (layout.yml -> category-menu.buttons.silent-toggle),
 *   enabled by (layout.yml -> menus.category.buttons.silent-toggle).
 * • On select:
 *     - if category has 1 level -> skip severity and go to reason prompt
 *     - else -> open severity menu
 *
 * 🔧 Examples
 * • new CategoryMenu(...).open(player, session)
 *
 * ✨ Feedback (messages.yml keys)
 * • menu.category.title
 * • menu.category.lore (fallback)
 * • category.<id>.lore (preferred)
 * • menu.category.history-button.*
 * • menu.silent-toggle.*
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
import org.bukkit.OfflinePlayer;
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
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

// ======================
// 🧩 Menu
// ======================

public final class CategoryMenu implements Listener {

    private static final String TITLE_KEY = "menu.category.title";
    private static final String CATEGORY_LORE_FALLBACK_KEY = "menu.category.lore";
    private static final String MSG_SESSION_CANCELLED = "session.cancelled";

    private static final String HISTORY_NAME_KEY = "menu.history-button.name";
    private static final String HISTORY_LORE_KEY = "menu.history-button.lore";

    private static final String SILENT_NAME_KEY = "menu.silent-toggle.name";
    private static final String SILENT_LORE_KEY = "menu.silent-toggle.lore";

    private static final String FORMAT_TRUE_KEY = "format.true";
    private static final String FORMAT_FALSE_KEY = "format.false";

    private static final int TARGET_HEAD_SLOT = 4;

    private final ConfigManager config;
    private final MessageService messages;
    private final ItemFactory items;
    private final PermissionService perms;
    private final SessionManager sessions;
    private final ReasonPromptService reasonPrompts;
    private final GuiManager gui;

    private final JavaPlugin plugin;

    private final Map<UUID, Map<Integer, String>> categoryBySlot = new HashMap<>();
    private final Map<UUID, Integer> historySlotByModerator = new HashMap<>();
    private final Map<UUID, Integer> silentSlotByModerator = new HashMap<>();

    public CategoryMenu(
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

        UUID moderatorUuid = moderator.getUniqueId();

        ConfigManager.Snapshot snap = config.snapshot();
        ConfigModels.MenuDefinition menuDef = snap.layout().categoryMenu();

        int size = menuDef.size();
        Inventory inv = Bukkit.createInventory(
                new GuiManager.Holder(GuiManager.MenuType.CATEGORY, moderatorUuid),
                size,
                messages.component(TITLE_KEY)
        );

        ItemStack filler = items.filler(menuDef);

        if (menuDef.fillEmptySlots()) {
            for (int i = 0; i < size; i++) {
                inv.setItem(i, filler);
            }
        }

        if (TARGET_HEAD_SLOT >= 0 && TARGET_HEAD_SLOT < size) {
            OfflinePlayer target = Bukkit.getOfflinePlayer(session.targetUuid());
            boolean online = Bukkit.getPlayer(session.targetUuid()) != null;

            Map<String, String> ph = PlaceholderUtil.forSession(session, moderatorUuid, moderator.getName(), online);
            inv.setItem(TARGET_HEAD_SLOT, items.targetHead(target, ph));
        }

        Map<Integer, String> bySlot = new HashMap<>();

        for (Map.Entry<String, ConfigModels.LayoutIcon> e : snap.layout().categoryIcons().entrySet()) {
            String categoryId = e.getKey();
            ConfigModels.LayoutIcon icon = e.getValue();

            ConfigModels.CategoryDef cat = snap.categories().get(categoryId);
            if (cat == null) continue;

            int slot = icon.slot();
            if (slot < 0 || slot >= size) continue;
            if (slot == TARGET_HEAD_SLOT) continue;

            boolean allowed = perms.canUseCategory(moderator, categoryId);

            Map<String, String> ph = PlaceholderUtil.merge(
                    PlaceholderUtil.forSession(session, moderatorUuid, moderator.getName()),
                    Map.of("category", categoryId)
            );

            String nameKey = "category." + categoryId + ".name";
            String perCategoryLoreKey = "category." + categoryId + ".lore";
            String loreKey = messages.existsList(perCategoryLoreKey) ? perCategoryLoreKey : CATEGORY_LORE_FALLBACK_KEY;

            ItemStack built = items.icon(icon.material(), nameKey, loreKey, ph);

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
            bySlot.put(slot, categoryId);
        }

        if (snap.layout().categorySilentToggleEnabled()) {
            ConfigModels.LayoutIcon silentIcon = snap.layout().categorySilentToggle();
            int slot = silentIcon.slot();

            if (slot >= 0 && slot < size && slot != TARGET_HEAD_SLOT) {
                Map<String, String> base = PlaceholderUtil.forSession(session, moderatorUuid, moderator.getName());

                String silentValueKey = session.silent() ? FORMAT_TRUE_KEY : FORMAT_FALSE_KEY;
                Map<String, String> ph = PlaceholderUtil.merge(
                        base,
                        Map.of("silent", messages.raw(silentValueKey))
                );

                ItemStack silentItem = items.iconTrusted(
                        silentIcon.material(),
                        SILENT_NAME_KEY,
                        SILENT_LORE_KEY,
                        ph
                );

                inv.setItem(slot, silentItem);
                silentSlotByModerator.put(moderatorUuid, slot);
            }
        }

        if (perms.canViewHistory(moderator)) {
            ConfigModels.LayoutIcon historyIcon = snap.layout().categoryHistory();

            int slot = historyIcon.slot();
            if (slot >= 0 && slot < size && slot != TARGET_HEAD_SLOT) {
                ItemStack historyItem = items.icon(
                        historyIcon.material(),
                        HISTORY_NAME_KEY,
                        HISTORY_LORE_KEY,
                        PlaceholderUtil.forSession(session, moderatorUuid, moderator.getName())
                );
                inv.setItem(slot, historyItem);
                historySlotByModerator.put(moderatorUuid, slot);
            }
        }

        categoryBySlot.put(moderatorUuid, bySlot);

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
        if (holder.type() != GuiManager.MenuType.CATEGORY) return;

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

        Integer silentSlot = silentSlotByModerator.get(moderatorUuid);
        if (silentSlot != null && slot == silentSlot) {
            session.setSilent(!session.silent());

            ConfigModels.LayoutIcon silentIcon = config.snapshot().layout().categorySilentToggle();

            Map<String, String> base = PlaceholderUtil.forSession(session, moderatorUuid, player.getName());
            String silentValueKey = session.silent() ? FORMAT_TRUE_KEY : FORMAT_FALSE_KEY;

            Map<String, String> ph = PlaceholderUtil.merge(
                    base,
                    Map.of("silent", messages.raw(silentValueKey))
            );

            ItemStack silentItem = items.iconTrusted(
                    silentIcon.material(),
                    SILENT_NAME_KEY,
                    SILENT_LORE_KEY,
                    ph
            );

            top.setItem(silentSlot, silentItem);
            return;
        }

        Integer historySlot = historySlotByModerator.get(moderatorUuid);
        if (historySlot != null && slot == historySlot) {
            session.setLastMenu(GuiManager.MenuType.CATEGORY);

            player.closeInventory();
            gui.openHistory(player, session);
            return;
        }

        Map<Integer, String> bySlot = categoryBySlot.get(moderatorUuid);
        if (bySlot == null) return;

        String categoryId = bySlot.get(slot);
        if (categoryId == null) return;

        ConfigModels.CategoryDef cat = config.snapshot().categories().get(categoryId);
        if (cat == null) return;

        if (!perms.canUseCategory(player, categoryId)) {
            perms.playDenyClick(player);
            return;
        }

        session.setCategoryId(categoryId);

        if (cat.hasSingleLevel()) {
            session.setLevel(cat.levels().getFirst());
            beginReason(player, session);
            return;
        }

        session.setLastMenu(GuiManager.MenuType.CATEGORY);

        player.closeInventory();
        gui.openSeverity(player, session);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder() instanceof InventoryListeners.MenuHolder holder)) return;
        if (holder.type() != GuiManager.MenuType.CATEGORY) return;

        UUID uuid = holder.moderatorUuid();

        categoryBySlot.remove(uuid);
        historySlotByModerator.remove(uuid);
        silentSlotByModerator.remove(uuid);

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

                    s.setLastMenu(GuiManager.MenuType.CATEGORY);

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
