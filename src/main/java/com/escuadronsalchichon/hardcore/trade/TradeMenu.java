package com.escuadronsalchichon.hardcore.trade;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class TradeMenu {
    private static final int PAGE_SIZE = 20;

    private TradeMenu() {}

    public static Inventory create(UUID viewer, TradeSessionRecord session,
                                   List<TradeEscrowRecord> records, int page) {
        List<TradeEscrowRecord> aItems = records.stream()
                .filter(item -> item.ownerUuid().equals(session.playerA())).toList();
        List<TradeEscrowRecord> bItems = records.stream()
                .filter(item -> item.ownerUuid().equals(session.playerB())).toList();
        int max = Math.max(aItems.size(), bItems.size());
        int pages = Math.max(1, (max + PAGE_SIZE - 1) / PAGE_SIZE);
        int current = Math.max(0, Math.min(page, pages - 1));
        TradeMenuHolder holder = new TradeMenuHolder(viewer, session, records,
                current, current > 0, current + 1 < pages);
        Inventory inventory = Bukkit.createInventory(holder, 54,
                Component.text("Intercambio · " + (current + 1) + "/" + pages));
        holder.inventory(inventory);

        fillSide(inventory, aItems, current, 0, "Oferta A");
        fillSide(inventory, bItems, current, 27, "Oferta B");
        inventory.setItem(22, button(Material.BOOK,
                "Estado: " + session.status(),
                List.of("Revisión: " + session.revision(),
                        "Tu confirmación: " + (session.isConfirmed(viewer) ? "Sí" : "No"),
                        "Otra confirmación: " + (session.isConfirmed(session.other(viewer)) ? "Sí" : "No"),
                        "Player Value no se puede intercambiar.")));
        boolean canConfirm = session.status() == TradeStatus.OPEN && !session.isConfirmed(viewer);
        inventory.setItem(49, button(canConfirm ? Material.LIME_CONCRETE : Material.GRAY_CONCRETE,
                canConfirm ? "Confirmar oferta actual" : "Confirmación no disponible",
                List.of("Ambos deben confirmar la misma revisión.",
                        "Cualquier cambio invalida las confirmaciones.")));
        inventory.setItem(45, button(holder.hasPrevious() ? Material.ARROW : Material.GRAY_STAINED_GLASS_PANE,
                "Página anterior", List.of()));
        inventory.setItem(53, button(holder.hasNext() ? Material.ARROW : Material.GRAY_STAINED_GLASS_PANE,
                "Página siguiente", List.of()));
        return inventory;
    }

    private static void fillSide(Inventory inventory, List<TradeEscrowRecord> items,
                                 int page, int offset, String sideName) {
        int start = page * PAGE_SIZE;
        for (int i = 0; i < PAGE_SIZE && start + i < items.size(); i++) {
            TradeEscrowRecord record = items.get(start + i);
            ItemStack icon;
            try {
                icon = ItemStack.deserializeBytes(record.payload());
            } catch (RuntimeException invalid) {
                icon = new ItemStack(Material.BARRIER);
            }
            if (icon == null || icon.getType().isAir()) icon = new ItemStack(Material.BARRIER);
            ItemMeta meta = icon.getItemMeta();
            List<Component> lore = new ArrayList<>(meta.lore() == null ? List.of() : meta.lore());
            lore.add(Component.text(" "));
            lore.add(Component.text(sideName));
            lore.add(Component.text("Estado: " + record.status()));
            meta.lore(lore);
            icon.setItemMeta(meta);
            inventory.setItem(offset + i, icon);
        }
    }

    private static ItemStack button(Material material, String name, List<String> loreLines) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(name));
        meta.lore(loreLines.stream().map(Component::text).toList());
        item.setItemMeta(meta);
        return item;
    }
}
