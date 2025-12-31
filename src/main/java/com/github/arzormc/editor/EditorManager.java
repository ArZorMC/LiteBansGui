/* =============================================================================
 * 🧩 EditorManager: Editor controller + click/drag enforcement + saving
 *
 * 📋 What it does
 * • Opens the EditorMenu.
 * • Prevents taking items out / duplicating via shift-click, hotbar swaps, etc.
 * • Allows:
 *     - moving category icons within the editor inventory
 *     - painting category icon material by clicking with an item on cursor
 * • Saves updated slots/materials back to layout.yml (category-menu.categories.*)
 *
 * 🔧 Examples
 * • editor.open(player)
 * • editor.save(player)
 *
 * ✨ Feedback (messages.yml keys)
 * • editor.opened
 * • editor.not-open
 * • editor.saved
 * • editor.save-error
 * =============================================================================
 */
package com.github.arzormc.editor;

import com.github.arzormc.config.ConfigManager;
import com.github.arzormc.config.ConfigModels.LayoutIcon;
import com.github.arzormc.config.MessageService;
import com.github.arzormc.gui.ItemFactory;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

// ======================
// 🧩 Manager
// ======================

public final class EditorManager implements Listener {

    private static final String MSG_OPENED = "editor.opened";
    private static final String MSG_NOT_OPEN = "editor.not-open";
    private static final String MSG_SAVED = "editor.saved";
    private static final String MSG_SAVE_ERROR = "editor.save-error";

    private final JavaPlugin plugin;
    private final ConfigManager config;
    private final MessageService messages;

    private final EditorMenu menu;
    private final LayoutWriter writer;

    public EditorManager(JavaPlugin plugin, ConfigManager config, MessageService messages, ItemFactory items, LayoutWriter writer) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.config = Objects.requireNonNull(config, "config");
        this.messages = Objects.requireNonNull(messages, "messages");
        this.writer = Objects.requireNonNull(writer, "writer");
        this.menu = new EditorMenu(plugin, config, messages, Objects.requireNonNull(items, "items"));
    }

    // ======================
    // 🧩 Public API
    // ======================

    public void open(Player moderator) {
        Objects.requireNonNull(moderator, "moderator");
        Inventory inv = menu.build(moderator.getUniqueId());
        moderator.openInventory(inv);
        moderator.sendMessage(messages.component(MSG_OPENED));
    }

    public void save(Player moderator) {
        Objects.requireNonNull(moderator, "moderator");

        Inventory top = moderator.getOpenInventory().getTopInventory();
        if (isNotEditorTop(top, moderator.getUniqueId())) {
            moderator.sendMessage(messages.component(MSG_NOT_OPEN));
            return;
        }

        Map<String, LayoutIcon> out = new LinkedHashMap<>();
        int size = top.getSize();

        for (int slot = 0; slot < size; slot++) {
            ItemStack it = top.getItem(slot);
            String categoryId = readCategoryId(it);
            if (categoryId == null) continue;

            Material mat = it.getType();
            out.put(categoryId, new LayoutIcon(slot, mat.name()));
        }

        try {
            writer.saveCategoryIcons(out);

            config.reload();

            moderator.sendMessage(messages.component(MSG_SAVED));
        } catch (IOException ex) {
            plugin.getLogger().severe("Failed to save layout.yml: " + ex.getMessage());
            moderator.sendMessage(messages.component(MSG_SAVE_ERROR));
        }
    }

    // ======================
    // 🧩 Event enforcement
    // ======================

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;

        Inventory top = event.getView().getTopInventory();
        if (isNotEditorTop(top, player.getUniqueId())) return;

        if (event.getClickedInventory() != top) {
            event.setCancelled(true);
            return;
        }

        InventoryAction action = event.getAction();
        if (action == InventoryAction.MOVE_TO_OTHER_INVENTORY
                || action == InventoryAction.HOTBAR_SWAP
                || action == InventoryAction.COLLECT_TO_CURSOR
                || isHotbarMoveAndReaddLike(action)) {
            event.setCancelled(true);
            return;
        }

        ItemStack current = event.getCurrentItem();
        ItemStack cursor = event.getCursor();

        if (isFiller(current)) {
            event.setCancelled(true);
            return;
        }

        String categoryId = readCategoryId(current);
        if (categoryId == null) {
            if (cursor.getType() != Material.AIR) {
                event.setCancelled(true);
            }
            return;
        }

        if (cursor.getType() != Material.AIR) {
            event.setCancelled(true);

            Material newMat = cursor.getType();
            ItemStack painted = new ItemStack(newMat);

            copyCategoryTag(painted, categoryId);
            applyCategoryText(painted, categoryId);

            top.setItem(event.getSlot(), painted);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;

        Inventory top = event.getView().getTopInventory();
        if (isNotEditorTop(top, player.getUniqueId())) return;

        int topSize = top.getSize();

        for (int raw : event.getRawSlots()) {
            if (raw >= topSize) {
                event.setCancelled(true);
                return;
            }
        }

        ItemStack oldCursor = event.getOldCursor();
        if (isFiller(oldCursor)) {
            event.setCancelled(true);
        }
    }

    // ======================
    // 🧩 Internals
    // ======================

    private boolean isNotEditorTop(Inventory top, UUID moderatorUuid) {
        if (top == null) return true;
        if (!(top.getHolder() instanceof EditorMenu.Holder(UUID uuid))) return true;
        return !uuid.equals(moderatorUuid);
    }

    private boolean isFiller(ItemStack item) {
        if (item == null || item.getType() == Material.AIR) return false;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return false;

        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        return pdc.has(menu.pdcFiller(), PersistentDataType.BYTE);
    }

    private String readCategoryId(ItemStack item) {
        if (item == null || item.getType() == Material.AIR) return null;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return null;

        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        return pdc.get(menu.pdcCategoryId(), PersistentDataType.STRING);
    }

    private void copyCategoryTag(ItemStack item, String categoryId) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;

        meta.getPersistentDataContainer().set(menu.pdcCategoryId(), PersistentDataType.STRING, categoryId);
        item.setItemMeta(meta);
    }

    private void applyCategoryText(ItemStack item, String categoryId) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;

        meta.displayName(messages.component("editor.category.name", Map.of("category", categoryId)));
        meta.lore(messages.components("editor.category.lore", Map.of("category", categoryId)));
        item.setItemMeta(meta);
    }

    private static boolean isHotbarMoveAndReaddLike(InventoryAction action) {
        if (action == null) return false;
        return action.name().equals("HOTBAR_MOVE_AND_READD");
    }
}
