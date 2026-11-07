package com.escuadronsalchichon.hardcore.trade;

import com.escuadronsalchichon.hardcore.SalchichonHardcore;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Read-only evidence scanner for trade operation markers.
 *
 * It only inspects online player inventories and item entities in loaded chunks.
 * It cannot prove absence from offline playerdata or unloaded chunks.
 */
public final class TradeEvidenceScanner {
    private final NamespacedKey escrowMarker;
    private final NamespacedKey deliveryMarker;

    public TradeEvidenceScanner(SalchichonHardcore plugin) {
        this.escrowMarker = new NamespacedKey(plugin, "trade-escrow-operation");
        this.deliveryMarker = new NamespacedKey(plugin, "trade-delivery-operation");
    }

    public List<String> scan(List<TradeEscrowRecord> operations) {
        Map<UUID, TradeEscrowRecord> byId = new HashMap<>();
        for (TradeEscrowRecord operation : operations) byId.put(operation.operationId(), operation);

        List<String> findings = new ArrayList<>();
        int[] counts = new int[3]; // matching, mismatching, unrecognized
        // Scan every online player, not only the two participants: a marked item
        // found on an unrelated player is important evidence of unexpected movement.
        for (Player player : Bukkit.getOnlinePlayers()) {
            for (ItemStack stack : player.getInventory().getStorageContents()) {
                inspect(stack, "inventario:" + player.getName(), player.getUniqueId(), byId, findings, counts);
            }
            for (ItemStack stack : player.getInventory().getArmorContents()) {
                inspect(stack, "armadura:" + player.getName(), player.getUniqueId(), byId, findings, counts);
            }
            inspect(player.getInventory().getItemInOffHand(), "mano-secundaria:" + player.getName(),
                    player.getUniqueId(), byId, findings, counts);
            for (ItemStack stack : player.getEnderChest().getContents()) {
                inspect(stack, "ender-chest:" + player.getName(), player.getUniqueId(), byId, findings, counts);
            }
        }

        for (var world : Bukkit.getWorlds()) {
            for (Item dropped : world.getEntitiesByClass(Item.class)) {
                inspect(dropped.getItemStack(), "objeto-en-suelo:" + world.getName(),
                        null, byId, findings, counts);
            }
        }

        findings.add(0, "Marcadores coincidentes: " + counts[0]
                + " | discrepantes: " + counts[1] + " | no reconocidos: " + counts[2]);
        if (findings.size() == 1) findings.add("No se encontraron marcadores en las ubicaciones inspeccionadas.");
        return List.copyOf(findings);
    }

    private void inspect(ItemStack stack, String location, UUID holder,
                         Map<UUID, TradeEscrowRecord> operations,
                         List<String> findings, int[] counts) {
        if (stack == null || stack.getType().isAir() || !stack.hasItemMeta()) return;
        ItemMeta meta = stack.getItemMeta();
        inspectMarker(stack, location, holder, operations,
                meta.getPersistentDataContainer().get(escrowMarker, PersistentDataType.STRING),
                escrowMarker, "ESCROW", findings, counts);
        inspectMarker(stack, location, holder, operations,
                meta.getPersistentDataContainer().get(deliveryMarker, PersistentDataType.STRING),
                deliveryMarker, "DELIVERY", findings, counts);
    }

    private void inspectMarker(ItemStack marked, String location, UUID holder,
                               Map<UUID, TradeEscrowRecord> operations, String rawId,
                               NamespacedKey markerKey, String markerType,
                               List<String> findings, int[] counts) {
        if (rawId == null) return;
        UUID operationId;
        try {
            operationId = UUID.fromString(rawId);
        } catch (IllegalArgumentException invalid) {
            counts[2]++;
            findings.add("NO RECONOCIDO " + markerType + " en " + location + " (UUID inválido)");
            return;
        }
        TradeEscrowRecord operation = operations.get(operationId);
        if (operation == null) {
            counts[2]++;
            findings.add("NO RECONOCIDO " + markerType + " " + shortId(operationId) + " en " + location);
            return;
        }

        boolean expectedState = "ESCROW".equals(markerType)
                ? "PREPARED".equals(operation.status())
                : "DELIVERING".equals(operation.status()) || "DELIVERED".equals(operation.status())
                    || "RETURN_PENDING".equals(operation.status()) || "RETURNED".equals(operation.status());
        boolean expectedHolder = holder == null
                || ("ESCROW".equals(markerType) && operation.ownerUuid().equals(holder))
                || ("DELIVERY".equals(markerType)
                    && (operation.recipientUuid().equals(holder) || operation.ownerUuid().equals(holder)));
        boolean expectedMarker = expectedState && expectedHolder;
        boolean fingerprintMatches = false;
        try {
            ItemStack copy = marked.clone();
            ItemMeta copyMeta = copy.getItemMeta();
            copyMeta.getPersistentDataContainer().remove(markerKey);
            copy.setItemMeta(copyMeta);
            fingerprintMatches = TradeEscrowItem.fingerprint(copy.serializeAsBytes())
                    .equalsIgnoreCase(operation.fingerprint());
        } catch (RuntimeException ignored) {
            // A failed serialization is evidence of a mismatch, never a reason to mutate the item.
        }

        if (expectedMarker && fingerprintMatches) {
            counts[0]++;
            findings.add("COINCIDE " + markerType + " " + shortId(operationId) + " en " + location);
        } else {
            counts[1]++;
            findings.add("DISCREPANCIA " + markerType + " " + shortId(operationId) + " en " + location
                    + " (estado/propietario o huella no coincide)");
        }
    }

    private static String shortId(UUID id) {
        return id.toString().substring(0, 8);
    }
}
