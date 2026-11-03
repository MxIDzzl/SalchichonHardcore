package com.escuadronsalchichon.hardcore.expansion;

import com.escuadronsalchichon.hardcore.manager.PlayerManager;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;

public class HardcoreExpansion extends PlaceholderExpansion {

    private final PlayerManager playerManager;

    public HardcoreExpansion(PlayerManager playerManager) {
        this.playerManager = playerManager;
    }

    @Override
    public @NotNull String getAuthor() {
        return "EscuadronSalchichon";
    }

    @Override
    public @NotNull String getIdentifier() {
        return "salchichonhardcore";
    }

    @Override
    public @NotNull String getVersion() {
        return "1.0.0";
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onRequest(
            OfflinePlayer player,
            @NotNull String params
    ) {
        if (player == null || player.getUniqueId() == null) {
            return null;
        }

        try {
            boolean hasLife = playerManager.hasLife(
                    player.getUniqueId()
            );

            if (params.equalsIgnoreCase("lives")) {
                return hasLife ? "1" : "0";
            }

            if (params.equalsIgnoreCase("status")) {
                return hasLife ? "DISPONIBLE" : "SIN VIDAS";
            }

        } catch (RuntimeException exception) {
            return "ERROR";
        }

        return null;
    }
}