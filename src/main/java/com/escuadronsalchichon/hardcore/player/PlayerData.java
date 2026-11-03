package com.escuadronsalchichon.hardcore.player;

import java.time.LocalDate;
import java.util.UUID;

public class PlayerData {

    private final UUID uuid;
    private String username;
    private LocalDate lastDeathDate;

    public PlayerData(UUID uuid, String username, LocalDate lastDeathDate) {
        this.uuid = uuid;
        this.username = username;
        this.lastDeathDate = lastDeathDate;
    }

    public UUID getUuid() {
        return uuid;
    }

    public String getUsername() {
        return username;
    }

    public LocalDate getLastDeathDate() {
        return lastDeathDate;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public void setLastDeathDate(LocalDate lastDeathDate) {
        this.lastDeathDate = lastDeathDate;
    }
}