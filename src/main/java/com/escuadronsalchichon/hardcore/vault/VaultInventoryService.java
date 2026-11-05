package com.escuadronsalchichon.hardcore.vault;

import com.escuadronsalchichon.hardcore.SalchichonHardcore;
import com.escuadronsalchichon.hardcore.database.PlayerDataStore;
import com.escuadronsalchichon.hardcore.database.VaultItemRecord;
import com.escuadronsalchichon.hardcore.database.VaultDepositRecoveryRecord;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class VaultInventoryService implements Listener {
    private final SalchichonHardcore plugin;
    private final PlayerDataStore store;
    private final VaultItemPolicy policy;
    private final NamespacedKey depositMarker;
    private final Set<UUID> lockedPlayers = ConcurrentHashMap.newKeySet();
    private final Set<UUID> recoveryPlayers = ConcurrentHashMap.newKeySet();

    public VaultInventoryService(SalchichonHardcore plugin, PlayerDataStore store) {
        this.plugin = plugin;
        this.store = store;
        this.depositMarker = new NamespacedKey(plugin, "vault-deposit-operation");
        Map<String, Long> values = new HashMap<>();
        var section = plugin.getConfig().getConfigurationSection("player-value.allowed-items");
        if (section != null) {
            for (String key : section.getKeys(false)) {
                Object raw = section.get(key);
                if (raw instanceof Number number) values.put(key, number.longValue());
            }
        }
        int maxStack = plugin.getConfig().getInt("player-value.max-stack-size-per-operation", 64);
        VaultItemPolicy configured;
        try {
            configured = new VaultItemPolicy(values, maxStack);
        } catch (IllegalArgumentException invalid) {
            plugin.getLogger().severe("La política de Player Value es inválida; los depósitos permanecerán desactivados.");
            configured = null;
        }
        this.policy = configured;
    }

    public boolean isLocked(UUID playerUuid) {
        return lockedPlayers.contains(playerUuid);
    }

    public void deposit(Player player) {
        if (!plugin.getConfig().getBoolean("player-value.enabled", false)) {
            player.sendMessage("§cPlayer Value está desactivado.");
            return;
        }
        if (policy == null) {
            player.sendMessage("§cLa lista de objetos permitidos no está configurada correctamente.");
            return;
        }
        UUID playerUuid = player.getUniqueId();
        if (recoveryPlayers.contains(playerUuid) || !lockedPlayers.add(playerUuid)) {
            player.sendMessage("§eTu inventario está ocupado por una operación Player Value.");
            return;
        }

        ItemStack original = player.getInventory().getItemInMainHand();
        if (original.getType().isAir()) {
            lockedPlayers.remove(playerUuid);
            player.sendMessage("§cSostén en la mano el objeto que quieres depositar.");
            return;
        }
        var credit = policy.creditFor(original);
        if (credit.isEmpty()) {
            lockedPlayers.remove(playerUuid);
            player.sendMessage("§cEse objeto no está permitido o contiene metadatos no admitidos.");
            return;
        }

        UUID operationId = UUID.randomUUID();
        ItemStack marked = original.clone();
        if (!writeMarker(marked, operationId)) {
            lockedPlayers.remove(playerUuid);
            player.sendMessage("§cNo se pudo preparar el marcador seguro del depósito.");
            return;
        }
        player.getInventory().setItemInMainHand(marked);
        try {
            player.saveData();
        } catch (RuntimeException failure) {
            player.getInventory().setItemInMainHand(original);
            lockedPlayers.remove(playerUuid);
            player.sendMessage("§cNo se pudo guardar el inventario; el depósito no se inició.");
            return;
        }

        VaultItemRecord record = new VaultItemRecord(operationId, playerUuid,
                original.getType().name(), original.getAmount(), original.serializeAsBytes(),
                credit.getAsLong(), Instant.now());
        player.sendMessage("§8Preparando depósito seguro...");
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            boolean prepared = store.prepareVaultDeposit(playerUuid, player.getName(), record);
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!player.isOnline()) return;
                if (!prepared) {
                    player.sendMessage("§eLa respuesta del registro no fue concluyente. Se está verificando el inventario antes de liberar la operación.");
                    startRecovery(player);
                    return;
                }
                ItemStack current = findMarkedItem(player, operationId);
                if (current == null) {
                    recoveryPlayers.add(playerUuid);
                    player.sendMessage("§eNo se encontró el marcador del depósito. La operación queda bloqueada para revisión; no intentes repetirla.");
                    return;
                }
                removeMarkedItem(player, operationId);
                try {
                    player.saveData();
                } catch (RuntimeException failure) {
                    recoveryPlayers.add(playerUuid);
                    player.sendMessage("§cNo se pudo confirmar el guardado del inventario. La operación quedó aislada; vuelve a entrar para recuperarla.");
                    return;
                }
                Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                    boolean removalRecorded = store.markVaultDepositInventoryRemoved(playerUuid, operationId);
                    boolean committed = removalRecorded && store.commitVaultDeposit(playerUuid, operationId);
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        if (!player.isOnline()) return;
                        if (committed) {
                            player.sendMessage("§aDepósito completado: §d+" + credit.getAsLong() + " Player Value");
                            lockedPlayers.remove(playerUuid);
                            recoveryPlayers.remove(playerUuid);
                        } else {
                            player.sendMessage("§eEl depósito no pudo finalizarse automáticamente. Se verificará el registro durable antes de permitir otro depósito.");
                            startRecovery(player);
                        }
                    });
                });
            });
        });
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        recoveryPlayers.add(uuid);
        if (hasAnyMarker(player)) lockedPlayers.add(uuid);
        startRecovery(player);
    }

    private void startRecovery(Player player) {
        UUID uuid = player.getUniqueId();
        recoveryPlayers.add(uuid);
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            List<VaultDepositRecoveryRecord> pending = store.getUncertainVaultDeposits(uuid);
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!player.isOnline()) return;
                if (pending == null) {
                    player.sendMessage("§cNo se pudo revisar una operación Player Value. Tu inventario queda protegido hasta poder verificarla.");
                    plugin.getLogger().warning("No se pudo recuperar la bóveda de " + player.getName() + "; se mantiene bloqueado el inventario.");
                    return;
                }
                recoverNext(player, pending, 0);
            });
        });
    }

    private void recoverNext(Player player, List<VaultDepositRecoveryRecord> pending, int index) {
        UUID uuid = player.getUniqueId();
        if (!player.isOnline()) return;
        if (index >= pending.size()) {
            clearOrphanMarkers(player);
            player.saveData();
            lockedPlayers.remove(uuid);
            recoveryPlayers.remove(uuid);
            if (!pending.isEmpty()) player.sendMessage("§aSe revisaron " + pending.size() + " operación(es) de Player Value.");
            return;
        }

        VaultDepositRecoveryRecord recovery = pending.get(index);
        VaultItemRecord operation = recovery.item();
        boolean markerPresent = findMarkedItem(player, operation.itemId()) != null;
        boolean safeAbort = markerPresent && (recovery.status().equals("PREPARED")
                || recovery.status().equals("RECOVERY_REQUIRED"));
        boolean safeCommit = !markerPresent && recovery.status().equals("DELIVERY_PENDING");
        if (!safeAbort && !safeCommit) {
            if (markerPresent) lockedPlayers.add(uuid);
            else lockedPlayers.remove(uuid);
            player.sendMessage("§cUna operación Player Value no tiene evidencia suficiente para resolverse automáticamente. No se volverá a acreditar ni a cancelar sin revisión.");
            plugin.getLogger().warning("Operación Player Value requiere revisión manual: " + operation.itemId() + " (" + recovery.status() + ").");
            return;
        }
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            boolean resolved = safeAbort
                    ? store.abortVaultDeposit(uuid, operation.itemId())
                    : store.commitVaultDeposit(uuid, operation.itemId());
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!player.isOnline()) return;
                if (!resolved) {
                    player.sendMessage("§cUna operación Player Value sigue sin poder verificarse. El inventario permanece protegido.");
                    plugin.getLogger().warning("No se pudo resolver la operación " + operation.itemId() + " de " + player.getName() + ".");
                    return;
                }
                if (markerPresent) removeMarkedItem(player, operation.itemId());
                recoverNext(player, pending, index + 1);
            });
        });
    }

    private boolean hasAnyMarker(Player player) {
        for (ItemStack item : player.getInventory().getContents()) {
            if (item != null && item.hasItemMeta()
                    && item.getItemMeta().getPersistentDataContainer().has(depositMarker, PersistentDataType.STRING)) {
                return true;
            }
        }
        return false;
    }

    private ItemStack findMarkedItem(Player player, UUID operationId) {
        for (ItemStack item : player.getInventory().getContents()) {
            if (item == null || !item.hasItemMeta()) continue;
            String marker = item.getItemMeta().getPersistentDataContainer()
                    .get(depositMarker, PersistentDataType.STRING);
            if (operationId.toString().equals(marker)) return item;
        }
        return null;
    }

    private void removeMarkedItem(Player player, UUID operationId) {
        ItemStack[] contents = player.getInventory().getContents();
        boolean changed = false;
        for (int i = 0; i < contents.length; i++) {
            ItemStack item = contents[i];
            if (item == null || !item.hasItemMeta()) continue;
            String marker = item.getItemMeta().getPersistentDataContainer()
                    .get(depositMarker, PersistentDataType.STRING);
            if (!operationId.toString().equals(marker)) continue;
            ItemMeta meta = item.getItemMeta();
            meta.getPersistentDataContainer().remove(depositMarker);
            item.setItemMeta(meta);
            contents[i] = item;
            changed = true;
        }
        if (changed) player.getInventory().setContents(contents);
    }

    private void clearOrphanMarkers(Player player) {
        ItemStack[] contents = player.getInventory().getContents();
        boolean changed = false;
        for (int i = 0; i < contents.length; i++) {
            ItemStack item = contents[i];
            if (item == null || !item.hasItemMeta()) continue;
            ItemMeta meta = item.getItemMeta();
            if (!meta.getPersistentDataContainer().has(depositMarker, PersistentDataType.STRING)) continue;
            meta.getPersistentDataContainer().remove(depositMarker);
            item.setItemMeta(meta);
            contents[i] = item;
            changed = true;
        }
        if (changed) player.getInventory().setContents(contents);
    }


    private boolean writeMarker(ItemStack item, UUID operationId) {
        try {
            ItemMeta meta = item.getItemMeta();
            if (meta == null) return false;
            meta.getPersistentDataContainer().set(depositMarker, PersistentDataType.STRING, operationId.toString());
            item.setItemMeta(meta);
            return true;
        } catch (RuntimeException exception) {
            plugin.getLogger().warning("No se pudo escribir el marcador de depósito.");
            return false;
        }
    }


    @EventHandler
    public void onQuit(org.bukkit.event.player.PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        lockedPlayers.remove(uuid);
        recoveryPlayers.remove(uuid);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (lockedPlayers.contains(event.getWhoClicked().getUniqueId())) event.setCancelled(true);
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        if (lockedPlayers.contains(event.getWhoClicked().getUniqueId())) event.setCancelled(true);
    }

    @EventHandler
    public void onDrop(PlayerDropItemEvent event) {
        if (lockedPlayers.contains(event.getPlayer().getUniqueId())) event.setCancelled(true);
    }

    @EventHandler
    public void onSwap(PlayerSwapHandItemsEvent event) {
        if (lockedPlayers.contains(event.getPlayer().getUniqueId())) event.setCancelled(true);
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (lockedPlayers.contains(event.getPlayer().getUniqueId())) event.setCancelled(true);
    }

    @EventHandler
    public void onPickup(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player player && lockedPlayers.contains(player.getUniqueId())) {
            event.setCancelled(true);
        }
    }
}
