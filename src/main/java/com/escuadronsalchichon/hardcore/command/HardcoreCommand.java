package com.escuadronsalchichon.hardcore.command;

import com.escuadronsalchichon.hardcore.SalchichonHardcore;
import com.escuadronsalchichon.hardcore.database.PlayerDataStore;
import com.escuadronsalchichon.hardcore.manager.BossBarManager;
import com.escuadronsalchichon.hardcore.manager.PlayerManager;
import com.escuadronsalchichon.hardcore.player.PlayerData;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Arrays;
import java.util.Locale;

public class HardcoreCommand implements CommandExecutor {

    private final SalchichonHardcore plugin;
    private final PlayerDataStore databaseManager;
    private final BossBarManager bossBarManager;
    private final PlayerManager playerManager;

    public HardcoreCommand(
            SalchichonHardcore plugin,
            PlayerDataStore databaseManager,
            BossBarManager bossBarManager,
            PlayerManager playerManager
    ) {
        this.plugin = plugin;
        this.databaseManager = databaseManager;
        this.bossBarManager = bossBarManager;
        this.playerManager = playerManager;
    }

    @Override
    public boolean onCommand(
            CommandSender sender,
            Command command,
            String label,
            String[] args
    ) {
        if (!sender.hasPermission("salchichonhardcore.admin")) {
            sender.sendMessage(message("messages.no-permission", "&cNo tienes permiso para utilizar este comando."));
            return true;
        }

        if (args.length < 1) {
            sendUsage(sender);
            return true;
        }

        String subcommand = args[0].toLowerCase(Locale.ROOT);

        if (subcommand.equals("reload")) {
            plugin.reloadPluginConfiguration();
            sender.sendMessage("§aConfiguración de SalchichonHardcore recargada.");
            return true;
        }

        if (args.length < 2) {
            sendUsage(sender);
            return true;
        }

        try {
            PlayerData target = databaseManager.getPlayerByName(args[1]);
            if (target == null) {
                sender.sendMessage(message("messages.no-data", "&cNo se encontraron datos de ese jugador."));
                return true;
            }

            boolean hasLife = playerManager.hasLife(target.getUuid());

            switch (subcommand) {
                case "stat" -> {
                    if (args.length < 5) {
                        sender.sendMessage("§cUso: /hardcore stat <jugador> <deaths|playtime|value> <nuevo_valor> <motivo>");
                        return true;
                    }
                    long value;
                    try {
                        value = Long.parseLong(args[3]);
                        if (value < 0) throw new NumberFormatException();
                    } catch (NumberFormatException invalid) {
                        sender.sendMessage("§cEl valor debe ser un entero mayor o igual a cero.");
                        return true;
                    }
                    String statKey = args[2].toLowerCase(Locale.ROOT);
                    if (!java.util.Set.of("deaths", "playtime", "value").contains(statKey)) {
                        sender.sendMessage("§cEstadística válida: deaths, playtime (ticks) o value.");
                        return true;
                    }
                    String reason = String.join(" ", Arrays.copyOfRange(args, 4, args.length)).trim();
                    if (reason.isBlank()) {
                        sender.sendMessage("§cDebes indicar un motivo.");
                        return true;
                    }
                    if (!databaseManager.setOfficialStat(target.getUuid(), statKey, value, sender.getName(), reason)) {
                        sender.sendMessage("§cNo se pudo corregir la estadística. Revisa los datos y la consola.");
                        return true;
                    }
                    sender.sendMessage("§aEstadística §f" + statKey + " §aactualizada para §f" + target.getUsername());
                    sender.sendMessage("§7Nuevo valor: §f" + value + " §8| §7Motivo: §f" + reason);
                }
                case "check" -> {
                    sender.sendMessage("§6§lEstado de jugador");
                    sender.sendMessage("§7Jugador: §f" + target.getUsername());
                    sender.sendMessage("§7Estado: " + (hasLife ? "§aVIDA DISPONIBLE" : "§cSIN VIDAS"));
                    sender.sendMessage("§7Última muerte: §f" +
                            (target.getLastDeathDate() == null ? "Ninguna registrada" : target.getLastDeathDate()));
                }
                case "restore" -> {
                    if (hasLife) {
                        sender.sendMessage("§eEse jugador ya tiene una vida disponible.");
                        return true;
                    }
                    String reason = getReason(args);
                    if (reason == null) {
                        sender.sendMessage("§cUso: /hardcore restore <jugador> <motivo>");
                        return true;
                    }
                    boolean restored = databaseManager.restorePlayerLife(
                            target.getUuid(), target.getUsername(), sender.getName(), reason
                    );
                    if (!restored) {
                        sender.sendMessage("§cNo se pudo restaurar la vida.");
                        return true;
                    }
                    updateOnlineTarget(target, "§a¡Un administrador te ha restaurado tu vida!");
                    sender.sendMessage("§aVida restaurada para §f" + target.getUsername());
                    sender.sendMessage("§7Motivo: §f" + reason);
                }
                case "reset" -> {
                    String reason = getReason(args);
                    if (reason == null) {
                        sender.sendMessage("§cUso: /hardcore reset <jugador> <motivo>");
                        return true;
                    }
                    boolean reset = databaseManager.resetPlayerCounter(
                            target.getUuid(), target.getUsername(), sender.getName(), reason
                    );
                    if (!reset) {
                        sender.sendMessage("§cNo se pudo reiniciar el contador.");
                        return true;
                    }
                    updateOnlineTarget(target, "§aTu contador de muerte ha sido reiniciado por un administrador.");
                    sender.sendMessage("§aContador reiniciado para §f" + target.getUsername());
                    sender.sendMessage("§7Motivo: §f" + reason);
                }
                default -> sendUsage(sender);
            }
        } catch (RuntimeException exception) {
            sender.sendMessage("§cOcurrió un error al ejecutar el comando.");
            plugin.getLogger().severe("Error al ejecutar /hardcore " + subcommand);
            exception.printStackTrace();
        }
        return true;
    }

    private String message(String path, String fallback) {
        String value = plugin.getConfig().getString(path, fallback);
        return org.bukkit.ChatColor.translateAlternateColorCodes('&', value);
    }

    private String getReason(String[] args) {
        if (args.length < 3) {
            return null;
        }
        String reason = String.join(" ", Arrays.copyOfRange(args, 2, args.length)).trim();
        return reason.isBlank() ? null : reason;
    }

    private void updateOnlineTarget(PlayerData target, String notification) {
        Player onlinePlayer = Bukkit.getPlayer(target.getUuid());
        if (onlinePlayer != null) {
            bossBarManager.showStatus(onlinePlayer, true);
            onlinePlayer.sendMessage(notification);
        }
    }

    private void sendUsage(CommandSender sender) {
        sender.sendMessage("§6§lSalchichonHardcore");
        sender.sendMessage("§e/hardcore check <jugador>");
        sender.sendMessage("§e/hardcore restore <jugador> <motivo>");
        sender.sendMessage("§e/hardcore reset <jugador> <motivo>");
        sender.sendMessage("§e/hardcore stat <jugador> <deaths|playtime|value> <valor> <motivo>");
        sender.sendMessage("§e/hardcore reload");
    }
}
