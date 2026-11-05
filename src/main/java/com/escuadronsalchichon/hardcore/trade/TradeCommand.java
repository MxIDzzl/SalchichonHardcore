package com.escuadronsalchichon.hardcore.trade;

import com.escuadronsalchichon.hardcore.SalchichonHardcore;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class TradeCommand implements CommandExecutor, TabCompleter {
    private final SalchichonHardcore plugin;
    private final TradeService trades;
    private final Map<UUID, UUID> invitations = new ConcurrentHashMap<>();

    public TradeCommand(SalchichonHardcore plugin, TradeService trades) {
        this.plugin = plugin;
        this.trades = trades;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§cEste comando solo puede utilizarse dentro del juego.");
            return true;
        }
        if (!plugin.getConfig().getBoolean("trade.enabled", true)) {
            player.sendMessage("§cLos intercambios están desactivados por configuración.");
            return true;
        }
        if (args.length == 0) {
            sendHelp(player);
            return true;
        }

        String action = args[0].toLowerCase(Locale.ROOT);
        switch (action) {
            case "accept" -> accept(player);
            case "deny" -> deny(player);
            case "offer" -> {
                if (args.length != 1) player.sendMessage("§cUso: /trade offer");
                else trades.offer(player);
            }
            case "confirm" -> {
                if (args.length != 1) player.sendMessage("§cUso: /trade confirm");
                else trades.confirm(player);
            }
            case "status" -> {
                if (args.length != 1) player.sendMessage("§cUso: /trade status");
                else trades.status(player);
            }
            default -> invite(player, args[0]);
        }
        return true;
    }

    private void invite(Player inviter, String targetName) {
        Player target = Bukkit.getPlayerExact(targetName);
        if (target == null || !target.isOnline()) {
            inviter.sendMessage("§cEse jugador no está conectado.");
            return;
        }
        if (target.getUniqueId().equals(inviter.getUniqueId())) {
            inviter.sendMessage("§cNo puedes invitarte a ti mismo.");
            return;
        }
        UUID previous = invitations.putIfAbsent(target.getUniqueId(), inviter.getUniqueId());
        if (previous != null) {
            inviter.sendMessage("§eEse jugador ya tiene una invitación pendiente.");
            return;
        }
        inviter.sendMessage("§dInvitación enviada a §f" + target.getName() + "§d.");
        target.sendMessage("§d§lInvitación de intercambio");
        target.sendMessage("§f" + inviter.getName() + " §7quiere intercambiar objetos contigo.");
        target.sendMessage("§7Escribe §a/trade accept §7o §c/trade deny§7.");
    }

    private void accept(Player target) {
        UUID inviterId = invitations.remove(target.getUniqueId());
        if (inviterId == null) {
            target.sendMessage("§cNo tienes invitaciones pendientes.");
            return;
        }
        Player inviter = Bukkit.getPlayer(inviterId);
        if (inviter == null || !inviter.isOnline()) {
            target.sendMessage("§cLa persona que te invitó ya no está conectada.");
            return;
        }
        trades.createTrade(inviter, target);
    }

    private void deny(Player target) {
        UUID inviterId = invitations.remove(target.getUniqueId());
        if (inviterId == null) {
            target.sendMessage("§cNo tienes invitaciones pendientes.");
            return;
        }
        Player inviter = Bukkit.getPlayer(inviterId);
        target.sendMessage("§7Invitación rechazada.");
        if (inviter != null) inviter.sendMessage("§c" + target.getName() + " rechazó tu invitación.");
    }

    private void sendHelp(Player player) {
        player.sendMessage("§d§lIntercambios — comandos");
        player.sendMessage("§f/trade <jugador> §7Invitar a alguien");
        player.sendMessage("§f/trade accept §7Aceptar una invitación");
        player.sendMessage("§f/trade deny §7Rechazar una invitación");
        player.sendMessage("§f/trade offer §7Ofrecer el objeto de tu mano");
        player.sendMessage("§f/trade confirm §7Confirmar la oferta actual");
        player.sendMessage("§f/trade status §7Consultar el intercambio");
        player.sendMessage("§8Solo se intercambian objetos; Player Value no es transferible.");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            List<String> options = new ArrayList<>(List.of("accept", "deny", "offer", "confirm", "status"));
            for (Player online : Bukkit.getOnlinePlayers()) {
                if (sender instanceof Player self && online.getUniqueId().equals(self.getUniqueId())) continue;
                options.add(online.getName());
            }
            return options.stream().filter(option -> option.toLowerCase(Locale.ROOT).startsWith(prefix)).toList();
        }
        return List.of();
    }
}
