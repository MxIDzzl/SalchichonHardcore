package com.escuadronsalchichon.hardcore.expansion;

import com.escuadronsalchichon.hardcore.database.PlayerDataStore;
import com.escuadronsalchichon.hardcore.manager.PlayerManager;
import com.escuadronsalchichon.hardcore.util.PlayerValueFormatter;
import com.escuadronsalchichon.hardcore.util.PlaytimeFormatter;
import com.escuadronsalchichon.hardcore.util.DifficultyProgression;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.OfflinePlayer;
import org.bukkit.Statistic;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;


public class HardcoreExpansion extends PlaceholderExpansion {
    private final JavaPlugin plugin;
    private final PlayerManager playerManager;
    private final PlayerDataStore databaseManager;

    public HardcoreExpansion(JavaPlugin plugin, PlayerManager playerManager, PlayerDataStore databaseManager) {
        this.plugin = plugin;
        this.playerManager = playerManager;
        this.databaseManager = databaseManager;
    }

    @Override public @NotNull String getAuthor() { return "EscuadronSalchichon"; }
    @Override public @NotNull String getIdentifier() { return "salchichonhardcore"; }
    @Override public @NotNull String getVersion() { return "1.1.0"; }
    @Override public boolean persist() { return true; }

    @Override
    public String onRequest(OfflinePlayer player, @NotNull String params) {
        if (player == null || player.getUniqueId() == null) return "";
        try {
            String key = params.toLowerCase();
            return switch (key) {
                case "lives" -> playerManager.hasLife(player.getUniqueId()) ? "1" : "0";
                case "status" -> playerManager.hasLife(player.getUniqueId()) ? "DISPONIBLE" : "SIN VIDAS";
                case "totems" -> String.valueOf(databaseManager.getTotems(player.getUniqueId()));
                case "deaths" -> String.valueOf(databaseManager.getOfficialDeaths(player.getUniqueId()));
                case "playtime" -> PlaytimeFormatter.format(databaseManager.getPlaytimeTicks(player.getUniqueId()));
                case "value" -> String.valueOf(databaseManager.getPlayerValue(player.getUniqueId()));
                case "value_compact" -> PlayerValueFormatter.compact(databaseManager.getPlayerValue(player.getUniqueId()));
                case "health" -> getHealthValue(player);
                case "health_display" -> getHealthDisplay(player);
                case "season" -> plugin.getConfig().getString("season.name", "UHC SEASON 1");
                case "difficulty" -> DifficultyProgression.currentDifficulty(plugin);
                case "season_day" -> String.valueOf(DifficultyProgression.seasonDay(plugin));
                default -> null;
            };
        } catch (Exception exception) {
            plugin.getLogger().warning("Error al resolver placeholder " + params + ": " + exception.getMessage());
            return "0";
        }
    }

    private String getHealthValue(OfflinePlayer offlinePlayer) {
        if (!(offlinePlayer instanceof Player player)) return "0";
        double max = player.getAttribute(Attribute.MAX_HEALTH) == null
                ? 20.0 : player.getAttribute(Attribute.MAX_HEALTH).getValue();
        return String.valueOf((int) Math.ceil(max + player.getAbsorptionAmount()));
    }

    private String getHealthDisplay(OfflinePlayer offlinePlayer) {
        if (!(offlinePlayer instanceof Player player)) return "<gray>—</gray>";
        double max = player.getAttribute(Attribute.MAX_HEALTH) == null
                ? 20.0 : player.getAttribute(Attribute.MAX_HEALTH).getValue();
        double absorption = player.getAbsorptionAmount();
        int normal = (int) Math.ceil(max);
        int golden = (int) Math.ceil(absorption);
        String value = "<#FF8FA3>❤ " + normal + "</#FF8FA3>";
        if (golden > 0) value += " <gold>❤ " + golden + "</gold>";
        return value;
    }


}