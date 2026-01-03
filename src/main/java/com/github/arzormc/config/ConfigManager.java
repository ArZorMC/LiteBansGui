/* =============================================================================
 * 🧩 ConfigManager: Centralized config.yml + layout.yml snapshot & accessors
 *
 * 📋 What it does
 * • Loads config.yml and layout.yml from the plugin data folder.
 * • Exposes typed snapshots (behavior, reason-input, permissions, categories, loadouts, menu layout).
 * • Normalizes and clamps raw values (rows, timeouts, sound volume/pitch, etc.).
 * • Provides semantic getters used by sessions, UI, and dispatching.
 *
 * 🔧 Examples
 * • config.snapshot().behavior().onInventoryClose()
 * • config.snapshot().categories().get("griefing")
 * • config.snapshot().loadouts().get("mod")
 * • config.snapshot().layout().categoryMenu().rows()
 *
 * ✨ Feedback (messages.yml keys)
 * • (N/A — config-only)
 * =============================================================================
 */
package com.github.arzormc.config;

import com.github.arzormc.config.ConfigModels.BehaviorSettings;
import com.github.arzormc.config.ConfigModels.CategoryDef;
import com.github.arzormc.config.ConfigModels.DenyAppearance;
import com.github.arzormc.config.ConfigModels.DenyClickSound;
import com.github.arzormc.config.ConfigModels.HistoryFilterDef;
import com.github.arzormc.config.ConfigModels.HistoryMenuLayout;
import com.github.arzormc.config.ConfigModels.LayoutIcon;
import com.github.arzormc.config.ConfigModels.LoadoutDef;
import com.github.arzormc.config.ConfigModels.MenuDefinition;
import com.github.arzormc.config.ConfigModels.OnInventoryCloseAction;
import com.github.arzormc.config.ConfigModels.PermissionUiSettings;
import com.github.arzormc.config.ConfigModels.Rarity;
import com.github.arzormc.config.ConfigModels.ReasonInputSettings;
import com.github.arzormc.config.ConfigModels.SeverityIcons;
import com.github.arzormc.config.ConfigModels.TempPermIcon;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

// ======================
// 🧩 State
// ======================

public final class ConfigManager {

    private static final String CONFIG_FILE = "config.yml";
    private static final String LAYOUT_FILE = "layout.yml";

    private final JavaPlugin plugin;
    private Snapshot snapshot;

    public ConfigManager(JavaPlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.snapshot = Snapshot.empty();
    }

    // ======================
    // 🧩 Lifecycle
    // ======================

    public void reload() {
        ensureDefaults();

        plugin.reloadConfig();
        FileConfiguration configYml = plugin.getConfig();
        FileConfiguration layoutYml = loadLayoutYaml();

        this.snapshot = Snapshot.load(configYml, layoutYml);
    }

    public Snapshot snapshot() {
        return snapshot;
    }

    private void ensureDefaults() {
        File dataFolder = plugin.getDataFolder();
        if (!dataFolder.exists()) {
            boolean created = dataFolder.mkdirs();
            if (!created && !dataFolder.exists()) {
                throw new IllegalStateException("Could not create plugin data folder: " + dataFolder.getAbsolutePath());
            }
        } else if (!dataFolder.isDirectory()) {
            throw new IllegalStateException("Plugin data folder path exists but is not a directory: " + dataFolder.getAbsolutePath());
        }

        File cfg = new File(dataFolder, CONFIG_FILE);
        if (!cfg.exists()) {
            plugin.saveDefaultConfig();
        }

        File layout = new File(dataFolder, LAYOUT_FILE);
        if (!layout.exists()) {
            plugin.saveResource(LAYOUT_FILE, false);
        }
    }

    // ======================
    // 🧩 YAML Loader (local-only; no extra classes)
    // ======================

    private FileConfiguration loadLayoutYaml() {
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

        return YamlConfiguration.loadConfiguration(file);
    }

    // ======================
    // 🧩 Snapshot
    // ======================

    public static final class Snapshot {

        private final BehaviorSettings behavior;
        private final ReasonInputSettings reasonInput;
        private final PermissionUiSettings permissionUi;

        private final Map<String, CategoryDef> categories;
        private final Map<String, LoadoutDef> loadouts;

        private final Layout layout;

        private Snapshot(
                BehaviorSettings behavior,
                ReasonInputSettings reasonInput,
                PermissionUiSettings permissionUi,
                Map<String, CategoryDef> categories,
                Map<String, LoadoutDef> loadouts,
                Layout layout
        ) {
            this.behavior = Objects.requireNonNull(behavior, "behavior");
            this.reasonInput = Objects.requireNonNull(reasonInput, "reasonInput");
            this.permissionUi = Objects.requireNonNull(permissionUi, "permissionUi");
            this.categories = Collections.unmodifiableMap(new LinkedHashMap<>(categories));
            this.loadouts = Collections.unmodifiableMap(new LinkedHashMap<>(loadouts));
            this.layout = Objects.requireNonNull(layout, "layout");
        }

        public static Snapshot empty() {
            BehaviorSettings behavior = new BehaviorSettings(true, true, OnInventoryCloseAction.CANCEL, true);
            ReasonInputSettings reason = new ReasonInputSettings(60, List.of("cancel"), true, List.of("none", "no reason"));
            PermissionUiSettings perm = new PermissionUiSettings(
                    DenyAppearance.LOCKED,
                    new DenyClickSound(true, "BLOCK_NOTE_BLOCK_BASS", 0.8f, 0.8f)
            );
            return new Snapshot(behavior, reason, perm, Map.of(), Map.of(), Layout.empty());
        }

        public static Snapshot load(FileConfiguration cfg, FileConfiguration layout) {
            Objects.requireNonNull(cfg, "cfg");
            Objects.requireNonNull(layout, "layout");

            BehaviorSettings behavior = loadBehavior(cfg);
            ReasonInputSettings reasonInput = loadReasonInput(cfg);
            PermissionUiSettings permissions = loadPermissions(cfg);

            Map<String, CategoryDef> categories = loadCategories(cfg);
            Map<String, LoadoutDef> loadouts = loadLoadouts(cfg);

            Layout layoutSnap = Layout.load(layout);

            return new Snapshot(behavior, reasonInput, permissions, categories, loadouts, layoutSnap);
        }

        public BehaviorSettings behavior() { return behavior; }
        public ReasonInputSettings reasonInput() { return reasonInput; }
        public PermissionUiSettings permissionUi() { return permissionUi; }

        public Map<String, CategoryDef> categories() { return categories; }
        public Map<String, LoadoutDef> loadouts() { return loadouts; }

        public Layout layout() { return layout; }
    }

    // ======================
    // 🧩 Layout Snapshot
    // ======================

    public static final class Layout {

        private final MenuDefinition categoryMenu;
        private final MenuDefinition severityMenu;
        private final MenuDefinition confirmMenu;
        private final MenuDefinition historyMenu;

        private final boolean categorySilentToggleEnabled;

        private final boolean severityBackEnabled;
        private final boolean confirmBackEnabled;
        private final boolean confirmSilentToggleEnabled;
        private final boolean confirmSummaryEnabled;

        private final Map<String, LayoutIcon> categoryIcons;

        private final LayoutIcon categorySilentToggle;
        private final LayoutIcon categoryHistory;

        private final List<Integer> severitySlots;
        private final SeverityIcons severityIcons;
        private final LayoutIcon severityBack;

        private final LayoutIcon confirmConfirm;
        private final LayoutIcon confirmCancel;
        private final LayoutIcon confirmBack;
        private final LayoutIcon confirmSilentToggle;
        private final LayoutIcon confirmSummary;

        private final HistoryMenuLayout history;

        private Layout(
                MenuDefinition categoryMenu,
                MenuDefinition severityMenu,
                MenuDefinition confirmMenu,
                MenuDefinition historyMenu,
                boolean categorySilentToggleEnabled,
                boolean severityBackEnabled,
                boolean confirmBackEnabled,
                boolean confirmSilentToggleEnabled,
                boolean confirmSummaryEnabled,
                Map<String, LayoutIcon> categoryIcons,
                LayoutIcon categorySilentToggle,
                LayoutIcon categoryHistory,
                List<Integer> severitySlots,
                SeverityIcons severityIcons,
                LayoutIcon severityBack,
                LayoutIcon confirmConfirm,
                LayoutIcon confirmCancel,
                LayoutIcon confirmBack,
                LayoutIcon confirmSilentToggle,
                LayoutIcon confirmSummary,
                HistoryMenuLayout history
        ) {
            this.categoryMenu = Objects.requireNonNull(categoryMenu, "categoryMenu");
            this.severityMenu = Objects.requireNonNull(severityMenu, "severityMenu");
            this.confirmMenu = Objects.requireNonNull(confirmMenu, "confirmMenu");
            this.historyMenu = Objects.requireNonNull(historyMenu, "historyMenu");

            this.categorySilentToggleEnabled = categorySilentToggleEnabled;

            this.severityBackEnabled = severityBackEnabled;
            this.confirmBackEnabled = confirmBackEnabled;
            this.confirmSilentToggleEnabled = confirmSilentToggleEnabled;
            this.confirmSummaryEnabled = confirmSummaryEnabled;

            this.categoryIcons = Collections.unmodifiableMap(new LinkedHashMap<>(categoryIcons));

            this.categorySilentToggle = Objects.requireNonNull(categorySilentToggle, "categorySilentToggle");
            this.categoryHistory = Objects.requireNonNull(categoryHistory, "categoryHistory");

            this.severitySlots = List.copyOf(severitySlots);
            this.severityIcons = Objects.requireNonNull(severityIcons, "severityIcons");
            this.severityBack = Objects.requireNonNull(severityBack, "severityBack");

            this.confirmConfirm = Objects.requireNonNull(confirmConfirm, "confirmConfirm");
            this.confirmCancel = Objects.requireNonNull(confirmCancel, "confirmCancel");
            this.confirmBack = Objects.requireNonNull(confirmBack, "confirmBack");
            this.confirmSilentToggle = Objects.requireNonNull(confirmSilentToggle, "confirmSilentToggle");
            this.confirmSummary = Objects.requireNonNull(confirmSummary, "confirmSummary");

            this.history = Objects.requireNonNull(history, "history");
        }

        public static Layout empty() {
            MenuDefinition cat = new MenuDefinition(6, true, "GRAY_STAINED_GLASS_PANE");
            MenuDefinition sev = new MenuDefinition(1, true, "GRAY_STAINED_GLASS_PANE");
            MenuDefinition con = new MenuDefinition(1, true, "GRAY_STAINED_GLASS_PANE");
            MenuDefinition his = new MenuDefinition(6, true, "GRAY_STAINED_GLASS_PANE");

            HistoryMenuLayout history = new HistoryMenuLayout(
                    defaultHistoryContentSlots(),
                    "PAPER",
                    "BARRIER",
                    new HistoryFilterDef(0, "BOOK"),
                    new HistoryFilterDef(1, "BOOK"),
                    new HistoryFilterDef(2, "BOOK"),
                    new HistoryFilterDef(3, "BOOK"),
                    new HistoryFilterDef(4, "BOOK"),
                    new LayoutIcon(45, "ARROW"),
                    new LayoutIcon(53, "ARROW"),
                    new LayoutIcon(49, "ARROW")
            );

            SeverityIcons severityIcons = new SeverityIcons(
                    "PAPER",
                    "LEATHER_BOOTS",
                    new TempPermIcon("IRON_SWORD", "NETHERITE_SWORD"),
                    new TempPermIcon("CLOCK", "OAK_SIGN")
            );

            return new Layout(
                    cat, sev, con, his,
                    true,
                    true, true, true, true,
                    Map.of(),
                    new LayoutIcon(2, "LEVER"),
                    new LayoutIcon(5, "BOOK"),
                    List.of(3, 4, 5, 6, 8),
                    severityIcons,
                    new LayoutIcon(0, "ARROW"),
                    new LayoutIcon(6, "LIME_CONCRETE"),
                    new LayoutIcon(8, "RED_CONCRETE"),
                    new LayoutIcon(0, "ARROW"),
                    new LayoutIcon(2, "LEVER"),
                    new LayoutIcon(3, "LECTERN"),
                    history
            );
        }

        public static Layout load(FileConfiguration yml) {
            MenuDefinition categoryMenu = loadMenuDefinition(yml, "menus.category", 6);
            MenuDefinition severityMenu = loadMenuDefinition(yml, "menus.severity", 1);
            MenuDefinition confirmMenu = loadMenuDefinition(yml, "menus.confirm", 1);
            MenuDefinition historyMenu = loadMenuDefinition(yml, "menus.history", 6);

            boolean categorySilentEnabled = yml.getBoolean("menus.category.buttons.silent-toggle", true);

            boolean severityBackEnabled = yml.getBoolean("menus.severity.buttons.back", true);

            boolean confirmBackEnabled = yml.getBoolean("menus.confirm.buttons.back", true);
            boolean confirmSilentEnabled = yml.getBoolean("menus.confirm.buttons.silent-toggle", true);
            boolean confirmSummaryEnabled = yml.getBoolean("menus.confirm.buttons.summary", true);

            Map<String, LayoutIcon> categoryIcons = loadCategoryIcons(yml);

            LayoutIcon categorySilentToggle = loadIcon(
                    yml,
                    "category-menu.buttons.silent-toggle",
                    new LayoutIcon(2, "LEVER")
            );

            LayoutIcon categoryHistory = loadIcon(
                    yml,
                    "category-menu.buttons.history",
                    new LayoutIcon(5, "BOOK")
            );

            List<Integer> severitySlots = yml.getIntegerList("severity-menu.severity-slots");
            if (severitySlots.isEmpty()) severitySlots = List.of(3, 4, 5, 6, 8);

            SeverityIcons severityIcons = loadSeverityIcons(yml);

            LayoutIcon severityBack = loadIcon(yml, "severity-menu.buttons.back", new LayoutIcon(0, "ARROW"));

            LayoutIcon confirmConfirm = loadIcon(yml, "confirm-menu.buttons.confirm", new LayoutIcon(6, "LIME_CONCRETE"));
            LayoutIcon confirmCancel = loadIcon(yml, "confirm-menu.buttons.cancel", new LayoutIcon(8, "RED_CONCRETE"));
            LayoutIcon confirmBack = loadIcon(yml, "confirm-menu.buttons.back", new LayoutIcon(0, "ARROW"));
            LayoutIcon confirmSilent = loadIcon(yml, "confirm-menu.buttons.silent-toggle", new LayoutIcon(2, "LEVER"));
            LayoutIcon confirmSummary = loadIcon(yml, "confirm-menu.buttons.summary", new LayoutIcon(3, "LECTERN"));

            HistoryMenuLayout history = loadHistoryLayout(yml);

            return new Layout(
                    categoryMenu,
                    severityMenu,
                    confirmMenu,
                    historyMenu,
                    categorySilentEnabled,
                    severityBackEnabled,
                    confirmBackEnabled,
                    confirmSilentEnabled,
                    confirmSummaryEnabled,
                    categoryIcons,
                    categorySilentToggle,
                    categoryHistory,
                    severitySlots,
                    severityIcons,
                    severityBack,
                    confirmConfirm,
                    confirmCancel,
                    confirmBack,
                    confirmSilent,
                    confirmSummary,
                    history
            );
        }

        public MenuDefinition categoryMenu() { return categoryMenu; }
        public MenuDefinition severityMenu() { return severityMenu; }
        public MenuDefinition confirmMenu() { return confirmMenu; }
        public MenuDefinition historyMenu() { return historyMenu; }

        public boolean categorySilentToggleEnabled() { return categorySilentToggleEnabled; }

        public boolean severityBackEnabled() { return severityBackEnabled; }
        public boolean confirmBackEnabled() { return confirmBackEnabled; }
        public boolean confirmSilentToggleEnabled() { return confirmSilentToggleEnabled; }
        public boolean confirmSummaryEnabled() { return confirmSummaryEnabled; }

        public Map<String, LayoutIcon> categoryIcons() { return categoryIcons; }

        public LayoutIcon categorySilentToggle() { return categorySilentToggle; }
        public LayoutIcon categoryHistory() { return categoryHistory; }

        public List<Integer> severitySlots() { return severitySlots; }
        public SeverityIcons severityIcons() { return severityIcons; }
        public LayoutIcon severityBack() { return severityBack; }

        public LayoutIcon confirmConfirm() { return confirmConfirm; }
        public LayoutIcon confirmCancel() { return confirmCancel; }
        public LayoutIcon confirmBack() { return confirmBack; }
        public LayoutIcon confirmSilentToggle() { return confirmSilentToggle; }
        public LayoutIcon confirmSummary() { return confirmSummary; }

        public HistoryMenuLayout history() { return history; }

        private static SeverityIcons loadSeverityIcons(FileConfiguration yml) {
            String warn = yml.getString("severity-menu.severity-icons.warn.material", "PAPER");
            String kick = yml.getString("severity-menu.severity-icons.kick.material", "LEATHER_BOOTS");

            String banTemp = yml.getString("severity-menu.severity-icons.ban.temp.material", "IRON_SWORD");
            String banPerm = yml.getString("severity-menu.severity-icons.ban.perm.material", "NETHERITE_SWORD");

            String muteTemp = yml.getString("severity-menu.severity-icons.mute.temp.material", "CLOCK");
            String mutePerm = yml.getString("severity-menu.severity-icons.mute.perm.material", "OAK_SIGN");

            return new SeverityIcons(
                    warn,
                    kick,
                    new TempPermIcon(banTemp, banPerm),
                    new TempPermIcon(muteTemp, mutePerm)
            );
        }

        private static HistoryMenuLayout loadHistoryLayout(FileConfiguration yml) {
            List<Integer> contentSlots = yml.getIntegerList("history-menu.content-slots");
            if (contentSlots.isEmpty()) contentSlots = defaultHistoryContentSlots();

            String entryMaterial = yml.getString("history-menu.entry.material", "PAPER");
            String emptyMaterial = yml.getString("history-menu.empty.material", "BARRIER");

            HistoryFilterDef all = loadHistoryFilter(yml, "history-menu.filters.all", new HistoryFilterDef(0, "BOOK"));
            HistoryFilterDef bans = loadHistoryFilter(yml, "history-menu.filters.bans", new HistoryFilterDef(1, "BOOK"));
            HistoryFilterDef mutes = loadHistoryFilter(yml, "history-menu.filters.mutes", new HistoryFilterDef(2, "BOOK"));
            HistoryFilterDef warns = loadHistoryFilter(yml, "history-menu.filters.warns", new HistoryFilterDef(3, "BOOK"));
            HistoryFilterDef kicks = loadHistoryFilter(yml, "history-menu.filters.kicks", new HistoryFilterDef(4, "BOOK"));

            LayoutIcon prev = loadIcon(yml, "history-menu.buttons.prev", new LayoutIcon(45, "ARROW"));
            LayoutIcon back = loadIcon(yml, "history-menu.buttons.back", new LayoutIcon(49, "ARROW"));
            LayoutIcon next = loadIcon(yml, "history-menu.buttons.next", new LayoutIcon(53, "ARROW"));

            return new HistoryMenuLayout(
                    contentSlots,
                    entryMaterial,
                    emptyMaterial,
                    all, bans, mutes, warns, kicks,
                    prev, next, back
            );
        }

        private static HistoryFilterDef loadHistoryFilter(FileConfiguration yml, String basePath, HistoryFilterDef fallback) {
            ConfigurationSection sec = yml.getConfigurationSection(basePath);
            if (sec == null) return fallback;

            int slot = sec.getInt("slot", fallback.slot());
            String material = sec.getString("material", fallback.material());
            return new HistoryFilterDef(slot, material);
        }

        private static List<Integer> defaultHistoryContentSlots() {
            List<Integer> out = new ArrayList<>(28);
            int[] rows = new int[]{9, 18, 27, 36};
            for (int base : rows) {
                for (int x = 0; x <= 6; x++) out.add(base + x);
            }
            return List.copyOf(out);
        }
    }

    // ======================
    // 🧩 Loaders: config.yml
    // ======================

    private static BehaviorSettings loadBehavior(FileConfiguration cfg) {
        boolean cancelOnReload = cfg.getBoolean("behavior.cancel-sessions-on-reload", true);
        boolean allowReplace = cfg.getBoolean("behavior.allow-session-replace", true);
        OnInventoryCloseAction closeAction =
                OnInventoryCloseAction.parse(cfg.getString("behavior.on-inventory-close"), OnInventoryCloseAction.CANCEL);
        boolean silentDefault = cfg.getBoolean("behavior.silent-default", true);

        return new BehaviorSettings(cancelOnReload, allowReplace, closeAction, silentDefault);
    }

    private static ReasonInputSettings loadReasonInput(FileConfiguration cfg) {
        int timeout = cfg.getInt("reason-input.timeout-seconds", 60);

        List<String> cancelWords = cfg.getStringList("reason-input.cancel-words");
        if (cancelWords.isEmpty()) cancelWords = List.of("cancel");

        boolean allowNone = cfg.getBoolean("reason-input.allow-none-word", true);

        List<String> noneWords = cfg.getStringList("reason-input.none-words");
        if (noneWords.isEmpty()) noneWords = List.of("none", "no reason");

        return new ReasonInputSettings(timeout, cancelWords, allowNone, noneWords);
    }

    private static PermissionUiSettings loadPermissions(FileConfiguration cfg) {
        DenyAppearance denyAppearance =
                DenyAppearance.parse(cfg.getString("permissions.deny-appearance"), DenyAppearance.LOCKED);

        boolean enabled = cfg.getBoolean("permissions.deny-click-sound.enabled", true);
        String sound = cfg.getString("permissions.deny-click-sound.sound", "BLOCK_NOTE_BLOCK_BASS");
        float volume = (float) cfg.getDouble("permissions.deny-click-sound.volume", 0.8);
        float pitch = (float) cfg.getDouble("permissions.deny-click-sound.pitch", 0.8);

        DenyClickSound denyClickSound = new DenyClickSound(enabled, sound, volume, pitch);
        return new PermissionUiSettings(denyAppearance, denyClickSound);
    }

    private static Map<String, CategoryDef> loadCategories(FileConfiguration cfg) {
        ConfigurationSection section = cfg.getConfigurationSection("categories");
        if (section == null) return Map.of();

        Map<String, CategoryDef> out = new LinkedHashMap<>();

        for (String id : section.getKeys(false)) {
            ConfigurationSection cat = section.getConfigurationSection(id);
            if (cat == null) continue;

            Rarity rarity = Rarity.parse(cat.getString("rarity"), Rarity.UNCATEGORIZED);

            List<String> levelStrings = cat.getStringList("levels");
            List<ConfigModels.LevelSpec> levels = new ArrayList<>();

            for (String s : levelStrings) {
                if (s == null || s.trim().isEmpty()) continue;
                levels.add(ConfigModels.LevelSpec.parse(s));
            }

            out.put(id, new CategoryDef(id, rarity, levels));
        }

        return out;
    }

    private static Map<String, LoadoutDef> loadLoadouts(FileConfiguration cfg) {
        ConfigurationSection section = cfg.getConfigurationSection("loadouts");
        if (section == null) return Map.of();

        Map<String, LoadoutDef> out = new LinkedHashMap<>();

        for (String id : section.getKeys(false)) {
            ConfigurationSection node = section.getConfigurationSection(id);
            if (node == null) continue;

            String normId = id.trim().toLowerCase(Locale.ROOT);
            if (normId.isEmpty()) continue;

            List<String> includes = node.getStringList("includes");
            List<String> permissions = node.getStringList("permissions");

            out.put(normId, new LoadoutDef(includes, permissions));
        }

        return out;
    }

    // ======================
    // 🧩 Loaders: layout.yml
    // ======================

    private static MenuDefinition loadMenuDefinition(FileConfiguration yml, String basePath, int defaultRows) {
        int rows = yml.getInt(basePath + ".rows", defaultRows);
        boolean fill = yml.getBoolean(basePath + ".fill-empty-slots", true);
        String fillerMat = yml.getString(basePath + ".filler-item.material", "GRAY_STAINED_GLASS_PANE");
        return new MenuDefinition(rows, fill, fillerMat);
    }

    private static Map<String, LayoutIcon> loadCategoryIcons(FileConfiguration yml) {
        ConfigurationSection section = yml.getConfigurationSection("category-menu.categories");
        if (section == null) {
            return Map.of();
        }

        Map<String, LayoutIcon> out = new LinkedHashMap<>();
        for (String id : section.getKeys(false)) {
            ConfigurationSection node = section.getConfigurationSection(id);
            if (node == null) continue;

            int slot = node.getInt("slot", -1);
            String material = node.getString("material", "");
            if (slot < 0 || material.trim().isEmpty()) continue;

            out.put(id, new LayoutIcon(slot, material));
        }

        return out;
    }

    private static LayoutIcon loadIcon(FileConfiguration yml, String path, LayoutIcon fallback) {
        ConfigurationSection sec = yml.getConfigurationSection(path);
        if (sec == null) {
            return fallback;
        }

        int slot = sec.getInt("slot", fallback.slot());
        String mat = sec.getString("material", fallback.material());
        return new LayoutIcon(slot, mat);
    }
}
