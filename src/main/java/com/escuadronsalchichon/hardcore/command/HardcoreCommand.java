
package com.escuadronsalchichon.hardcore.command;

import com.escuadronsalchichon.hardcore.database.DatabaseManager;
import com.escuadronsalchichon.hardcore.manager.BossBarManager;
import com.escuadronsalchichon.hardcore.manager.PlayerManager;
import com.escuadronsalchichon.hardcore.player.PlayerData;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import com.escuadronsalchichon.hardcore.SalchichonHardcore;

public class HardcoreCommand implements CommandExecutor {

    private final DatabaseManager databaseManager;
    private final BossBarManager bossBarManager;
    private final PlayerManager playerManager;
    private final SalchichonHardcore plugin;

    public HardcoreCommand(
            SalchichonHardcore plugin,
            DatabaseManager databaseManager,
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
            sender.sendMessage("§cNo tienes permiso para utilizar este comando.");
            return true;
        }

        if (args.length < 1) {
            sendUsage(sender);
            return true;
        }

        String subcommand = args[0].toLowerCase();

        if (subcommand.equals("reload")) {

            plugin.reloadConfig();

            sender.sendMessage("§aConfiguración de SalchichonHardcore recargada.");

            return true;
        }

        if (args.length < 2) {
            sendUsage(sender);
            return true;
        }

        String targetName = args[1];

        try {
            PlayerData target = databaseManager.getPlayerByName(targetName);

            if (target == null) {
                sender.sendMessage("§cNo se encontraron datos de ese jugador.");
                return true;
            }

            boolean hasLife = playerManager.hasLife(target.getUuid());

            switch (subcommand) {

                case "check" -> {
                    sender.sendMessage("§6§lEstado de jugador");
                    sender.sendMessage("§7Jugador: §f" + target.getUsername());
                    sender.sendMessage(
                            "§7Estado: " +
                                    (hasLife ? "§aVIDA DISPONIBLE" : "§cSIN VIDAS")
                    );
                    sender.sendMessage(
                            "§7Última muerte: §f" +
                                    (target.getLastDeathDate() == null
                                            ? "Ninguna registrada"
                                            : target.getLastDeathDate())
                    );
                }

                case "restore" -> {

                    if (hasLife) {
                        sender.sendMessage(
                                "§eEse jugador ya tiene una vida disponible."
                        );
                        return true;
                    }

                    if (args.length < 3) {
                        sender.sendMessage(
                                "§cUso: /hardcore restore <jugador> <motivo>"
                        );
                        return true;
                    }

                    String reason = String.join(
                            " ",
                            java.util.Arrays.copyOfRange(args, 2, args.length)
                    ).trim();

                    if (reason.isBlank()) {
                        sender.sendMessage("§cDebes especificar un motivo.");
                        return true;
                    }

                    boolean restored = databaseManager.restorePlayerLife(
                            target.getUuid(),
                            target.getUsername(),
                            sender.getName(),
                            reason
                    );

                    if (!restored) {
                        sender.sendMessage("§cNo se pudo restaurar la vida.");
                        return true;
                    }

                    Player onlinePlayer = Bukkit.getPlayer(target.getUuid());

                    if (onlinePlayer != null) {
                        bossBarManager.showStatus(onlinePlayer, true);
                        onlinePlayer.sendMessage(
                                "§a¡Un administrador te ha restaurado tu vida!"
                        );
                    }

                    sender.sendMessage(
                            "§aVida restaurada para §f" + target.getUsername()
                    );
                    sender.sendMessage("§7Motivo: §f" + reason);
                }
                case "reset" -> {

                    if (args.length < 3) {
                        sender.sendMessage(
                                "§cUso: /hardcore reset <jugador> <motivo>"
                        );
                        return true;
                    }

                    String reason = String.join(
                            " ",
                            java.util.Arrays.copyOfRange(args, 2, args.length)
                    ).trim();

                    if (reason.isBlank()) {
                        sender.sendMessage("§cDebes especificar un motivo.");
                        return true;
                    }

                    boolean reset = databaseManager.resetPlayerCounter(
                            target.getUuid(),
                            target.getUsername(),
                            sender.getName(),
                            reason
                    );

                    if (!reset) {
                        sender.sendMessage("§cNo se pudo reiniciar el contador.");
                        return true;
                    }

                    Player onlinePlayer = Bukkit.getPlayer(target.getUuid());

                    if (onlinePlayer != null) {
                        bossBarManager.showStatus(onlinePlayer, true);
                        onlinePlayer.sendMessage(
                                "§aTu contador de muerte ha sido reiniciado por un administrador."
                        );
                    }

                    sender.sendMessage(
                            "§aContador reiniciado para §f" + target.getUsername()
                    );
                    sender.sendMessage("§7Motivo: §f" + reason);
                }

                default -> sendUsage(sender);
            }

        } catch (RuntimeException exception) {
            sender.sendMessage("§cOcurrió un error al ejecutar el comando.");
            exception.printStackTrace();
        }

        return true;
    }

    private void sendUsage(CommandSender sender) {
        sender.sendMessage("§6§lSalchichonHardcore");
        sender.sendMessage("§e/hardcore check <jugador>");
        sender.sendMessage("§e/hardcore restore <jugador> <motivo>");
        sender.sendMessage("§e/hardcore reset <jugador> <motivo>");
        sender.sendMessage("§e/hardcore reload");
    }
}
