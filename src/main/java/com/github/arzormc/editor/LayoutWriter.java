/* =============================================================================
 * 🧩 LayoutWriter: Writes updated layout.yml from the in-game editor state
 *
 * 📋 What it does
 * • Persists category icon slot/material changes to layout.yml.
 * • MVP: writes ONLY "category-menu.categories"
 * • Leaves all other layout.yml sections intact.
 *
 * 🔧 Examples
 * • writer.saveCategoryIcons(categoryIcons)
 *
 * ✨ Feedback (messages.yml keys)
 * • (N/A — editor-only)
 * =============================================================================
 */
package com.github.arzormc.editor;

import com.github.arzormc.config.ConfigModels.LayoutIcon;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.Objects;

// ======================
// 🧩 Writer
// ======================

public final class LayoutWriter {

    private static final String LAYOUT_FILE = "layout.yml";
    private static final String CATEGORY_BASE = "category-menu.categories";

    private final JavaPlugin plugin;

    public LayoutWriter(JavaPlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    public void saveCategoryIcons(Map<String, LayoutIcon> icons) throws IOException {
        Objects.requireNonNull(icons, "icons");

        File file = ensureLayoutFile();
        FileConfiguration yml = YamlConfiguration.loadConfiguration(file);

        ConfigurationSection base = yml.getConfigurationSection(CATEGORY_BASE);
        if (base == null) {
            base = yml.createSection(CATEGORY_BASE);
        }

        for (String key : base.getKeys(false)) {
            yml.set(CATEGORY_BASE + "." + key, null);
        }

        for (Map.Entry<String, LayoutIcon> e : icons.entrySet()) {
            String id = e.getKey();
            LayoutIcon icon = e.getValue();
            if (id == null || id.trim().isEmpty() || icon == null) continue;

            String path = CATEGORY_BASE + "." + id.trim();
            yml.set(path + ".slot", icon.slot());
            yml.set(path + ".material", icon.material());
        }

        yml.save(file);
    }

    // ======================
    // 🧩 Internals
    // ======================

    private File ensureLayoutFile() {
        File dataFolder = plugin.getDataFolder();
        if (!dataFolder.exists()) {
            boolean created = dataFolder.mkdirs();
            if (!created && !dataFolder.exists()) {
                throw new IllegalStateException("Could not create plugin data folder: " + dataFolder.getAbsolutePath());
            }
        } else if (!dataFolder.isDirectory()) {
            throw new IllegalStateException("Plugin data folder path exists but is not a directory: " + dataFolder.getAbsolutePath());
        }

        File file = new File(dataFolder, LAYOUT_FILE);
        if (!file.exists()) {
            plugin.saveResource(LAYOUT_FILE, false);
        }

        return file;
    }
}
