package com.escuadronsalchichon.hardcore.vault;

import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalLong;

/**
 * Strict allow-list for Player Value deposits.
 * Metadata-bearing items are rejected until canonical serialization is implemented.
 */
public final class VaultItemPolicy {
    private final Map<String, Long> values;
    private final int maxStackSize;

    public VaultItemPolicy(Map<String, Long> values, int maxStackSize) {
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException("La lista de objetos permitidos no puede estar vacía.");
        }
        if (maxStackSize < 1) {
            throw new IllegalArgumentException("El tamaño máximo debe ser positivo.");
        }
        Map<String, Long> normalized = new HashMap<>();
        for (Map.Entry<String, Long> entry : values.entrySet()) {
            String material = normalize(entry.getKey());
            if (material == null || material.equals("AIR") || entry.getValue() == null || entry.getValue() < 1) {
                throw new IllegalArgumentException("La lista de objetos contiene una regla inválida.");
            }
            normalized.put(material, entry.getValue());
        }
        this.values = Map.copyOf(normalized);
        this.maxStackSize = maxStackSize;
    }

    public OptionalLong creditFor(ItemStack stack) {
        if (stack == null) return OptionalLong.empty();
        return creditFor(stack.getType().name(), stack.getAmount(),
                stack.getMaxStackSize(), stack.hasItemMeta());
    }

    /** Pure validation entry point, also usable in tests without a running Bukkit registry. */
    public OptionalLong creditFor(String material, int amount, int materialMaxStackSize, boolean hasMetadata) {
        String key = normalize(material);
        if (key == null || key.equals("AIR") || amount < 1 || amount > maxStackSize
                || materialMaxStackSize < 1 || amount > materialMaxStackSize || hasMetadata) {
            return OptionalLong.empty();
        }
        Long unitValue = values.get(key);
        if (unitValue == null) return OptionalLong.empty();
        try {
            return OptionalLong.of(Math.multiplyExact(unitValue, (long) amount));
        } catch (ArithmeticException overflow) {
            return OptionalLong.empty();
        }
    }

    private static String normalize(String material) {
        if (material == null || material.isBlank()) return null;
        return material.trim().toUpperCase(Locale.ROOT);
    }
}
