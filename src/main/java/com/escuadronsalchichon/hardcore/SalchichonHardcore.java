
package com.escuadronsalchichon.hardcore;

import com.escuadronsalchichon.hardcore.database.DatabaseManager;
import com.escuadronsalchichon.hardcore.listener.PlayerDeathListener;
import com.escuadronsalchichon.hardcore.listener.PlayerJoinListener;
import com.escuadronsalchichon.hardcore.manager.BossBarManager;
import com.escuadronsalchichon.hardcore.manager.DailyResetManager;
import com.escuadronsalchichon.hardcore.manager.PlayerManager;
import org.bukkit.plugin.java.JavaPlugin;
import com.escuadronsalchichon.hardcore.listener.WorldLoadListener;
import com.escuadronsalchichon.hardcore.manager.WorldRuleManager;
import org.bukkit.Bukkit;
import com.escuadronsalchichon.hardcore.listener.FoodListener;
import com.escuadronsalchichon.hardcore.command.HardcoreCommand;
import com.escuadronsalchichon.hardcore.expansion.HardcoreExpansion;

public class SalchichonHardcore extends JavaPlugin {

    private DatabaseManager databaseManager;
    private PlayerManager playerManager;
    private BossBarManager bossBarManager;
    private WorldRuleManager worldRuleManager;
    private DailyResetManager dailyResetManager;

    @Override
    public void onEnable() {

        saveDefaultConfig();
        reloadConfig();

        databaseManager = new DatabaseManager(this);

        if (!databaseManager.connect()) {
            getLogger().severe(
                    "El plugin no puede continuar sin la base de datos."
            );
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        if (!databaseManager.createTables()) {
            getLogger().severe(
                    "No se pudieron inicializar las tablas."
            );
            databaseManager.disconnect();
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        playerManager = new PlayerManager(this, databaseManager);
        if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {

            new HardcoreExpansion(playerManager).register();

            getLogger().info("Integración con PlaceholderAPI activada.");

        } else {
            getLogger().warning(
                    "PlaceholderAPI no está instalado. Los placeholders no estarán disponibles."
            );
        }

        bossBarManager = new BossBarManager();
        worldRuleManager = new WorldRuleManager();

        dailyResetManager = new DailyResetManager(
                this,
                playerManager,
                bossBarManager
        );

        dailyResetManager.start();

        var hardcoreCommand = getCommand("hardcore");

        if (hardcoreCommand == null) {
            getLogger().severe(
                    "No se pudo registrar el comando hardcore. Revisa plugin.yml."
            );

            databaseManager.disconnect();
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        hardcoreCommand.setExecutor(
                new HardcoreCommand(
                        this,
                        databaseManager,
                        bossBarManager,
                        playerManager
                )
        );

        for (var world : Bukkit.getWorlds()) {
            worldRuleManager.applyRules(world);
            world.setPVP(true);
        }

        getServer().getPluginManager().registerEvents(
                new WorldLoadListener(worldRuleManager),
                this
        );

        getServer().getPluginManager().registerEvents(
                new PlayerJoinListener(playerManager, bossBarManager),
                this
        );

        getServer().getPluginManager().registerEvents(
                new PlayerDeathListener(this, playerManager, bossBarManager),
                this
        );

        getServer().getPluginManager().registerEvents(
                new FoodListener(),
                this
        );

        getLogger().info(
                "SalchichonHardcore se ha iniciado correctamente."
        );
    }

    @Override
    public void onDisable() {

        if (bossBarManager != null) {
            bossBarManager.removeAll();
        }

        if (databaseManager != null) {
            databaseManager.disconnect();
        }

        getLogger().info(
                "SalchichonHardcore se ha detenido correctamente."
        );
    }

    public DatabaseManager getDatabaseManager() {
        return databaseManager;
    }

    public PlayerManager getPlayerManager() {
        return playerManager;
    }

    public BossBarManager getBossBarManager() {
        return bossBarManager;
    }
}
