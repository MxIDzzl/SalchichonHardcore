package com.escuadronsalchichon.hardcore;

import com.escuadronsalchichon.hardcore.command.HardcoreCommand;
import com.escuadronsalchichon.hardcore.database.DatabaseManager;
import com.escuadronsalchichon.hardcore.database.PlayerDataStore;
import com.escuadronsalchichon.hardcore.database.PostgresDatabaseManager;
import com.escuadronsalchichon.hardcore.expansion.HardcoreExpansion;
import com.escuadronsalchichon.hardcore.listener.FoodListener;
import com.escuadronsalchichon.hardcore.listener.PlayerDeathListener;
import com.escuadronsalchichon.hardcore.listener.PlayerJoinListener;
import com.escuadronsalchichon.hardcore.listener.TotemListener;
import com.escuadronsalchichon.hardcore.listener.WorldLoadListener;
import com.escuadronsalchichon.hardcore.manager.BossBarManager;
import com.escuadronsalchichon.hardcore.manager.DailyResetManager;
import com.escuadronsalchichon.hardcore.manager.PlayerManager;
import com.escuadronsalchichon.hardcore.manager.PlaytimeTracker;
import com.escuadronsalchichon.hardcore.manager.WorldRuleManager;
import com.escuadronsalchichon.hardcore.vault.VaultCommand;
import com.escuadronsalchichon.hardcore.vault.VaultMenuListener;
import com.escuadronsalchichon.hardcore.vault.VaultWithdrawalService;
import com.escuadronsalchichon.hardcore.trade.TradeCommand;
import com.escuadronsalchichon.hardcore.trade.TradeService;
import com.escuadronsalchichon.hardcore.vault.VaultInventoryService;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

public class SalchichonHardcore extends JavaPlugin {

    private PlayerDataStore databaseManager;
    private PlayerManager playerManager;
    private BossBarManager bossBarManager;
    private WorldRuleManager worldRuleManager;
    private DailyResetManager dailyResetManager;
    private PlaytimeTracker playtimeTracker;
    private VaultInventoryService vaultInventoryService;
    private VaultWithdrawalService vaultWithdrawalService;
    private TradeService tradeService;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        reloadConfig();

        databaseManager = getConfig().getBoolean("database.postgresql.enabled", false)
                ? new PostgresDatabaseManager(this)
                : new DatabaseManager(this);

        if (!databaseManager.connect()) {
            getLogger().severe("El plugin no puede continuar sin la base de datos.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        if (!databaseManager.createTables()) {
            getLogger().severe("No se pudieron inicializar las tablas.");
            databaseManager.disconnect();
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        int uncertainClaims = databaseManager.flagUncertainVaultOperations();
        if (uncertainClaims < 0) {
            getLogger().severe("No se pudo completar la revisión de reclamaciones Player Value; el plugin se desactivará por seguridad.");
            databaseManager.disconnect();
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        if (uncertainClaims > 0) {
            getLogger().warning("Se aislaron " + uncertainClaims
                    + " reclamaciones de retiro interrumpidas. No se entregarán automáticamente; requieren revisión.");
        }

        playerManager = new PlayerManager(this, databaseManager);
        bossBarManager = new BossBarManager(this);
        worldRuleManager = new WorldRuleManager();

        if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            new HardcoreExpansion(this, playerManager, databaseManager).register();
            getLogger().info("Integración con PlaceholderAPI activada.");
        } else {
            getLogger().warning(
                    "PlaceholderAPI no está instalado. Los placeholders no estarán disponibles."
            );
        }

        playtimeTracker = new PlaytimeTracker(this, databaseManager);
        getServer().getPluginManager().registerEvents(playtimeTracker, this);
        playtimeTracker.start();

        dailyResetManager = new DailyResetManager(this, playerManager, bossBarManager);
        dailyResetManager.start();

        var hardcoreCommand = getCommand("hardcore");
        if (hardcoreCommand == null) {
            getLogger().severe("No se pudo registrar el comando hardcore. Revisa plugin.yml.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        hardcoreCommand.setExecutor(
                new HardcoreCommand(this, databaseManager, bossBarManager, playerManager)
        );
        var valueCommand = getCommand("value");
        if (valueCommand == null) {
            getLogger().severe("No se pudo registrar el comando value. Revisa plugin.yml.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        vaultInventoryService = new VaultInventoryService(this, databaseManager);
        vaultWithdrawalService = new VaultWithdrawalService(this, databaseManager, vaultInventoryService);
        valueCommand.setExecutor(new VaultCommand(this, databaseManager, vaultInventoryService));
        getServer().getPluginManager().registerEvents(new VaultMenuListener(this, vaultWithdrawalService), this);
        getServer().getPluginManager().registerEvents(vaultInventoryService, this);
        getServer().getPluginManager().registerEvents(vaultWithdrawalService, this);

        tradeService = new TradeService(this, databaseManager);
        var tradeCommand = getCommand("trade");
        if (tradeCommand == null) {
            getLogger().severe("No se pudo registrar el comando trade. Revisa plugin.yml.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        TradeCommand tradeExecutor = new TradeCommand(this, tradeService);
        tradeCommand.setExecutor(tradeExecutor);
        tradeCommand.setTabCompleter(tradeExecutor);
        getServer().getPluginManager().registerEvents(tradeService, this);
        getServer().getPluginManager().registerEvents(new TradeMenuListener(tradeService), this);

        for (var world : Bukkit.getWorlds()) {
            worldRuleManager.applyRules(world);
        }

        getServer().getPluginManager().registerEvents(new WorldLoadListener(worldRuleManager), this);
        getServer().getPluginManager().registerEvents(
                new PlayerJoinListener(this, playerManager, bossBarManager), this
        );
        if (Bukkit.getPluginManager().isPluginEnabled("SkinsRestorer")) {
            try {
                Class<?> bridge = Class.forName(
                        "com.escuadronsalchichon.hardcore.listener.SkinRestorerSkinSync"
                );
                bridge.getMethod("registerIfAvailable", JavaPlugin.class, PlayerDataStore.class)
                        .invoke(null, this, databaseManager);
            } catch (ReflectiveOperationException | LinkageError exception) {
                getLogger().warning("No se pudo cargar la integración opcional de SkinsRestorer; el plugin continuará sin sincronizar skins.");
            }
        }
        getServer().getPluginManager().registerEvents(
                new PlayerDeathListener(this, playerManager, bossBarManager), this
        );
        getServer().getPluginManager().registerEvents(new FoodListener(), this);
        getServer().getPluginManager().registerEvents(new TotemListener(databaseManager), this);

        getLogger().info("SalchichonHardcore se ha iniciado correctamente.");
    }

    public void reloadPluginConfiguration() {
        reloadConfig();
        if (dailyResetManager != null) {
            dailyResetManager.reschedule();
        }
    }

    @Override
    public void onDisable() {
        if (playtimeTracker != null) {
            playtimeTracker.shutdown();
        }
        if (dailyResetManager != null) {
            dailyResetManager.cancel();
        }
        if (bossBarManager != null) {
            bossBarManager.removeAll();
        }
        if (databaseManager != null) {
            databaseManager.disconnect();
        }
        getLogger().info("SalchichonHardcore se ha detenido correctamente.");
    }

    public PlayerDataStore getDatabaseManager() {
        return databaseManager;
    }

    public PlayerManager getPlayerManager() {
        return playerManager;
    }

    public BossBarManager getBossBarManager() {
        return bossBarManager;
    }
}
