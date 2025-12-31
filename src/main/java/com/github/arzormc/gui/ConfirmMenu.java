/* =============================================================================
 * 🧩 ConfirmMenu: Review + confirm GUI (step 3)
 *
 * 📋 What it does
 * • Final screen:
 *     - Confirm (required)
 *     - Cancel (required)
 *     - Back (optional)
 *     - Silent toggle (optional)
 *     - Summary item (optional, hover-only)
 * • Dispatches the final punishment via LiteBansDispatcher.
 *
 * ✨ Feedback (messages.yml keys)
 * • menu.confirm.title
 * • menu.confirm-button.name / lore
 * • menu.cancel-button.name / lore
 * • menu.silent-toggle.name / lore
 * • menu.summary.name / lore
 * • menu.back.name / lore
 * • session.cancelled
 * • punish.success
 * =============================================================================
 */
package com.github.arzormc.gui;

import com.github.arzormc.config.ConfigManager;
import com.github.arzormc.config.ConfigModels;
import com.github.arzormc.config.MessageService;
import com.github.arzormc.config.PlaceholderUtil;
import com.github.arzormc.punish.LiteBansDispatcher;
import com.github.arzormc.punish.PermissionService;
import com.github.arzormc.punish.PunishSession;
import com.github.arzormc.punish.ReasonPromptService;
import com.github.arzormc.punish.SessionManager;

import org.bukkit.Bukkit;
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

public final class ConfirmMenu implements Listener {

    private static final String TITLE_KEY = "menu.confirm.title";

    private static final String CONFIRM_NAME_KEY = "menu.confirm-button.name";
    private static final String CONFIRM_LORE_KEY = "menu.confirm-button.lore";

    private static final String CANCEL_NAME_KEY = "menu.cancel-button.name";
    private static final String CANCEL_LORE_KEY = "menu.cancel-button.lore";

    private static final String BACK_NAME_KEY = "menu.back.name";
    private static final String BACK_LORE_KEY = "menu.back.lore";

    private static final String SILENT_NAME_KEY = "menu.silent-toggle.name";
    private static final String SILENT_LORE_KEY = "menu.silent-toggle.lore";

    private static final String SUMMARY_NAME_KEY = "menu.summary.name";
    private static final String SUMMARY_LORE_KEY = "menu.summary.lore";

    private static final String FORMAT_TRUE_KEY = "format.true";
    private static final String FORMAT_FALSE_KEY = "format.false";

    private static final String MSG_SESSION_CANCELLED = "session.cancelled";
    private static final String MSG_PUNISH_SUCCESS = "punish.success";

    private final ConfigManager config;
    private final MessageService messages;
    private final ItemFactory items;
    private final SessionManager sessions;
    private final GuiManager gui;

    private final LiteBansDispatcher dispatcher;
    private final JavaPlugin plugin;

    private final Map<UUID, Slots> slotsByModerator = new HashMap<>();

    public ConfirmMenu(
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
        Objects.requireNonNull(perms, "perms");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        Objects.requireNonNull(reasonPrompts, "reasonPrompts");
        this.gui = Objects.requireNonNull(gui, "gui");

        this.dispatcher = new LiteBansDispatcher(messages);
        this.plugin = JavaPlugin.getProvidingPlugin(getClass());
    }

    public void open(Player moderator, PunishSession session) {
        Objects.requireNonNull(moderator, "moderator");
        Objects.requireNonNull(session, "session");

        UUID moderatorUuid = moderator.getUniqueId();

        ConfigManager.Snapshot snap = config.snapshot();
        ConfigModels.MenuDefinition menuDef = snap.layout().confirmMenu();

        int size = menuDef.size();
        Inventory inv = Bukkit.createInventory(
                new GuiManager.Holder(GuiManager.MenuType.CONFIRM, moderatorUuid),
                size,
                messages.component(TITLE_KEY)
        );

        if (menuDef.fillEmptySlots()) {
            ItemStack filler = items.filler(menuDef);
            for (int i = 0; i < size; i++) {
                inv.setItem(i, filler);
            }
        }

        Map<String, String> ph = PlaceholderUtil.forSession(session, moderatorUuid, moderator.getName());

        ConfigModels.LayoutIcon confirm = snap.layout().confirmConfirm();
        ConfigModels.LayoutIcon cancel = snap.layout().confirmCancel();

        int confirmSlot = clampSlot(confirm.slot(), size);
        int cancelSlot = clampSlot(cancel.slot(), size);

        inv.setItem(confirmSlot, items.icon(confirm.material(), CONFIRM_NAME_KEY, CONFIRM_LORE_KEY, ph));
        inv.setItem(cancelSlot, items.icon(cancel.material(), CANCEL_NAME_KEY, CANCEL_LORE_KEY, ph));

        int backSlot = -1;
        if (snap.layout().confirmBackEnabled()) {
            ConfigModels.LayoutIcon back = snap.layout().confirmBack();
            backSlot = clampSlot(back.slot(), size);
            inv.setItem(backSlot, items.icon(back.material(), BACK_NAME_KEY, BACK_LORE_KEY, ph));
        }

        int silentSlot = -1;
        if (snap.layout().confirmSilentToggleEnabled()) {
            ConfigModels.LayoutIcon silent = snap.layout().confirmSilentToggle();
            silentSlot = clampSlot(silent.slot(), size);

            String silentValueKey = session.silent() ? FORMAT_TRUE_KEY : FORMAT_FALSE_KEY;
            String silentValue = messages.raw(silentValueKey);

            inv.setItem(
                    silentSlot,
                    items.iconTrusted(
                            silent.material(),
                            SILENT_NAME_KEY,
                            SILENT_LORE_KEY,
                            PlaceholderUtil.merge(ph, Map.of("silent", silentValue))
                    )
            );
        }

        int summarySlot = -1;
        if (snap.layout().confirmSummaryEnabled()) {
            ConfigModels.LayoutIcon summary = snap.layout().confirmSummary();
            summarySlot = clampSlot(summary.slot(), size);
            inv.setItem(summarySlot, items.icon(summary.material(), SUMMARY_NAME_KEY, SUMMARY_LORE_KEY, ph));
        }

        slotsByModerator.put(moderatorUuid, new Slots(confirmSlot, cancelSlot, backSlot, silentSlot, summarySlot));

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
        if (holder.type() != GuiManager.MenuType.CONFIRM) return;

        UUID moderatorUuid = holder.moderatorUuid();
        if (!player.getUniqueId().equals(moderatorUuid)) return;

        if (event.getClickedInventory() != top) {
            event.setCancelled(true);
            return;
        }

        event.setCancelled(true);

        int slot = event.getRawSlot();
        if (slot < 0 || slot >= top.getSize()) return;

        Slots slots = slotsByModerator.get(moderatorUuid);
        if (slots == null) return;

        PunishSession session = sessions.get(player).orElse(null);
        if (session == null) return;

        if (slot == slots.cancelSlot()) {
            sessions.cancel(player.getUniqueId());
            player.closeInventory();
            player.sendMessage(messages.component(MSG_SESSION_CANCELLED));
            return;
        }

        if (slot == slots.backSlot()) {
            player.closeInventory();
            gui.openSeverity(player, session);
            return;
        }

        if (slot == slots.silentSlot()) {
            session.setSilent(!session.silent());

            ConfigManager.Snapshot snap = config.snapshot();
            if (snap.layout().confirmSilentToggleEnabled()) {
                ConfigModels.LayoutIcon silent = snap.layout().confirmSilentToggle();

                Map<String, String> base = PlaceholderUtil.forSession(session, moderatorUuid, player.getName());
                String silentValueKey = session.silent() ? FORMAT_TRUE_KEY : FORMAT_FALSE_KEY;

                Map<String, String> ph = PlaceholderUtil.merge(
                        base,
                        Map.of("silent", messages.raw(silentValueKey))
                );

                ItemStack silentItem = items.iconTrusted(
                        silent.material(),
                        SILENT_NAME_KEY,
                        SILENT_LORE_KEY,
                        ph
                );

                top.setItem(slots.silentSlot(), silentItem);
            }

            return;
        }

        if (slot == slots.confirmSlot()) {
            boolean ok = dispatcher.dispatch(player, session);
            if (ok) {
                sessions.complete(player.getUniqueId());

                player.closeInventory();
                player.sendMessage(messages.component(
                        MSG_PUNISH_SUCCESS,
                        PlaceholderUtil.forSession(session, session.targetUuid(), player.getName())
                ));
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder() instanceof InventoryListeners.MenuHolder holder)) return;
        if (holder.type() != GuiManager.MenuType.CONFIRM) return;

        slotsByModerator.remove(holder.moderatorUuid());
        HandlerList.unregisterAll(this);
    }


    // ======================
    // 🧩 Internals
    // ======================

    private static int clampSlot(int slot, int size) {
        if (size <= 0) return 0;
        if (slot < 0) return 0;
        if (slot >= size) return size - 1;
        return slot;
    }

    private record Slots(int confirmSlot, int cancelSlot, int backSlot, int silentSlot, int summarySlot) {
    }
}
