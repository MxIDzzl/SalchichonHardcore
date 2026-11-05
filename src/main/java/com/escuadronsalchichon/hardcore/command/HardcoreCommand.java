package com.escuadronsalchichon.hardcore.command;

import com.escuadronsalchichon.hardcore.SalchichonHardcore;
import com.escuadronsalchichon.hardcore.database.PlayerDataStore;
import com.escuadronsalchichon.hardcore.database.PostgresDatabaseManager;
import com.escuadronsalchichon.hardcore.manager.BossBarManager;
import com.escuadronsalchichon.hardcore.manager.PlayerManager;
import com.escuadronsalchichon.hardcore.player.PlayerData;
import com.escuadronsalchichon.hardcore.util.PlaytimeFormatter;
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

        if (subcommand.equals("vault-status")) {
            if (args.length != 1) {
                sender.sendMessage("§cUso: /hardcore vault-status");
                return true;
            }
            var summary = databaseManager.getVaultRecoverySummary();
            if (summary == null) {
                sender.sendMessage("§cNo se pudo consultar el estado del almacén Player Value.");
                return true;
            }
            sender.sendMessage("§d§lPlayer Value — estado de operaciones");
            sender.sendMessage("§7Depósitos pendientes: §f" + summary.preparedDeposits());
            sender.sendMessage("§7Retiros DELIVERY_PENDING: §f" + summary.pendingWithdrawals());
            sender.sendMessage("§7Depósitos RECOVERY_REQUIRED: §c" + summary.recoveryRequiredDeposits());
            sender.sendMessage("§7Retiros RECOVERY_REQUIRED: §c" + summary.recoveryRequiredWithdrawals());
            sender.sendMessage("§8Consulta de solo lectura; no modifica ni entrega objetos.");
            return true;
        }

        if (subcommand.equals("reload")) {
            plugin.reloadPluginConfiguration();
            sender.sendMessage("§aConfiguración de SalchichonHardcore recargada.");
            return true;
        }

        if (subcommand.equals("migrate-sqlite")) {
            if (!(sender instanceof org.bukkit.command.ConsoleCommandSender)) {
                sender.sendMessage("§cPor seguridad, la migración solo puede ejecutarse desde la consola.");
                return true;
            }
            if (!(databaseManager instanceof PostgresDatabaseManager postgres)) {
                sender.sendMessage("§cActiva PostgreSQL y reinicia el servidor antes de importar SQLite.");
                return true;
            }
            if (args.length != 2 || !args[1].equalsIgnoreCase("CONFIRM")) {
                sender.sendMessage("§eUso: hardcore migrate-sqlite CONFIRM");
                sender.sendMessage("§7Requiere mantenimiento, copia de hardcore.db y PostgreSQL inicializado.");
                return true;
            }
            if (!Bukkit.getOnlinePlayers().isEmpty()) {
                sender.sendMessage("§cNo puede haber jugadores conectados durante la migración.");
                return true;
            }
            try {
                var report = postgres.importLegacySqlite();
                sender.sendMessage("§aImportación completada y verificada.");
                sender.sendMessage("§7Jugadores: §f" + report.playersImported() + " §7| Auditorías: §f" + report.auditRowsImported());
            } catch (Exception exception) {
                sender.sendMessage("§cLa importación falló y fue revertida. Revisa la consola.");
                plugin.getLogger().severe("Falló la importación de hardcore.db a PostgreSQL.");
                exception.printStackTrace();
            }
            return true;
        }

        if (subcommand.equals("trade-evidence")) {
            if (args.length != 2) {
                sender.sendMessage("§cUso: /hardcore trade-evidence <jugador>");
                return true;
            }
            String targetName = args[1];
            sender.sendMessage("§8Escaneando marcadores visibles del intercambio (solo lectura)...");
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                PlayerData target = databaseManager.getPlayerByName(targetName);
                var session = target == null ? null : databaseManager.getActiveTrade(target.getUuid());
                var items = session == null ? java.util.List.<com.escuadronsalchichon.hardcore.trade.TradeEscrowRecord>of()
                        : databaseManager.getTradeItems(session.tradeId());
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (target == null) {
                        sender.sendMessage("§cNo se encontró ese jugador.");
                        return;
                    }
                    if (session == null) {
                        sender.sendMessage("§7No hay una sesión activa asociada a ese jugador.");
                        return;
                    }
                    sender.sendMessage("§d§lTrade evidence — " + target.getUsername());
                    sender.sendMessage("§7Sesión: §f" + session.tradeId() + " §8| §7Estado: §f" + session.status());
                    var findings = new com.escuadronsalchichon.hardcore.trade.TradeEvidenceScanner(plugin)
                            .scan(items);
                    for (String finding : findings) sender.sendMessage("§7" + finding);
                    sender.sendMessage("§cSolo evidencia visible: no inspecciona playerdata offline ni chunks descargados. La ausencia de marcadores no demuestra que un objeto no exista.");
                    sender.sendMessage("§8No se modificó, retiró ni entregó ningún objeto.");
                });
            });
            return true;
        }

        if (subcommand.equals("trade-status")) {
            if (args.length != 2) {
                sender.sendMessage("§cUso: /hardcore trade-status <jugador>");
                return true;
            }
            String targetName = args[1];
            sender.sendMessage("§8Consultando el estado durable del intercambio...");
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                PlayerData target = databaseManager.getPlayerByName(targetName);
                var session = target == null ? null : databaseManager.getActiveTrade(target.getUuid());
                var items = session == null ? java.util.List.<com.escuadronsalchichon.hardcore.trade.TradeEscrowRecord>of()
                        : databaseManager.getTradeItems(session.tradeId());
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (target == null) {
                        sender.sendMessage("§cNo se encontró ese jugador.");
                        return;
                    }
                    if (session == null) {
                        sender.sendMessage("§7" + target.getUsername() + " no tiene un intercambio activo.");
                        return;
                    }
                    sender.sendMessage("§d§lTrade recovery — solo lectura");
                    sender.sendMessage("§7ID: §f" + session.tradeId());
                    sender.sendMessage("§7Estado: §f" + session.status() + " §8| §7Revisión: §f" + session.revision());
                    sender.sendMessage("§7A: §f" + session.playerA() + " §8| §7B: §f" + session.playerB());
                    sender.sendMessage("§7Confirmaciones actuales: §f" + session.isConfirmed(session.playerA())
                            + " §8/ §f" + session.isConfirmed(session.playerB()));
                    sender.sendMessage("§7Operaciones: §f" + items.size());
                    for (var item : items) {
                        sender.sendMessage("§8• §f" + item.operationId().toString().substring(0, 8)
                                + " §7" + item.status()
                                + " §8| §7owner " + item.ownerUuid().toString().substring(0, 8)
                                + " §8→ §7recipient " + item.recipientUuid().toString().substring(0, 8));
                    }
                    sender.sendMessage("§cConsulta de solo lectura. No se modificó ni entregó ningún objeto.");
                });
            });
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
                    long officialDeaths = databaseManager.getOfficialDeaths(target.getUuid());
                    long playtimeTicks = databaseManager.getPlaytimeTicks(target.getUuid());
                    long playerValue = databaseManager.getPlayerValue(target.getUuid());
                    sender.sendMessage("§7Muertes oficiales: §f" + officialDeaths);
                    sender.sendMessage("§7Tiempo registrado: §f" + PlaytimeFormatter.format(playtimeTicks));
                    sender.sendMessage("§7Player Value: §d" + com.escuadronsalchichon.hardcore.util.PlayerValueFormatter.compact(playerValue)
                            + " §8(" + playerValue + ")");
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
        sender.sendMessage("§e/hardcore vault-status §7(estado interno, solo lectura)");
        sender.sendMessage("§e/hardcore trade-status <jugador> §7(escrow, solo lectura)");
        sender.sendMessage("§e/hardcore trade-evidence <jugador> §7(marcadores visibles, solo lectura)");
        sender.sendMessage("§e/hardcore restore <jugador> <motivo>");
        sender.sendMessage("§e/hardcore reset <jugador> <motivo>");
        sender.sendMessage("§e/hardcore stat <jugador> <deaths|playtime|value> <valor> <motivo>");
        sender.sendMessage("§e/hardcore reload");
        sender.sendMessage("§e/hardcore migrate-sqlite CONFIRM §7(solo consola, mantenimiento)");
    }
}
