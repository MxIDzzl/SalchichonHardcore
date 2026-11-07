package com.escuadronsalchichon.hardcore.vault;

import com.escuadronsalchichon.hardcore.util.PlayerValueFormatter;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class VaultDepositMenu {
    private VaultDepositMenu() {}

    public static Inventory create(Player player, List<VaultDepositCandidate> candidates) {
        VaultDepositMenuHolder holder = new VaultDepositMenuHolder(player.getUniqueId(), candidates);
        Inventory inventory = Bukkit.createInventory(holder, 54, Component.text("Player Value · Depositar"));
        holder.inventory(inventory);

        for (int i = 0; i < candidates.size() && i < 45; i++) {
            VaultDepositCandidate candidate = candidates.get(i);
            ItemStack icon = candidate.item();
            ItemMeta meta = icon.getItemMeta();
            meta.displayName(Component.text(icon.getType().name().toLowerCase(Locale.ROOT).replace('_', ' ')));
            List<Component> lore = new ArrayList<>();
            lore.add(Component.text("Cantidad: " + icon.getAmount()));
            lore.add(Component.text("Valor a recibir: +" + PlayerValueFormatter.compact(candidate.credit())));
            lore.add(Component.text(" "));
            lore.add(Component.text("Clic para depositar esta pila"));
            lore.add(Component.text("El objeto se retira solo al confirmar el registro seguro"));
            meta.lore(lore);
            icon.setItemMeta(meta);
            inventory.setItem(i, icon);
        }

        for (int i = 45; i < 54; i++) inventory.setItem(i, button(Material.GRAY_STAINED_GLASS_PANE, " "));
        inventory.setItem(49, button(Material.NETHER_STAR,
                candidates.isEmpty() ? "No tienes objetos permitidos" : "Selecciona un objeto para depositar"));
        return inventory;
    }

    private static ItemStack button(Material material, String name) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(name));
        item.setItemMeta(meta);
        return item;
    }
}
