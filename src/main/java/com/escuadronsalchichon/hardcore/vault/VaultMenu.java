package com.escuadronsalchichon.hardcore.vault;

import com.escuadronsalchichon.hardcore.database.VaultItemRecord;
import com.escuadronsalchichon.hardcore.util.PlayerValueFormatter;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public final class VaultMenu {
    public static final int PAGE_SIZE = 45;
    private static final int MENU_SIZE = 54;

    private VaultMenu() {}

    public static Inventory create(UUID playerUuid, int page, long playerValue,
                                   List<VaultItemRecord> fetchedItems) {
        boolean hasNext = fetchedItems.size() > PAGE_SIZE;
        List<VaultItemRecord> visible = fetchedItems.subList(0, Math.min(PAGE_SIZE, fetchedItems.size()));
        VaultMenuHolder holder = new VaultMenuHolder(playerUuid, page, playerValue,
                visible, page > 0, hasNext);
        Inventory inventory = Bukkit.createInventory(holder, MENU_SIZE,
                Component.text("Player Value · " + (page + 1)));
        holder.inventory(inventory);

        for (int i = 0; i < visible.size(); i++) {
            VaultItemRecord record = visible.get(i);
            Material material = Material.matchMaterial(record.material());
            if (material == null || material.isAir()) material = Material.BARRIER;
            ItemStack icon = new ItemStack(material, Math.max(1, Math.min(record.amount(), material.getMaxStackSize())));
            ItemMeta meta = icon.getItemMeta();
            meta.displayName(Component.text(record.material().toLowerCase(Locale.ROOT).replace('_', ' ')));
            List<Component> lore = new ArrayList<>();
            lore.add(Component.text("Cantidad almacenada: " + record.amount()));
            lore.add(Component.text("Valor acreditado: " + PlayerValueFormatter.compact(record.creditedValue())));
            lore.add(Component.text("Identificador: " + record.itemId().toString().substring(0, 8)));
            lore.add(Component.text(" "));
            lore.add(Component.text("Almacén personal · No transferible"));
            meta.lore(lore);
            icon.setItemMeta(meta);
            inventory.setItem(i, icon);
        }

        inventory.setItem(45, button(Material.ARROW, "Página anterior", holder.hasPrevious()));
        inventory.setItem(49, button(Material.NETHER_STAR,
                "Player Value: " + PlayerValueFormatter.compact(playerValue), false));
        inventory.setItem(53, button(Material.ARROW, "Página siguiente", holder.hasNext()));
        return inventory;
    }

    private static ItemStack button(Material material, String name, boolean enabled) {
        ItemStack item = new ItemStack(enabled ? material : Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(name));
        item.setItemMeta(meta);
        return item;
    }
}
