/* =============================================================================
 * 🧩 ItemFactory: Builds GUI ItemStacks from layout + messages (MiniMessage)
 *
 * 📋 What it does
 * • Centralized ItemStack builder for all menus (filler, buttons, category icons, etc.).
 * • Uses MessageService keys for ALL display text (titles/lore).
 * • Supports:
 *     - Safe items: literal placeholders only (no MiniMessage injection)
 *     - Trusted items: placeholder values may contain MiniMessage (for internal-controlled values)
 *
 * 🔧 Examples
 * • items.filler(config.snapshot().layout().categoryMenu())
 * • items.icon("ARROW", "menu.back.name", "menu.back.lore", ph)              // safe
 * • items.iconTrusted("LEVER", "menu.silent-toggle.name", "menu.silent-toggle.lore", ph) // trusted
 *
 * ✨ Feedback (messages.yml keys)
 * • (N/A — item-only)
 * =============================================================================
 */
package com.github.arzormc.gui;

import com.github.arzormc.config.ConfigManager;
import com.github.arzormc.config.ConfigModels.MenuDefinition;
import com.github.arzormc.config.MessageService;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.List;
import java.util.Map;
import java.util.Objects;

// ======================
// 🧩 Factory
// ======================

public final class ItemFactory {

    private static final String KEY_FILLER_NAME = "item.filler.name";
    private static final String KEY_FILLER_LORE = "item.filler.lore";

    private static final String KEY_LOCKED_NAME = "item.locked.name";
    private static final String KEY_LOCKED_LORE = "item.locked.lore";

    private static final String KEY_TARGET_HEAD_NAME = "menu.target-head.name";
    private static final String KEY_TARGET_HEAD_LORE = "menu.target-head.lore";

    private static final PlainTextComponentSerializer PLAIN =
            PlainTextComponentSerializer.plainText();

    private final MessageService messages;

    public ItemFactory(ConfigManager config, MessageService messages) {
        Objects.requireNonNull(config, "config");
        this.messages = Objects.requireNonNull(messages, "messages");
    }

    // ======================
    // 🧩 Common items
    // ======================

    public ItemStack filler(MenuDefinition def) {
        Objects.requireNonNull(def, "def");
        Material mat = materialOr(def.fillerMaterial(), Material.GRAY_STAINED_GLASS_PANE);

        ItemStack item = new ItemStack(mat);
        applyText(item, KEY_FILLER_NAME, KEY_FILLER_LORE, Map.of(), false);
        hideFlags(item);
        return item;
    }

    public ItemStack icon(String materialName, String nameKey, String loreKey, Map<String, String> placeholders) {
        Material mat = materialOr(materialName, Material.BARRIER);
        ItemStack item = new ItemStack(mat);
        applyText(item, nameKey, loreKey, placeholders, false);
        hideFlags(item);
        return item;
    }

    public ItemStack icon(Material mat, String nameKey, String loreKey, Map<String, String> placeholders) {
        ItemStack item = new ItemStack(Objects.requireNonNull(mat, "mat"));
        applyText(item, nameKey, loreKey, placeholders, false);
        hideFlags(item);
        return item;
    }

    // ======================
    // 🧩 Trusted variants (parsed placeholder values)
    // ======================

    public ItemStack iconTrusted(String materialName, String nameKey, String loreKey, Map<String, String> placeholders) {
        Material mat = materialOr(materialName, Material.BARRIER);
        ItemStack item = new ItemStack(mat);
        applyText(item, nameKey, loreKey, placeholders, true);
        hideFlags(item);
        return item;
    }

    // ======================
    // 🧩 Target head (reusable widget)
    // ======================

    public ItemStack targetHead(OfflinePlayer target, Map<String, String> placeholders) {
        Objects.requireNonNull(placeholders, "placeholders");

        ItemStack item = new ItemStack(Material.PLAYER_HEAD);

        ItemMeta meta = item.getItemMeta();
        if (meta instanceof SkullMeta skull) {
            if (target != null) {
                skull.setOwningPlayer(target);
            }
            item.setItemMeta(skull);
        }

        applyText(item, KEY_TARGET_HEAD_NAME, KEY_TARGET_HEAD_LORE, placeholders, false);
        hideFlags(item);
        return item;
    }

    // ======================
    // 🧩 Permission UI helpers
    // ======================

    public ItemStack locked(ItemStack base, Map<String, String> placeholders) {
        if (base == null) {
            base = new ItemStack(Material.BARRIER);
        }

        ItemStack out = base.clone();

        out.addUnsafeEnchantment(Enchantment.UNBREAKING, 1);

        applyText(out, KEY_LOCKED_NAME, KEY_LOCKED_LORE, placeholders, false);
        hideFlags(out);
        return out;
    }

    // ======================
    // 🧩 Internals
    // ======================

    private void applyText(
            ItemStack item,
            String nameKey,
            String loreKey,
            Map<String, String> placeholders,
            boolean trusted
    ) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;

        Map<String, String> ph = (placeholders == null) ? Map.of() : placeholders;

        if (nameKey != null && !nameKey.isBlank()) {
            Component name = trusted
                    ? messages.componentTrusted(nameKey, ph)
                    : messages.component(nameKey, ph);

            if (isBlankComponent(name)) {
                meta.displayName(null);
            } else {
                meta.displayName(name);
            }
        }

        if (loreKey != null && !loreKey.isBlank()) {
            List<Component> lore = trusted
                    ? messages.componentsTrusted(loreKey, ph)
                    : messages.components(loreKey, ph);

            if (isBlankLore(lore)) {
                meta.lore(null);
            } else {
                meta.lore(lore);
            }
        }

        item.setItemMeta(meta);
    }

    private void hideFlags(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;

        meta.addItemFlags(
                ItemFlag.HIDE_ATTRIBUTES,
                ItemFlag.HIDE_ENCHANTS,
                ItemFlag.HIDE_DESTROYS,
                ItemFlag.HIDE_PLACED_ON,
                ItemFlag.HIDE_UNBREAKABLE
        );

        boolean blankName = isBlankComponent(meta.displayName());
        boolean blankLore = isBlankLore(meta.lore());

        if (blankName && blankLore) {
            meta.setHideTooltip(true);
        }

        addHideAdditionalTooltipFlag(meta);

        item.setItemMeta(meta);
    }

    private static boolean isBlankLore(List<Component> lore) {
        if (lore == null || lore.isEmpty()) return true;

        for (Component c : lore) {
            if (!isBlankComponent(c)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isBlankComponent(Component c) {
        if (c == null) return true;
        String plain = PLAIN.serialize(c);
        return plain.trim().isEmpty();
    }

    private static void addHideAdditionalTooltipFlag(ItemMeta meta) {
        if (meta == null) return;

        try {
            meta.addItemFlags(ItemFlag.valueOf("HIDE_ADDITIONAL_TOOLTIP"));
        } catch (IllegalArgumentException ignored) {
        }
    }

    private static Material materialOr(String raw, Material fallback) {
        if (raw == null) return fallback;
        String t = raw.trim();
        if (t.isEmpty()) return fallback;

        Material m = Material.matchMaterial(t);
        return m != null ? m : fallback;
    }
}
