package com.escuadronsalchichon.hardcore.trade;

import com.escuadronsalchichon.hardcore.SalchichonHardcore;
import com.escuadronsalchichon.hardcore.database.PlayerDataStore;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-authoritative, item-only trade workflow.
 * Inventory mutations are performed on the Paper main thread; durable state
 * transitions are performed asynchronously before/after those mutations.
 */
public final class TradeService implements Listener {
    private final SalchichonHardcore plugin;
    private final PlayerDataStore store;
    private final NamespacedKey escrowMarker;
    private final NamespacedKey deliveryMarker;
    private final Set<UUID> inventoryLocks = ConcurrentHashMap.newKeySet();
    private final Set<UUID> processingTrades = ConcurrentHashMap.newKeySet();
    private final java.util.concurrent.ConcurrentMap<UUID, Set<UUID>> tradeParticipants = new ConcurrentHashMap<>();

    public TradeService(SalchichonHardcore plugin, PlayerDataStore store) {
        this.plugin = plugin;
        this.store = store;
        this.escrowMarker = new NamespacedKey(plugin, "trade-escrow-operation");
        this.deliveryMarker = new NamespacedKey(plugin, "trade-delivery-operation");
    }

    public void createTrade(Player a, Player b) {
        if (a.getUniqueId().equals(b.getUniqueId())) {
            a.sendMessage("§cNo puedes intercambiar contigo mismo.");
            return;
        }
        UUID tradeId = UUID.randomUUID();
        UUID aId = a.getUniqueId();
        UUID bId = b.getUniqueId();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            TradeSessionRecord aTrade = store.getActiveTrade(aId);
            TradeSessionRecord bTrade = store.getActiveTrade(bId);
            boolean created = aTrade == null && bTrade == null && store.createTradeSession(tradeId, aId, bId);
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!a.isOnline() || !b.isOnline()) return;
                if (!created) {
                    a.sendMessage("§cUno de los dos ya participa en un intercambio o no se pudo crear la sesión.");
                    b.sendMessage("§cNo se pudo iniciar el intercambio.");
                    return;
                }
                tradeParticipants.put(tradeId, Set.of(aId, bId));
                a.sendMessage("§dIntercambio iniciado con §f" + b.getName() + "§d. Usa §f/trade offer §dpara ofrecer el objeto de tu mano.");
                b.sendMessage("§dIntercambio iniciado con §f" + a.getName() + "§d. Usa §f/trade offer §dpara ofrecer objetos.");
                sendStatus(a, tradeId);
                sendStatus(b, tradeId);
            });
        });
    }

    public void offer(Player player) {
        if (!plugin.getConfig().getBoolean("trade.enabled", true)) {
            player.sendMessage("§cLos intercambios están desactivados por configuración.");
            return;
        }
        UUID playerId = player.getUniqueId();
        if (!inventoryLocks.add(playerId)) {
            player.sendMessage("§eTu inventario está ocupado por otra operación.");
            return;
        }
        ItemStack original = player.getInventory().getItemInMainHand();
        if (original.getType().isAir()) {
            inventoryLocks.remove(playerId);
            player.sendMessage("§cSostén en la mano el objeto que deseas ofrecer.");
            return;
        }
        if (hasMarker(original)) {
            inventoryLocks.remove(playerId);
            player.sendMessage("§cEse objeto tiene un marcador de operación y no puede volver a ofrecerse.");
            return;
        }
        ItemStack payloadItem = original.clone();
        player.sendMessage("§8Preparando objeto para escrow...");
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            TradeSessionRecord session = store.getActiveTrade(playerId);
            if (session == null || session.status() != TradeStatus.OPEN || !session.contains(playerId)) {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    inventoryLocks.remove(playerId);
                    player.sendMessage("§cNo tienes un intercambio abierto.");
                });
                return;
            }
            UUID operationId = UUID.randomUUID();
            UUID recipient = session.other(playerId);
            TradeEscrowItem record = new TradeEscrowItem(operationId, session.tradeId(),
                    playerId, recipient, payloadItem.serializeAsBytes(),
                    TradeEscrowItem.fingerprint(payloadItem.serializeAsBytes()));
            boolean prepared = store.prepareTradeEscrowItem(record);
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!player.isOnline()) {
                    if (prepared) quarantine(session.tradeId(), session.playerA(), session.playerB(),
                            "El jugador salió durante la preparación del escrow.");
                    return;
                }
                if (!prepared) {
                    inventoryLocks.remove(playerId);
                    player.sendMessage("§cNo se pudo preparar el escrow. El objeto no fue retirado.");
                    return;
                }
                ItemStack current = player.getInventory().getItemInMainHand();
                if (current == null || !current.isSimilar(payloadItem) || current.getAmount() != payloadItem.getAmount()) {
                    quarantine(session.tradeId(), session.playerA(), session.playerB(),
                            "El objeto ofrecido cambió durante la preparación.");
                    return;
                }
                ItemStack marked = current.clone();
                if (!writeMarker(marked, escrowMarker, operationId)) {
                    quarantine(session.tradeId(), session.playerA(), session.playerB(),
                            "No se pudo escribir el marcador de escrow.");
                    return;
                }
                player.getInventory().setItemInMainHand(marked);
                if (!savePlayer(player)) {
                    quarantine(session.tradeId(), session.playerA(), session.playerB(),
                            "No se pudo guardar el marcador de escrow.");
                    return;
                }
                ItemStack check = player.getInventory().getItemInMainHand();
                if (!hasMarker(check, escrowMarker, operationId)) {
                    quarantine(session.tradeId(), session.playerA(), session.playerB(),
                            "El marcador de escrow no aparece en el inventario.");
                    return;
                }
                player.getInventory().setItemInMainHand(null);
                if (!savePlayer(player)) {
                    quarantine(session.tradeId(), session.playerA(), session.playerB(),
                            "No se pudo guardar la retirada del objeto.");
                    return;
                }
                Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                    boolean stored = store.markTradeEscrowed(operationId);
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        if (!stored) {
                            quarantine(session.tradeId(), session.playerA(), session.playerB(),
                                    "No se pudo confirmar el objeto en escrow.");
                            return;
                        }
                        inventoryLocks.remove(playerId);
                        player.sendMessage("§aObjeto ofrecido correctamente. §7La oferta cambió; ambos deben confirmar de nuevo.");
                        Player other = Bukkit.getPlayer(recipient);
                        if (other != null) other.sendMessage("§d" + player.getName() + " §7añadió un objeto a la oferta.");
                        sendStatus(player, session.tradeId());
                        if (other != null) sendStatus(other, session.tradeId());
                    });
                });
            });
        });
    }

    public void confirm(Player player) {
        confirm(player, -1);
    }

    public void confirm(Player player, long expectedRevision) {
        UUID playerId = player.getUniqueId();
        player.sendMessage("§8Registrando tu confirmación...");
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            TradeSessionRecord session = store.getActiveTrade(playerId);
            boolean confirmed = session != null && session.status() == TradeStatus.OPEN
                    && (expectedRevision < 0 || session.revision() == expectedRevision)
                    && store.confirmTradeRevision(session.tradeId(), playerId, session.revision());
            TradeSessionRecord updated = confirmed ? store.getActiveTrade(playerId) : null;
            List<TradeEscrowRecord> offered = updated == null ? List.of() : store.getTradeItems(updated.tradeId());
            boolean hasEscrowedItems = offered.stream().anyMatch(item -> "ESCROWED".equals(item.status()));
            boolean allItemsReady = offered.stream().allMatch(item ->
                    "ESCROWED".equals(item.status()) || "RETURNED".equals(item.status()));
            boolean locked = updated != null && updated.bothConfirmed() && hasEscrowedItems && allItemsReady
                    && store.lockTradeForSettlement(updated.tradeId(), updated.revision());
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!player.isOnline()) return;
                if (!confirmed) {
                    player.sendMessage("§cNo se pudo confirmar: no hay una oferta abierta o esta cambió.");
                    return;
                }
                if (!locked) {
                    if (updated != null && updated.bothConfirmed() && !hasEscrowedItems) {
                        player.sendMessage("§eAmbos confirmaron, pero no hay objetos disponibles en escrow. Ofrece un objeto antes de confirmar.");
                        return;
                    }
                    if (updated != null && updated.bothConfirmed() && !allItemsReady) {
                        player.sendMessage("§eEspera a que terminen de procesarse las devoluciones antes de confirmar.");
                        return;
                    }
                    player.sendMessage("§aTu confirmación quedó registrada. §7Esperando a la otra persona.");
                    Player other = Bukkit.getPlayer(session.other(playerId));
                    if (other != null) other.sendMessage("§d" + player.getName() + " §7confirmó la oferta actual.");
                    return;
                }
                player.sendMessage("§eAmbos confirmaron. El intercambio quedó bloqueado y comenzó la entrega segura.");
                resumeTrade(updated.tradeId());
            });
        });
    }

    public void cancel(Player player) {
        UUID playerId = player.getUniqueId();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            TradeSessionRecord session = store.getActiveTrade(playerId);
            List<TradeEscrowRecord> items = session == null ? List.of() : store.getTradeItems(session.tradeId());
            boolean allReturned = items.stream().allMatch(item -> "RETURNED".equals(item.status()));
            boolean cancelled = session != null && session.status() == TradeStatus.OPEN
                    && allReturned && store.cancelEmptyTrade(session.tradeId(), playerId);
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!player.isOnline()) return;
                if (!cancelled) {
                    player.sendMessage("§cSolo puedes cancelar un intercambio abierto que todavía no tenga objetos ofrecidos.");
                    return;
                }
                tradeParticipants.remove(session.tradeId());
                inventoryLocks.remove(session.playerA());
                inventoryLocks.remove(session.playerB());
                player.sendMessage("§7Intercambio cancelado.");
                Player other = Bukkit.getPlayer(session.other(playerId));
                if (other != null) other.sendMessage("§7El intercambio fue cancelado.");
            });
        });
    }

    public void returnOffer(Player player, UUID operationId, long expectedRevision) {
        UUID playerId = player.getUniqueId();
        if (!inventoryLocks.add(playerId)) {
            player.sendMessage("§eTu inventario está ocupado por otra operación.");
            return;
        }
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            TradeSessionRecord session = store.getActiveTrade(playerId);
            List<TradeEscrowRecord> items = session == null ? List.of() : store.getTradeItems(session.tradeId());
            TradeEscrowRecord selected = items.stream()
                    .filter(item -> item.operationId().equals(operationId)).findFirst().orElse(null);
            boolean requested = session != null && session.status() == TradeStatus.OPEN
                    && session.revision() == expectedRevision && selected != null
                    && selected.ownerUuid().equals(playerId) && "ESCROWED".equals(selected.status())
                    && store.requestTradeItemReturn(operationId, playerId, expectedRevision);
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!player.isOnline()) return;
                if (!requested) {
                    inventoryLocks.remove(playerId);
                    player.sendMessage("§cNo se pudo retirar el objeto: la oferta cambió o el objeto ya no está disponible.");
                    return;
                }
                player.closeInventory();
                player.sendMessage("§8Devolviendo el objeto a tu inventario...");
                resumeTrade(session.tradeId());
            });
        });
    }

    public void openMenu(Player player, int page) {
        UUID playerId = player.getUniqueId();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            TradeSessionRecord session = store.getActiveTrade(playerId);
            List<TradeEscrowRecord> items = session == null ? List.of() : store.getTradeItems(session.tradeId());
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!player.isOnline()) return;
                if (session == null) {
                    player.sendMessage("§7No tienes un intercambio activo. Usa §f/trade <jugador>§7 para invitar a alguien.");
                    return;
                }
                tradeParticipants.put(session.tradeId(), Set.of(session.playerA(), session.playerB()));
                if (session.status() == TradeStatus.RECOVERY_REQUIRED) {
                    inventoryLocks.add(session.playerA());
                    inventoryLocks.add(session.playerB());
                    player.sendMessage("§cEste intercambio requiere revisión manual; no muevas objetos marcados.");
                    return;
                }
                player.openInventory(TradeMenu.create(playerId, session, items, page));
                if (session.status() == TradeStatus.LOCKED
                        || items.stream().anyMatch(item -> "PREPARED".equals(item.status())
                        || "RETURN_PENDING".equals(item.status()))) {
                    resumeTrade(session.tradeId());
                }
            });
        });
    }

    public void status(Player player) {
        UUID playerId = player.getUniqueId();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            TradeSessionRecord session = store.getActiveTrade(playerId);
            List<TradeEscrowRecord> items = session == null ? List.of() : store.getTradeItems(session.tradeId());
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!player.isOnline()) return;
                if (session == null) {
                    player.sendMessage("§7No tienes un intercambio activo.");
                    return;
                }
                tradeParticipants.put(session.tradeId(), Set.of(session.playerA(), session.playerB()));
                player.sendMessage("§d§lIntercambio");
                player.sendMessage("§7Estado: §f" + session.status() + " §8| §7Revisión: §f" + session.revision());
                player.sendMessage("§7Participante: §f" + Bukkit.getOfflinePlayer(session.other(playerId)).getName());
                player.sendMessage("§7Objetos ofrecidos: §f" + items.size());
                player.sendMessage("§7Tu confirmación: " + (session.isConfirmed(playerId) ? "§aSí" : "§cNo"));
                player.sendMessage("§7Otra confirmación: " + (session.isConfirmed(session.other(playerId)) ? "§aSí" : "§cNo"));
                player.sendMessage("§8Usa /trade offer para añadir el objeto de tu mano y /trade confirm para aceptar.");
                if (session.status() == TradeStatus.LOCKED) resumeTrade(session.tradeId());
                if (session.status() == TradeStatus.RECOVERY_REQUIRED) {
                    inventoryLocks.add(playerId);
                    player.sendMessage("§cEste intercambio está aislado para revisión; no muevas posibles objetos marcados.");
                }
            });
        });
    }

    private void resumeTrade(UUID tradeId) {
        if (!processingTrades.add(tradeId)) return;
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            List<TradeEscrowRecord> items = store.getTradeItems(tradeId);
            Bukkit.getScheduler().runTask(plugin, () -> processItems(tradeId, items, 0));
        });
    }

    private void processItems(UUID tradeId, List<TradeEscrowRecord> items, int index) {
        if (index >= items.size()) {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                boolean complete = store.completeTrade(tradeId);
                Bukkit.getScheduler().runTask(plugin, () -> {
                    processingTrades.remove(tradeId);
                    if (complete) {
                        Set<UUID> participants = new java.util.HashSet<>();
                        for (TradeEscrowRecord record : items) {
                            participants.add(record.ownerUuid());
                            participants.add(record.recipientUuid());
                        }
                        tradeParticipants.remove(tradeId);
                        for (UUID participant : participants) {
                            inventoryLocks.remove(participant);
                            Player online = Bukkit.getPlayer(participant);
                            if (online != null) online.sendMessage("§a§l¡Intercambio completado!");
                        }
                        plugin.getLogger().info("Intercambio completado: " + tradeId);
                    }
                });
            });
            return;
        }
        TradeEscrowRecord item = items.get(index);
        if ("ESCROWED".equals(item.status())) {
            processItems(tradeId, items, index + 1);
            return;
        }
        if ("RETURNED".equals(item.status())) {
            Player owner = Bukkit.getPlayer(item.ownerUuid());
            if (owner != null && hasOperationMarker(owner, deliveryMarker, item.operationId())) {
                ItemStack marked = findOperationMarkedStack(owner, deliveryMarker, item.operationId());
                if (marked == null || !matchesPayload(marked, item.payload(), deliveryMarker)
                        || !stripOperationMarker(owner, deliveryMarker, item.operationId()) || !savePlayer(owner)) {
                    quarantine(tradeId, null, null, "La devolución confirmada no coincide con su payload.");
                    return;
                }
                inventoryLocks.remove(owner.getUniqueId());
            }
            processItems(tradeId, items, index + 1);
            return;
        }
        if ("DELIVERED".equals(item.status())) {
            Player recipient = Bukkit.getPlayer(item.recipientUuid());
            if (recipient != null && hasOperationMarker(recipient, deliveryMarker, item.operationId())) {
                ItemStack marked = findOperationMarkedStack(recipient, deliveryMarker, item.operationId());
                if (marked == null || !matchesPayload(marked, item.payload(), deliveryMarker)) {
                    quarantine(tradeId, null, null, "El objeto entregado ya no coincide con el payload confirmado.");
                    return;
                }
                if (!stripOperationMarker(recipient, deliveryMarker, item.operationId()) || !savePlayer(recipient)) {
                    quarantine(tradeId, null, null, "No se pudo limpiar el marcador de una entrega confirmada.");
                    return;
                }
                inventoryLocks.remove(recipient.getUniqueId());
            }
            processItems(tradeId, items, index + 1);
            return;
        }
        if ("RETURN_PENDING".equals(item.status())) {
            Player owner = Bukkit.getPlayer(item.ownerUuid());
            if (owner == null) {
                processItems(tradeId, items, index + 1);
                return;
            }
            ItemStack marked = findOperationMarkedStack(owner, deliveryMarker, item.operationId());
            if (marked != null) {
                if (!matchesPayload(marked, item.payload(), deliveryMarker)) {
                    quarantine(tradeId, null, null, "El objeto de devolución marcado no coincide.");
                    return;
                }
                acknowledgeReturnedItem(tradeId, item, owner, index, items);
            } else {
                deliverReturn(tradeId, item, owner, index, items);
            }
            return;
        }
        if ("RECOVERY_REQUIRED".equals(item.status())) {
            quarantine(tradeId, null, null, "El escrow contiene una operación que requiere revisión.");
            return;
        }
        if ("PREPARED".equals(item.status())) {
            Player owner = Bukkit.getPlayer(item.ownerUuid());
            if (owner == null) {
                processingTrades.remove(tradeId);
                return;
            }
            if (!hasOperationMarker(owner, escrowMarker, item.operationId())) {
                quarantine(tradeId, null, null, "No existe evidencia del marcador de escrow preparado.");
                return;
            }
            ItemStack markedEscrow = findOperationMarkedStack(owner, escrowMarker, item.operationId());
            if (markedEscrow == null || !matchesPayload(markedEscrow, item.payload(), escrowMarker)) {
                quarantine(tradeId, null, null, "El objeto marcado no coincide con el payload del escrow.");
                return;
            }
            removeOperationMarkeredStack(owner, escrowMarker, item.operationId());
            if (!savePlayer(owner)) {
                quarantine(tradeId, null, null, "No se pudo guardar la recuperación del escrow.");
                return;
            }
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                boolean marked = store.markTradeEscrowed(item.operationId());
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (!marked) quarantine(tradeId, null, null, "No se pudo confirmar la recuperación del escrow.");
                    else {
                        inventoryLocks.remove(owner.getUniqueId());
                        reloadAndProcess(tradeId);
                    }
                });
            });
            return;
        }
        if ("DELIVERING".equals(item.status())) {
            Player recipient = Bukkit.getPlayer(item.recipientUuid());
            if (recipient == null) {
                processingTrades.remove(tradeId);
                return;
            }
            ItemStack marked = findOperationMarkedStack(recipient, deliveryMarker, item.operationId());
            if (marked == null || !matchesPayload(marked, item.payload(), deliveryMarker)) {
                quarantine(tradeId, null, null, "Una entrega interrumpida no tiene evidencia íntegra.");
                return;
            }
            acknowledgeRecoveredDelivery(tradeId, item, recipient, index, items);
            return;
        }
        if ("DELIVERY_PENDING".equals(item.status())) {
            Player recipient = Bukkit.getPlayer(item.recipientUuid());
            if (recipient == null) {
                processItems(tradeId, items, index + 1);
                return;
            }
            deliver(tradeId, item, recipient, index, items);
            return;
        }
        quarantine(tradeId, null, null, "Estado de escrow desconocido: " + item.status());
    }

    private void deliverReturn(UUID tradeId, TradeEscrowRecord item, Player owner,
                               int index, List<TradeEscrowRecord> allItems) {
        ItemStack payload;
        try {
            payload = ItemStack.deserializeBytes(item.payload());
        } catch (RuntimeException invalid) {
            quarantine(tradeId, null, null, "El objeto de devolución no se puede deserializar.");
            return;
        }
        if (payload == null || payload.getType().isAir()
                || !TradeEscrowItem.fingerprint(payload.serializeAsBytes()).equals(item.fingerprint())) {
            quarantine(tradeId, null, null, "La huella del objeto de devolución no coincide.");
            return;
        }
        inventoryLocks.add(owner.getUniqueId());
        if (!canFit(owner, payload)) {
            inventoryLocks.remove(owner.getUniqueId());
            owner.sendMessage("§eTu inventario está lleno. Libera espacio y usa /trade status para recuperar tu oferta.");
            processItems(tradeId, allItems, index + 1);
            return;
        }
        ItemStack marked = payload.clone();
        if (!writeMarker(marked, deliveryMarker, item.operationId())) {
            quarantine(tradeId, null, null, "No se pudo marcar el objeto de devolución.");
            return;
        }
        var leftovers = owner.getInventory().addItem(marked);
        if (!leftovers.isEmpty() || !savePlayer(owner)) {
            quarantine(tradeId, null, null, "No se pudo guardar la devolución de forma concluyente.");
            return;
        }
        acknowledgeReturnedItem(tradeId, item, owner, index, allItems);
    }

    private void acknowledgeReturnedItem(UUID tradeId, TradeEscrowRecord item, Player owner,
                                         int index, List<TradeEscrowRecord> allItems) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            boolean acknowledged = store.markTradeItemReturned(item.operationId(), owner.getUniqueId());
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!acknowledged) {
                    quarantine(tradeId, null, null, "No se pudo confirmar la devolución.");
                    return;
                }
                if (!owner.isOnline()) {
                    processingTrades.remove(tradeId);
                    return;
                }
                if (!stripOperationMarker(owner, deliveryMarker, item.operationId()) || !savePlayer(owner)) {
                    quarantine(tradeId, null, null, "No se pudo limpiar el marcador de devolución.");
                    return;
                }
                inventoryLocks.remove(owner.getUniqueId());
                owner.sendMessage("§aTu objeto fue retirado de la oferta y devuelto.");
                reloadAndProcess(tradeId);
            });
        });
    }

    private void deliver(UUID tradeId, TradeEscrowRecord item, Player recipient,
                         int index, List<TradeEscrowRecord> allItems) {
        ItemStack payload;
        try {
            payload = ItemStack.deserializeBytes(item.payload());
        } catch (RuntimeException invalid) {
            quarantine(tradeId, null, null, "El objeto almacenado no se puede deserializar.");
            return;
        }
        if (payload == null || payload.getType().isAir()
                || !TradeEscrowItem.fingerprint(payload.serializeAsBytes()).equals(item.fingerprint())) {
            quarantine(tradeId, null, null, "La huella del objeto almacenado no coincide.");
            return;
        }
        inventoryLocks.add(recipient.getUniqueId());
        if (!canFit(recipient, payload)) {
            inventoryLocks.remove(recipient.getUniqueId());
            recipient.sendMessage("§eTu inventario está lleno. Libera espacio y usa /trade status para reanudar la entrega.");
            processItems(tradeId, allItems, index + 1);
            return;
        }
        ItemStack marked = payload.clone();
        if (!writeMarker(marked, deliveryMarker, item.operationId())) {
            quarantine(tradeId, null, null, "No se pudo escribir el marcador de entrega.");
            return;
        }
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            boolean started = store.markTradeItemDelivering(item.operationId(), recipient.getUniqueId());
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!started) {
                    quarantine(tradeId, null, null, "No se pudo iniciar la entrega durable.");
                    return;
                }
                if (!recipient.isOnline()) {
                    quarantine(tradeId, null, null, "El destinatario salió antes de recibir el objeto.");
                    return;
                }
                var leftovers = recipient.getInventory().addItem(marked);
                if (!leftovers.isEmpty() || !savePlayer(recipient)) {
                    quarantine(tradeId, null, null, "La entrega no pudo guardarse de forma concluyente.");
                    return;
                }
                Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                    boolean acknowledged = store.markTradeItemDelivered(item.operationId(), recipient.getUniqueId());
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        if (!acknowledged) {
                            quarantine(tradeId, null, null, "El objeto está marcado pero falta el acuse durable.");
                            return;
                        }
                        if (!recipient.isOnline()) {
                            processItems(tradeId, allItems, index + 1);
                            return;
                        }
                        if (!stripOperationMarker(recipient, deliveryMarker, item.operationId()) || !savePlayer(recipient)) {
                            quarantine(tradeId, null, null, "No se pudo limpiar el marcador tras confirmar la entrega.");
                            return;
                        }
                        inventoryLocks.remove(recipient.getUniqueId());
                        recipient.sendMessage("§aRecibiste un objeto del intercambio.");
                        processItems(tradeId, allItems, index + 1);
                    });
                });
            });
        });
    }

    private void acknowledgeRecoveredDelivery(UUID tradeId, TradeEscrowRecord item, Player recipient,
                                              int index, List<TradeEscrowRecord> allItems) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            boolean acknowledged = store.markTradeItemDelivered(item.operationId(), recipient.getUniqueId());
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!acknowledged) {
                    quarantine(tradeId, null, null, "No se pudo confirmar la entrega recuperada.");
                    return;
                }
                if (!recipient.isOnline()) {
                    processingTrades.remove(tradeId);
                    return;
                }
                if (!stripOperationMarker(recipient, deliveryMarker, item.operationId()) || !savePlayer(recipient)) {
                    quarantine(tradeId, null, null, "No se pudo cerrar una entrega recuperada.");
                    return;
                }
                inventoryLocks.remove(recipient.getUniqueId());
                processItems(tradeId, allItems, index + 1);
            });
        });
    }

    private void quarantine(UUID tradeId, UUID aId, UUID bId, String reason) {
        Set<UUID> knownParticipants = tradeParticipants.get(tradeId);
        if (aId == null && bId == null && knownParticipants != null) {
            for (UUID participant : knownParticipants) inventoryLocks.add(participant);
        }
        if (aId != null) inventoryLocks.add(aId);
        if (bId != null) inventoryLocks.add(bId);
        List<UUID> onlineIds = aId == null && bId == null && knownParticipants == null
                ? Bukkit.getOnlinePlayers().stream().map(Player::getUniqueId).toList()
                : List.of();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            boolean isolated = store.markTradeRecoveryRequired(tradeId);
            TradeSessionRecord active = null;
            if (aId == null && bId == null) {
                for (UUID onlineId : onlineIds) {
                    TradeSessionRecord candidate = store.getActiveTrade(onlineId);
                    if (candidate != null && candidate.tradeId().equals(tradeId)) { active = candidate; break; }
                }
            }
            TradeSessionRecord recovered = active;
            Bukkit.getScheduler().runTask(plugin, () -> {
                processingTrades.remove(tradeId);
                if (aId != null) {
                    Player a = Bukkit.getPlayer(aId);
                    if (a != null) a.sendMessage("§cEl intercambio fue aislado para recuperación: " + reason);
                }
                if (bId != null) {
                    Player b = Bukkit.getPlayer(bId);
                    if (b != null) b.sendMessage("§cEl intercambio fue aislado para recuperación: " + reason);
                }
                Set<UUID> participants = recovered != null
                        ? Set.of(recovered.playerA(), recovered.playerB())
                        : knownParticipants;
                if (participants != null) {
                    tradeParticipants.put(tradeId, participants);
                    for (UUID participant : participants) {
                        Player online = Bukkit.getPlayer(participant);
                        if (online != null) {
                            inventoryLocks.add(participant);
                            online.sendMessage("§cEl intercambio requiere revisión manual. No muevas objetos marcados: " + reason);
                        }
                    }
                }
                plugin.getLogger().warning("Trade " + tradeId + " aislado (" + isolated + "): " + reason);
            });
        });
    }

    private void reloadAndProcess(UUID tradeId) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            List<TradeEscrowRecord> items = store.getTradeItems(tradeId);
            Bukkit.getScheduler().runTask(plugin, () -> processItems(tradeId, items, 0));
        });
    }

    private void sendStatus(Player player, UUID tradeId) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            TradeSessionRecord session = store.getActiveTrade(player.getUniqueId());
            List<TradeEscrowRecord> items = store.getTradeItems(tradeId);
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (session == null || !player.isOnline()) return;
                player.sendMessage("§7Revisión " + session.revision() + " · objetos en escrow: " + items.size());
            });
        });
    }

    private boolean canFit(Player player, ItemStack item) {
        try {
            Inventory simulated = Bukkit.createInventory(null, 36);
            ItemStack[] contents = player.getInventory().getStorageContents();
            ItemStack[] copy = new ItemStack[contents.length];
            for (int i = 0; i < contents.length; i++) copy[i] = contents[i] == null ? null : contents[i].clone();
            simulated.setStorageContents(copy);
            return simulated.addItem(item.clone()).isEmpty();
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private boolean savePlayer(Player player) {
        try {
            player.saveData();
            return true;
        } catch (RuntimeException exception) {
            plugin.getLogger().warning("No se pudo guardar el inventario de " + player.getName() + ".");
            return false;
        }
    }

    private boolean writeMarker(ItemStack item, NamespacedKey key, UUID operationId) {
        try {
            ItemMeta meta = item.getItemMeta();
            if (meta == null) return false;
            meta.getPersistentDataContainer().set(key, PersistentDataType.STRING, operationId.toString());
            item.setItemMeta(meta);
            return true;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private boolean hasMarker(ItemStack item) {
        return hasMarker(item, escrowMarker) || hasMarker(item, deliveryMarker);
    }

    private boolean hasMarker(ItemStack item, NamespacedKey key) {
        return item != null && item.hasItemMeta()
                && item.getItemMeta().getPersistentDataContainer().has(key, PersistentDataType.STRING);
    }

    private boolean hasMarker(ItemStack item, NamespacedKey key, UUID operationId) {
        return item != null && item.hasItemMeta()
                && operationId.toString().equals(item.getItemMeta().getPersistentDataContainer()
                .get(key, PersistentDataType.STRING));
    }

    private boolean hasOperationMarker(Player player, NamespacedKey key, UUID operationId) {
        return findOperationMarkedStack(player, key, operationId) != null;
    }

    private ItemStack findOperationMarkedStack(Player player, NamespacedKey key, UUID operationId) {
        ItemStack found = null;
        for (ItemStack item : player.getInventory().getContents()) {
            if (!hasMarker(item, key, operationId)) continue;
            if (found != null) return null;
            found = item;
        }
        return found;
    }

    private boolean stripOperationMarker(Player player, NamespacedKey key, UUID operationId) {
        ItemStack[] contents = player.getInventory().getContents();
        int matches = 0;
        for (int i = 0; i < contents.length; i++) {
            ItemStack item = contents[i];
            if (!hasMarker(item, key, operationId)) continue;
            matches++;
            ItemMeta meta = item.getItemMeta();
            meta.getPersistentDataContainer().remove(key);
            item.setItemMeta(meta);
            contents[i] = item;
        }
        if (matches != 1) return false;
        player.getInventory().setContents(contents);
        return true;
    }

    private void removeOperationMarkeredStack(Player player, NamespacedKey key, UUID operationId) {
        ItemStack[] contents = player.getInventory().getContents();
        for (int i = 0; i < contents.length; i++) {
            if (hasMarker(contents[i], key, operationId)) contents[i] = null;
        }
        player.getInventory().setContents(contents);
    }

    private boolean matchesPayload(ItemStack marked, byte[] payload, NamespacedKey key) {
        try {
            ItemStack copy = marked.clone();
            ItemMeta meta = copy.getItemMeta();
            meta.getPersistentDataContainer().remove(key);
            copy.setItemMeta(meta);
            return TradeEscrowItem.fingerprint(copy.serializeAsBytes())
                    .equals(TradeEscrowItem.fingerprint(payload));
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private boolean hasAnyTradeMarker(Player player) {
        for (ItemStack item : player.getInventory().getContents()) {
            if (hasMarker(item, escrowMarker) || hasMarker(item, deliveryMarker)) return true;
        }
        return false;
    }

    private boolean hasExpectedMarker(Player player, List<TradeEscrowRecord> items) {
        UUID playerId = player.getUniqueId();
        Set<String> expected = new java.util.HashSet<>();
        for (TradeEscrowRecord item : items) {
            if (item.ownerUuid().equals(playerId) && "PREPARED".equals(item.status())) {
                ItemStack marked = findOperationMarkedStack(player, escrowMarker, item.operationId());
                if (marked != null) {
                    if (!matchesPayload(marked, item.payload(), escrowMarker)) return false;
                    expected.add(item.operationId().toString());
                }
            }
            if (item.ownerUuid().equals(playerId)
                    && ("RETURN_PENDING".equals(item.status()) || "RETURNED".equals(item.status()))) {
                ItemStack marked = findOperationMarkedStack(player, deliveryMarker, item.operationId());
                if (marked != null) {
                    if (!matchesPayload(marked, item.payload(), deliveryMarker)) return false;
                    expected.add(item.operationId().toString());
                }
            }
            if (item.recipientUuid().equals(playerId)
                    && ("DELIVERING".equals(item.status()) || "DELIVERED".equals(item.status()))) {
                ItemStack marked = findOperationMarkedStack(player, deliveryMarker, item.operationId());
                if (marked != null) {
                    if (!matchesPayload(marked, item.payload(), deliveryMarker)) return false;
                    expected.add(item.operationId().toString());
                }
            }
        }
        boolean found = false;
        for (ItemStack item : player.getInventory().getContents()) {
            for (NamespacedKey key : List.of(escrowMarker, deliveryMarker)) {
                if (!hasMarker(item, key)) continue;
                found = true;
                String operation = item.getItemMeta().getPersistentDataContainer().get(key, PersistentDataType.STRING);
                if (operation == null || !expected.contains(operation)) return false;
            }
        }
        return found && !expected.isEmpty();
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();
        boolean markerPresentAtJoin = hasAnyTradeMarker(player);
        if (markerPresentAtJoin) inventoryLocks.add(playerId);
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            TradeSessionRecord session = store.getActiveTrade(playerId);
            if (session == null) {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (markerPresentAtJoin && player.isOnline()) {
                        inventoryLocks.add(playerId);
                        player.sendMessage("§cHay un marcador de intercambio sin sesión activa. Tu inventario queda bloqueado para revisión manual.");
                    }
                });
                return;
            }
            List<TradeEscrowRecord> items = store.getTradeItems(session.tradeId());
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!player.isOnline()) return;
                tradeParticipants.put(session.tradeId(), Set.of(session.playerA(), session.playerB()));
                if (session.status() == TradeStatus.RECOVERY_REQUIRED) {
                    inventoryLocks.add(session.playerA());
                    inventoryLocks.add(session.playerB());
                    player.sendMessage("§cTienes un intercambio aislado para revisión manual. No muevas objetos marcados.");
                    return;
                }
                if (markerPresentAtJoin && !hasExpectedMarker(player, items)) {
                    quarantine(session.tradeId(), session.playerA(), session.playerB(),
                            "Se encontró un marcador que no coincide con una operación no terminal.");
                    return;
                }
                for (TradeEscrowRecord item : items) {
                    if (item.status().equals("RETURN_PENDING") && item.ownerUuid().equals(playerId)) {
                    ItemStack marked = findOperationMarkedStack(player, deliveryMarker, item.operationId());
                    if (marked != null && !matchesPayload(marked, item.payload(), deliveryMarker)) {
                        quarantine(session.tradeId(), session.playerA(), session.playerB(),
                                "Devolución interrumpida con marcador inválido al reconectar.");
                        return;
                    }
                }
                if (item.status().equals("DELIVERING") && item.recipientUuid().equals(playerId)) {
                        ItemStack marked = findOperationMarkedStack(player, deliveryMarker, item.operationId());
                        if (marked == null || !matchesPayload(marked, item.payload(), deliveryMarker)) {
                            quarantine(session.tradeId(), session.playerA(), session.playerB(),
                                    "Entrega interrumpida sin marcador íntegro al reconectar.");
                            return;
                        }
                    }
                    if (item.status().equals("PREPARED") && item.ownerUuid().equals(playerId)
                            && !hasOperationMarker(player, escrowMarker, item.operationId())) {
                        quarantine(session.tradeId(), session.playerA(), session.playerB(),
                                "Depósito a escrow PREPARED sin marcador al reconectar.");
                        return;
                    }
                }
                if (session.status() == TradeStatus.LOCKED) resumeTrade(session.tradeId());
                else if (markerPresentAtJoin || items.stream().anyMatch(item -> item.status().equals("PREPARED")
                        || item.status().equals("RETURN_PENDING"))) resumeTrade(session.tradeId());
            });
        });
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (inventoryLocks.contains(event.getWhoClicked().getUniqueId())) event.setCancelled(true);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (inventoryLocks.contains(event.getWhoClicked().getUniqueId())) event.setCancelled(true);
    }

    @EventHandler
    public void onDrop(PlayerDropItemEvent event) {
        if (inventoryLocks.contains(event.getPlayer().getUniqueId())) event.setCancelled(true);
    }

    @EventHandler
    public void onSwap(PlayerSwapHandItemsEvent event) {
        if (inventoryLocks.contains(event.getPlayer().getUniqueId())) event.setCancelled(true);
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (inventoryLocks.contains(event.getPlayer().getUniqueId())) event.setCancelled(true);
    }

    @EventHandler
    public void onPickup(EntityPickupItemEvent event) {
        if (hasMarker(event.getItem().getItemStack(), escrowMarker)
                || hasMarker(event.getItem().getItemStack(), deliveryMarker)) {
            event.setCancelled(true);
            return;
        }
        if (event.getEntity() instanceof Player player && inventoryLocks.contains(player.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onHeldSlotChange(PlayerItemHeldEvent event) {
        if (inventoryLocks.contains(event.getPlayer().getUniqueId())) event.setCancelled(true);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        inventoryLocks.remove(event.getPlayer().getUniqueId());
    }
}
