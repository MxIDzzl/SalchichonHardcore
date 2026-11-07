package com.escuadronsalchichon.hardcore.manager;

import com.escuadronsalchichon.hardcore.database.PlayerDataStore;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Accumulates online time from monotonic elapsed time, not client statistics.
 * Flushes every minute, on quit, and during plugin shutdown.
 */
public final class PlaytimeTracker implements Listener {
    private final JavaPlugin plugin;
    private final PlayerDataStore store;
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
    private BukkitTask task;

    public PlaytimeTracker(JavaPlugin plugin, PlayerDataStore store) {
        this.plugin = plugin;
        this.store = store;
    }

    public void start() {
        for (Player player : Bukkit.getOnlinePlayers()) begin(player);
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::flushAll, 1200L, 1200L);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        begin(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        flush(event.getPlayer().getUniqueId(), event.getPlayer().getName());
        sessions.remove(event.getPlayer().getUniqueId());
    }

    private void begin(Player player) {
        sessions.putIfAbsent(player.getUniqueId(), new Session(player.getName(), System.nanoTime()));
    }

    private void flushAll() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            flush(player.getUniqueId(), player.getName());
        }
    }

    private void flush(UUID uuid, String username) {
        Session session = sessions.get(uuid);
        if (session == null) return;
        long now = System.nanoTime();
        long elapsed = now - session.lastFlushNanos;
        if (elapsed < 0) return;
        long ticks = elapsed / 50_000_000L;
        if (ticks <= 0) return;
        if (store.addPlaytimeTicks(uuid, username, ticks)) {
            session.lastFlushNanos += ticks * 50_000_000L;
            session.username = username;
        } else {
            plugin.getLogger().warning("No se pudo guardar el tiempo de juego de " + username
                    + "; se reintentará en el siguiente ciclo.");
        }
    }

    public void shutdown() {
        if (task != null) task.cancel();
        flushAll();
        sessions.clear();
    }

    private static final class Session {
        private String username;
        private long lastFlushNanos;
        private Session(String username, long lastFlushNanos) {
            this.username = username;
            this.lastFlushNanos = lastFlushNanos;
        }
    }
}
