package com.escuadronsalchichon.hardcore.vault;

import com.escuadronsalchichon.hardcore.SalchichonHardcore;
import com.escuadronsalchichon.hardcore.database.PlayerDataStore;
import com.escuadronsalchichon.hardcore.database.VaultClaimRecord;
import com.escuadronsalchichon.hardcore.database.VaultItemRecord;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class VaultWithdrawalService implements Listener {
    private final SalchichonHardcore plugin;
    private final PlayerDataStore store;
    private final VaultInventoryService inventoryLocks;
    private final NamespacedKey claimMarker;

    public VaultWithdrawalService(SalchichonHardcore plugin, PlayerDataStore store,
                                  VaultInventoryService inventoryLocks) {
        this.plugin = plugin;
        this.store = store;
        this.inventoryLocks = inventoryLocks;
        this.claimMarker = new NamespacedKey(plugin, "vault-withdrawal-claim");
    }

    public void withdraw(Player player, VaultItemRecord vaultItem) {
        UUID uuid = player.getUniqueId();
        if (!plugin.getConfig().getBoolean("player-value.enabled", false)) {
            player.sendMessage("§cPlayer Value está desactivado.");
            return;
        }
        if (!inventoryLocks.beginExternalOperation(uuid)) {
            player.sendMessage("§eTu almacén tiene una operación pendiente.");
            return;
        }
        ItemStack preview = deserialize(vaultItem.payload());
        if (!validItem(preview, vaultItem.material(), vaultItem.amount()) || !canFit(player, preview)) {
            inventoryLocks.endExternalOperation(uuid);
            player.sendMessage("§cNo tienes espacio suficiente o el objeto almacenado no es válido.");
            return;
        }
        Instant now = Instant.now();
        long seconds = Math.max(0L, plugin.getConfig().getLong("player-value.cooldown-seconds", 5L));
        Instant next;
        try {
            next = now.plusSeconds(seconds);
        } catch (RuntimeException invalid) {
            inventoryLocks.endExternalOperation(uuid);
            player.sendMessage("§cEl cooldown configurado no es válido.");
            return;
        }
        player.sendMessage("§8Preparando retiro seguro...");
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            VaultClaimRecord claim = store.createVaultWithdrawal(uuid, vaultItem.itemId(), now, next);
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!player.isOnline()) return;
                if (claim == null) {
                    inventoryLocks.endExternalOperation(uuid);
                    player.sendMessage("§cNo se pudo crear el retiro. Comprueba el cooldown y que el objeto siga en tu almacén.");
                    return;
                }
                deliverPending(player, claim);
            });
        });
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        scheduleJoinRecovery(player);
    }

    private void scheduleJoinRecovery(Player player) {
        UUID uuid = player.getUniqueId();
        if (!player.isOnline()) return;
        if (inventoryLocks.isRecoveryPending(uuid)) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> scheduleJoinRecovery(player), 20L);
            return;
        }
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            List<VaultClaimRecord> claims = store.getUncertainVaultClaims(uuid);
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!player.isOnline()) return;
                if (claims == null) {
                    inventoryLocks.quarantineExternalOperation(uuid);
                    player.sendMessage("§cNo se pudieron revisar los retiros pendientes. Player Value queda bloqueado.");
                    return;
                }
                if (claims.isEmpty()) {
                    clearOrphanMarkers(player);
                    player.saveData();
                    return;
                }
                if (!inventoryLocks.beginExternalOperation(uuid)) {
                    inventoryLocks.quarantineExternalOperation(uuid);
                    player.sendMessage("§ePlayer Value está terminando otra recuperación; vuelve a entrar para revisar los retiros.");
                    return;
                }
                recoverNext(player, claims, 0);
            });
        });
    }

    private void recoverNext(Player player, List<VaultClaimRecord> claims, int index) {
        UUID uuid = player.getUniqueId();
        if (!player.isOnline()) return;
        if (index >= claims.size()) {
            clearOrphanMarkers(player);
            player.saveData();
            inventoryLocks.endExternalOperation(uuid);
            if (!claims.isEmpty()) player.sendMessage("§aSe revisaron " + claims.size() + " retiro(s) pendientes.");
            return;
        }
        VaultClaimRecord claim = claims.get(index);
        int marked = markedAmount(player, claim.claimId());
        if (claim.status().equals("PENDING")) {
            if (marked != 0) {
                inventoryLocks.quarantineExternalOperation(uuid);
                player.sendMessage("§cUna reclamación pendiente tiene un marcador inesperado. Requiere revisión manual.");
                return;
            }
            deliverPending(player, claim, () -> recoverNext(player, claims, index + 1));
            return;
        }
        if (marked != claim.amount()) {
            inventoryLocks.quarantineExternalOperation(uuid);
            if (marked > 0) player.sendMessage("§cEl objeto de un retiro interrumpido está incompleto. No lo muevas; requiere revisión.");
            else player.sendMessage("§cUn retiro interrumpido no tiene evidencia de entrega. No se volverá a generar automáticamente.");
            plugin.getLogger().warning("Retiro requiere revisión manual: " + claim.claimId() + " (" + claim.status() + ").");
            return;
        }
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            boolean acknowledged = store.markVaultClaimDelivered(claim.claimId(), uuid);
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!player.isOnline()) return;
                if (!acknowledged) {
                    inventoryLocks.quarantineExternalOperation(uuid);
                    player.sendMessage("§cNo se pudo confirmar el retiro recuperado. El objeto permanece protegido.");
                    return;
                }
                clearMarker(player, claim.claimId());
                player.saveData();
                recoverNext(player, claims, index + 1);
            });
        });
    }

    private void deliverPending(Player player, VaultClaimRecord claim) {
        deliverPending(player, claim, () -> {});
    }

    private void deliverPending(Player player, VaultClaimRecord claim, Runnable afterSuccess) {
        UUID uuid = player.getUniqueId();
        ItemStack item = deserialize(claim.payload());
        if (!validItem(item, claim.material(), claim.amount()) || !canFit(player, item) || !writeMarker(item, claim.claimId())) {
            inventoryLocks.quarantineExternalOperation(uuid);
            player.sendMessage("§eNo se pudo entregar el retiro automáticamente. Libera espacio o solicita revisión.");
            return;
        }
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            boolean delivering = store.markVaultClaimDelivering(claim.claimId(), uuid);
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!player.isOnline()) return;
                if (!delivering) {
                    inventoryLocks.quarantineExternalOperation(uuid);
                    player.sendMessage("§cNo se pudo reservar la entrega. No se generó ningún objeto.");
                    return;
                }
                Map<Integer, ItemStack> leftovers = player.getInventory().addItem(item);
                if (!leftovers.isEmpty()) {
                    inventoryLocks.quarantineExternalOperation(uuid);
                    player.sendMessage("§cLa entrega quedó incompleta y aislada para revisión. No intentes repetirla.");
                    return;
                }
                try {
                    player.saveData();
                } catch (RuntimeException failure) {
                    inventoryLocks.quarantineExternalOperation(uuid);
                    player.sendMessage("§cNo se pudo guardar el inventario. El retiro quedó aislado para recuperación.");
                    return;
                }
                Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                    boolean acknowledged = store.markVaultClaimDelivered(claim.claimId(), uuid);
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        if (!player.isOnline()) return;
                        if (!acknowledged) {
                            inventoryLocks.quarantineExternalOperation(uuid);
                            player.sendMessage("§eEl objeto está marcado en tu inventario, pero falta confirmar el retiro. No lo muevas.");
                            return;
                        }
                        clearMarker(player, claim.claimId());
                        player.saveData();
                        inventoryLocks.endExternalOperation(uuid);
                        player.sendMessage("§aRetiro completado: §f" + claim.amount() + " " + claim.material().toLowerCase(java.util.Locale.ROOT));
                        afterSuccess.run();
                    });
                });
            });
        });
    }

    private ItemStack deserialize(byte[] payload) {
        try { return ItemStack.deserializeBytes(payload); }
        catch (RuntimeException invalid) { return null; }
    }

    private boolean validItem(ItemStack item, String material, int amount) {
        return item != null && !item.getType().isAir() && item.getType().name().equals(material)
                && item.getAmount() == amount && amount <= item.getMaxStackSize()
                && !item.getItemMeta().getPersistentDataContainer().has(claimMarker, PersistentDataType.STRING);
    }

    private boolean canFit(Player player, ItemStack item) {
        int emptySlots = 0;
        for (ItemStack existing : player.getInventory().getStorageContents()) {
            if (existing == null || existing.getType().isAir()) emptySlots++;
        }
        return emptySlots * item.getMaxStackSize() >= item.getAmount();
    }

    private boolean writeMarker(ItemStack item, UUID claimId) {
        try {
            ItemMeta meta = item.getItemMeta();
            if (meta == null) return false;
            meta.getPersistentDataContainer().set(claimMarker, PersistentDataType.STRING, claimId.toString());
            item.setItemMeta(meta);
            return true;
        } catch (RuntimeException invalid) {
            return false;
        }
    }

    private int markedAmount(Player player, UUID claimId) {
        int amount = 0;
        for (ItemStack item : player.getInventory().getContents()) {
            if (item == null || !item.hasItemMeta()) continue;
            String marker = item.getItemMeta().getPersistentDataContainer().get(claimMarker, PersistentDataType.STRING);
            if (claimId.toString().equals(marker)) amount = Math.addExact(amount, item.getAmount());
        }
        return amount;
    }

    private void clearMarker(Player player, UUID claimId) {
        for (ItemStack item : player.getInventory().getContents()) {
            if (item == null || !item.hasItemMeta()) continue;
            ItemMeta meta = item.getItemMeta();
            String marker = meta.getPersistentDataContainer().get(claimMarker, PersistentDataType.STRING);
            if (!claimId.toString().equals(marker)) continue;
            meta.getPersistentDataContainer().remove(claimMarker);
            item.setItemMeta(meta);
        }
    }

    private void clearOrphanMarkers(Player player) {
        for (ItemStack item : player.getInventory().getContents()) {
            if (item == null || !item.hasItemMeta()) continue;
            ItemMeta meta = item.getItemMeta();
            if (!meta.getPersistentDataContainer().has(claimMarker, PersistentDataType.STRING)) continue;
            meta.getPersistentDataContainer().remove(claimMarker);
            item.setItemMeta(meta);
        }
    }
}
