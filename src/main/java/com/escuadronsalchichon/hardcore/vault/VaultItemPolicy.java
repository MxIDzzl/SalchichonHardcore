package com.escuadronsalchichon.hardcore.vault;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import java.util.Map;
import java.util.OptionalLong;

/**
 * Strict allow-list for Player Value deposits.
 *
 * Only plain, unmodified stacks are accepted. The policy deliberately rejects
 * metadata-bearing items until a canonical serialization/fingerprint strategy
 * is implemented and tested.
 */
public final class VaultItemPolicy {
    private final Map<Material, Long> values;
    private final int maxStackSize;

    public VaultItemPolicy(Map<Material, Long> values, int maxStackSize) {
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException("La lista de objetos permitidos no puede estar vacía.");
        }
        if (maxStackSize < 1) {
            throw new IllegalArgumentException("El tamaño máximo debe ser positivo.");
        }
        for (Map.Entry<Material, Long> entry : values.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isAir()
                    || entry.getValue() == null || entry.getValue() < 1) {
                throw new IllegalArgumentException("La lista de objetos contiene una regla inválida.");
            }
        }
        this.values = Map.copyOf(values);
        this.maxStackSize = maxStackSize;
    }

    /**
     * Returns the total score for a stack, or empty when it is not safe to deposit.
     * Uses exact multiplication to reject integer overflow instead of wrapping.
     */
    public OptionalLong creditFor(ItemStack stack) {
        if (stack == null || stack.getType().isAir() || stack.getAmount() < 1
                || stack.getAmount() > maxStackSize || stack.getAmount() > stack.getMaxStackSize()
                || stack.hasItemMeta()) {
            return OptionalLong.empty();
        }
        Long unitValue = values.get(stack.getType());
        if (unitValue == null) return OptionalLong.empty();
        try {
            return OptionalLong.of(Math.multiplyExact(unitValue, (long) stack.getAmount()));
        } catch (ArithmeticException overflow) {
            return OptionalLong.empty();
        }
    }

    public static Material parseMaterial(String name) {
        if (name == null || name.isBlank()) return null;
        Material material = Material.matchMaterial(name.trim());
        return material == null || material.isAir() ? null : material;
    }
}
