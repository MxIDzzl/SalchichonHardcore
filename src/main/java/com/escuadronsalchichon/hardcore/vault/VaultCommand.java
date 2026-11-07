package com.escuadronsalchichon.hardcore.vault;

import com.escuadronsalchichon.hardcore.SalchichonHardcore;
import com.escuadronsalchichon.hardcore.database.PlayerDataStore;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;

public final class VaultCommand implements CommandExecutor {
    private final SalchichonHardcore plugin;
    private final PlayerDataStore store;
    private final VaultInventoryService inventoryService;

    public VaultCommand(SalchichonHardcore plugin, PlayerDataStore store, VaultInventoryService inventoryService) {
        this.plugin = plugin;
        this.store = store;
        this.inventoryService = inventoryService;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§cEste comando solo puede utilizarse dentro del juego.");
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("deposit")) {
            inventoryService.openDepositMenu(player);
            return true;
        }
        if (!plugin.getConfig().getBoolean("player-value.enabled", false)) {
            player.sendMessage("§cPlayer Value está temporalmente desactivado mientras se termina de validar el sistema de inventarios.");
            return true;
        }
        int page = 0;
        if (args.length > 1) {
            player.sendMessage("§eUso: /value [página] o /value deposit");
            return true;
        }
        if (args.length == 1) {
            try {
                page = Integer.parseInt(args[0]) - 1;
                if (page < 0 || page > 100_000) throw new NumberFormatException();
            } catch (NumberFormatException invalid) {
                player.sendMessage("§cIndica un número de página válido.");
                return true;
            }
        }

        final int requestedPage = page;
        final java.util.UUID playerUuid = player.getUniqueId();
        final String playerName = player.getName();
        player.sendMessage("§8Cargando tu almacén Player Value...");
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            int offset;
            try {
                offset = Math.multiplyExact(requestedPage, VaultMenu.PAGE_SIZE);
            } catch (ArithmeticException overflow) {
                Bukkit.getScheduler().runTask(plugin, () -> player.sendMessage("§cLa página solicitada es demasiado alta."));
                return;
            }
            List<com.escuadronsalchichon.hardcore.database.VaultItemRecord> items =
                    store.getVaultItems(playerUuid, VaultMenu.PAGE_SIZE + 1, offset);
            long value = store.getPlayerValue(playerUuid);
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!player.isOnline() || !player.getUniqueId().equals(playerUuid)) return;
                player.openInventory(VaultMenu.create(playerUuid, requestedPage, value, items));
                player.sendMessage("§dPlayer Value §f" + value + " §8· §7Página " + (requestedPage + 1));
            });
        });
        return true;
    }
}
