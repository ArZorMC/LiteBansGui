/* =============================================================================
 * 🧩 MessageService: messages.yml loader + key resolver (Adventure/MiniMessage)
 *
 * 📋 What it does
 * • Loads messages.yml from the plugin data folder (separate from config.yml/layout.yml).
 * • Resolves message keys into:
 *     - raw strings
 *     - Components (MiniMessage for templates)
 *     - string lists / component lists (for lore)
 * • Applies placeholders safely:
 *     - placeholder values are treated as literal text (no MiniMessage injection)
 * • Supports a trusted placeholder pathway:
 *     - placeholder values are parsed as MiniMessage (ONLY for values we control)
 *
 * 🔧 Examples
 * • messages.raw("menu.category.title")
 * • messages.component("error.no-permission")
 * • messages.components("confirm.summary.lore", Map.of("target", targetName))
 * • messages.componentTrusted("menu.silent-toggle.name", Map.of("silent", messages.raw("format.true")))
 *
 * ✨ Feedback (messages.yml keys)
 * • messages.missing-key
 * =============================================================================
 */
package com.github.arzormc.config;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

// ======================
// 🧩 State
// ======================

public final class MessageService {

    private static final String MESSAGES_FILE = "messages.yml";
    private static final String KEY_MISSING = "messages.missing-key";

    private static final String HARD_MISSING_FORMAT = "<red>Missing message: <gray>{key}</gray></red>";

    private final JavaPlugin plugin;
    private final MiniMessage mini;

    private FileConfiguration messages;

    public MessageService(JavaPlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.mini = MiniMessage.miniMessage();
        this.messages = new YamlConfiguration();
    }

    // ======================
    // 🧩 Lifecycle
    // ======================

    public void reload() {
        this.messages = loadMessagesYaml();
    }

    // ======================
    // 🧩 Raw Access
    // ======================

    public String raw(String key) {
        Objects.requireNonNull(key, "key");
        String value = messages.getString(key);
        if (value != null) {
            return value;
        }

        String missingTemplate = messages.getString(KEY_MISSING);
        if (missingTemplate == null || missingTemplate.trim().isEmpty()) {
            missingTemplate = HARD_MISSING_FORMAT;
        }

        return mini.serialize(mini.deserialize(missingTemplate, Placeholder.unparsed("key", key)));
    }

    public List<String> rawList(String key) {
        Objects.requireNonNull(key, "key");

        List<String> list = messages.getStringList(key);
        if (!list.isEmpty()) {
            return list;
        }

        String single = messages.getString(key);
        if (single != null && !single.trim().isEmpty()) {
            return List.of(single);
        }

        return List.of(raw(key));
    }

    // ======================
    // 🧩 Key Existence
    // ======================

    public boolean exists(String key) {
        Objects.requireNonNull(key, "key");
        if (!messages.isString(key)) {
            return false;
        }

        String value = messages.getString(key);
        return value != null && !value.trim().isEmpty();
    }

    public boolean existsList(String key) {
        Objects.requireNonNull(key, "key");

        List<String> list = messages.getStringList(key);
        if (!list.isEmpty()) {
            for (String s : list) {
                if (s != null && !s.trim().isEmpty()) {
                    return true;
                }
            }
            return false;
        }

        return exists(key);
    }

    // ======================
    // 🧩 Component Access (safe placeholders)
    // ======================

    public Component component(String key) {
        return component(key, Map.of());
    }

    public Component component(String key, Map<String, String> placeholders) {
        String template = raw(key);
        return mini.deserialize(template, toResolvers(placeholders));
    }

    public List<Component> components(String key, Map<String, String> placeholders) {
        List<String> lines = rawList(key);
        TagResolver resolver = toResolvers(placeholders);

        List<Component> out = new ArrayList<>(lines.size());
        for (String line : lines) {
            out.add(mini.deserialize(line, resolver));
        }
        return Collections.unmodifiableList(out);
    }

    // ======================
    // 🧩 Component Access (trusted placeholders)
    // ======================

    public Component componentTrusted(String key, Map<String, String> placeholders) {
        String template = raw(key);
        return mini.deserialize(template, toResolversTrusted(placeholders));
    }

    public List<Component> componentsTrusted(String key, Map<String, String> placeholders) {
        List<String> lines = rawList(key);
        TagResolver resolver = toResolversTrusted(placeholders);

        List<Component> out = new ArrayList<>(lines.size());
        for (String line : lines) {
            out.add(mini.deserialize(line, resolver));
        }
        return Collections.unmodifiableList(out);
    }

    // ======================
    // 🧩 Internals
    // ======================

    private FileConfiguration loadMessagesYaml() {
        File dataFolder = plugin.getDataFolder();
        if (!dataFolder.exists()) {
            boolean created = dataFolder.mkdirs();
            if (!created && !dataFolder.exists()) {
                throw new IllegalStateException("Could not create plugin data folder: " + dataFolder.getAbsolutePath());
            }
        } else if (!dataFolder.isDirectory()) {
            throw new IllegalStateException("Plugin data folder path exists but is not a directory: " + dataFolder.getAbsolutePath());
        }

        File file = new File(dataFolder, MESSAGES_FILE);
        if (!file.exists()) {
            plugin.saveResource(MESSAGES_FILE, false);
        }

        return YamlConfiguration.loadConfiguration(file);
    }

    private TagResolver toResolvers(Map<String, String> placeholders) {
        if (placeholders == null || placeholders.isEmpty()) {
            return TagResolver.empty();
        }

        List<TagResolver> resolvers = new ArrayList<>(placeholders.size());
        for (Map.Entry<String, String> e : placeholders.entrySet()) {
            String k = e.getKey();
            if (k == null) continue;

            String key = k.trim();
            if (key.isEmpty()) continue;

            String v = e.getValue();
            if (v == null) v = "";

            resolvers.add(Placeholder.unparsed(key, v));
        }

        return resolvers.isEmpty() ? TagResolver.empty() : TagResolver.resolver(resolvers);
    }

    private TagResolver toResolversTrusted(Map<String, String> placeholders) {
        if (placeholders == null || placeholders.isEmpty()) {
            return TagResolver.empty();
        }

        List<TagResolver> resolvers = new ArrayList<>(placeholders.size());
        for (Map.Entry<String, String> e : placeholders.entrySet()) {
            String k = e.getKey();
            if (k == null) continue;

            String key = k.trim();
            if (key.isEmpty()) continue;

            String v = e.getValue();
            if (v == null) v = "";

            Component value = mini.deserialize(v);
            resolvers.add(Placeholder.component(key, value));
        }

        return resolvers.isEmpty() ? TagResolver.empty() : TagResolver.resolver(resolvers);
    }
}
