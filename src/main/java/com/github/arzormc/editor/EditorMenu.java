/* =============================================================================
 * 🧩 EditorMenu: In-game layout editor inventory (category icon layout)
 *
 * 📋 What it does
 * • Builds an inventory matching the Category menu size (rows from layout.yml).
 * • Places each category icon at its configured slot/material.
 * • Fills remaining slots with filler (if enabled in layout.yml).
 *
 * 🧠 Editing rules are enforced by EditorManager.
 *
 * 🔧 Examples
 * • Inventory inv = editorMenu.build(playerUuid)
 *
 * ✨ Feedback (messages.yml keys)
 * • editor.menu.title
 * • item.filler.name
 * • item.filler.lore
 * • editor.category.name
 * • editor.category.lore
 * =============================================================================
 */
package com.github.arzormc.editor;

import com.github.arzormc.config.ConfigManager;
import com.github.arzormc.config.ConfigModels.LayoutIcon;
import com.github.arzormc.config.MessageService;
import com.github.arzormc.gui.ItemFactory;

import net.kyori.adventure.text.Component;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

// ======================
// 🧩 Builder
// ======================

public final class EditorMenu {

    public record Holder(UUID moderatorUuid) implements InventoryHolder {
        public Holder(UUID moderatorUuid) {
            this.moderatorUuid = Objects.requireNonNull(moderatorUuid, "moderatorUuid");
        }

        @Override
        public @NotNull Inventory getInventory() {
            return Bukkit.createInventory(this, 9);
        }
    }

    private static final String KEY_TITLE = "editor.menu.title";
    private static final String KEY_CAT_NAME = "editor.category.name";
    private static final String KEY_CAT_LORE = "editor.category.lore";

    private final ConfigManager config;
    private final MessageService messages;
    private final ItemFactory items;

    private final NamespacedKey pdcCategoryId;
    private final NamespacedKey pdcFiller;

    public EditorMenu(JavaPlugin plugin, ConfigManager config, MessageService messages, ItemFactory items) {
        Objects.requireNonNull(plugin, "plugin");
        this.config = Objects.requireNonNull(config, "config");
        this.messages = Objects.requireNonNull(messages, "messages");
        this.items = Objects.requireNonNull(items, "items");

        this.pdcCategoryId = new NamespacedKey(plugin, "lb_gui_editor_category");
        this.pdcFiller = new NamespacedKey(plugin, "lb_gui_editor_filler");
    }

    public Inventory build(UUID moderatorUuid) {
        Objects.requireNonNull(moderatorUuid, "moderatorUuid");

        int rows = config.snapshot().layout().categoryMenu().rows();
        int size = rows * 9;

        Component title = messages.component(KEY_TITLE);
        Inventory inv = Bukkit.createInventory(new Holder(moderatorUuid), size, title);

        if (config.snapshot().layout().categoryMenu().fillEmptySlots()) {
            ItemStack filler = items.filler(config.snapshot().layout().categoryMenu());
            markFiller(filler);

            for (int i = 0; i < size; i++) {
                inv.setItem(i, filler);
            }
        }

        for (Map.Entry<String, LayoutIcon> e : config.snapshot().layout().categoryIcons().entrySet()) {
            String categoryId = e.getKey();
            LayoutIcon icon = e.getValue();
            if (categoryId == null || categoryId.trim().isEmpty() || icon == null) continue;

            int slot = icon.slot();
            if (slot < 0 || slot >= size) continue;

            Material mat = parseMaterialOrPaper(icon.material());

            ItemStack item = new ItemStack(mat);
            applyCategoryText(item, categoryId);
            markCategory(item, categoryId);

            inv.setItem(slot, item);
        }

        return inv;
    }

    public NamespacedKey pdcCategoryId() {
        return pdcCategoryId;
    }

    public NamespacedKey pdcFiller() {
        return pdcFiller;
    }

    // ======================
    // 🧩 Internals
    // ======================

    private void applyCategoryText(ItemStack item, String categoryId) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;

        meta.displayName(messages.component(KEY_CAT_NAME, Map.of("category", categoryId)));
        meta.lore(messages.components(KEY_CAT_LORE, Map.of("category", categoryId)));

        item.setItemMeta(meta);
    }

    private void markCategory(ItemStack item, String categoryId) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;

        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.set(pdcCategoryId, PersistentDataType.STRING, categoryId);

        item.setItemMeta(meta);
    }

    private void markFiller(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;

        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.set(pdcFiller, PersistentDataType.BYTE, (byte) 1);

        item.setItemMeta(meta);
    }

    private static Material parseMaterialOrPaper(String raw) {
        if (raw == null) return Material.PAPER;
        String t = raw.trim();
        if (t.isEmpty()) return Material.PAPER;

        Material m = Material.matchMaterial(t);
        return m != null ? m : Material.PAPER;
    }
}
