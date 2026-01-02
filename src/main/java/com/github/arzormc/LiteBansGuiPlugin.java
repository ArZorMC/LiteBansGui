/* =============================================================================
 * 🧩 LiteBansGuiPlugin: Plugin bootstrap + service wiring
 *
 * 📋 What it does
 * • Loads config.yml, layout.yml, messages.yml
 * • Wires services (sessions, prompts, permissions, gui, editor, dispatcher)
 * • Registers listeners + commands
 *
 * ✨ Feedback (messages.yml keys)
 * • (N/A — bootstrap)
 * =============================================================================
 */
package com.github.arzormc;

import com.github.arzormc.command.PunishCommand;
import com.github.arzormc.command.PunishEditorCommand;
import com.github.arzormc.command.PunishReloadCommand;
import com.github.arzormc.config.ConfigManager;
import com.github.arzormc.config.MessageService;
import com.github.arzormc.editor.EditorManager;
import com.github.arzormc.editor.LayoutWriter;
import com.github.arzormc.gui.GuiManager;
import com.github.arzormc.gui.InventoryListeners;
import com.github.arzormc.gui.ItemFactory;
import com.github.arzormc.punish.PermissionService;
import com.github.arzormc.punish.ReasonPromptService;
import com.github.arzormc.punish.SessionManager;

import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Map;

// ======================
// 🧩 Plugin
// ======================

public final class LiteBansGuiPlugin extends JavaPlugin {

    private static final String MSG_COMMAND_MISSING_IN_PLUGIN_YML = "command.missing-in-plugin-yml";

    private SessionManager sessions;
    private ReasonPromptService reasonPrompts;

    private MessageService messages;

    @Override
    public void onEnable() {
        // ----- Load configs -----
        ConfigManager config = new ConfigManager(this);
        config.reload();

        this.messages = new MessageService(this);
        this.messages.reload();

        // ----- LiteBans presence check -----
        if (getServer().getPluginManager().getPlugin("LiteBans") == null) {
            getLogger().warning("LiteBans not detected. Punishment history will be unavailable.");
        }

        // ----- Core services -----
        PermissionService perms = new PermissionService(config);
        this.sessions = new SessionManager(config, messages);
        this.reasonPrompts = new ReasonPromptService(this, config, messages);

        // ----- GUI -----
        ItemFactory items = new ItemFactory(config, messages);
        GuiManager gui = new GuiManager(config, messages, items, perms, sessions, reasonPrompts);

        // ----- Editor -----
        LayoutWriter layoutWriter = new LayoutWriter(this);
        EditorManager editor = new EditorManager(this, config, messages, items, layoutWriter);

        // ----- Listeners -----
        getServer().getPluginManager().registerEvents(reasonPrompts, this);
        getServer().getPluginManager().registerEvents(
                new InventoryListeners(config, messages, gui, sessions, reasonPrompts),
                this
        );
        getServer().getPluginManager().registerEvents(editor, this);

        // ----- Commands -----
        registerCommand("punish", new PunishCommand(config, messages, perms, sessions, gui));
        registerCommand("punishreload", new PunishReloadCommand(config, messages, perms, gui, sessions, reasonPrompts));
        registerCommand("punisheditor", new PunishEditorCommand(messages, perms, editor));

        getLogger().info("LiteBansGUI enabled.");
    }

    @Override
    public void onDisable() {
        if (reasonPrompts != null) {
            reasonPrompts.cancelAll();
        }
        if (sessions != null) {
            sessions.cancelAll();
        }

        getLogger().info("LiteBansGUI disabled.");
    }

    // ======================
    // 🧩 Internals
    // ======================

    private void registerCommand(String name, Object executor) {
        PluginCommand cmd = getCommand(name);
        if (cmd == null) {
            if (messages != null) {
                String line = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                        .serialize(messages.component(MSG_COMMAND_MISSING_IN_PLUGIN_YML, Map.of("command", name)));

                getLogger().warning(line);
            } else {
                getLogger().warning("Command '" + name + "' is not defined in plugin.yml (skipping registration).");
            }
            return;
        }

        if (executor instanceof org.bukkit.command.CommandExecutor ce) {
            cmd.setExecutor(ce);
        }

        if (executor instanceof org.bukkit.command.TabCompleter tc) {
            cmd.setTabCompleter(tc);
        }
    }
}
