package com.escuadronsalchichon.hardcore.integration;

import com.escuadronsalchichon.hardcore.SalchichonHardcore;
import com.escuadronsalchichon.hardcore.database.ClanMemberRecord;
import com.escuadronsalchichon.hardcore.database.ClanRecord;
import com.escuadronsalchichon.hardcore.database.PlayerDataStore;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Read-only bridge to zMClans' public API. All zMClans API calls are made on
 * the server thread; only the immutable database snapshot is written async.
 */
public final class ClanDirectorySync {
    private static final long SYNC_INTERVAL_TICKS = 20L * 60L * 5L;

    private final SalchichonHardcore plugin;
    private final PlayerDataStore store;
    private final AtomicBoolean writeInProgress = new AtomicBoolean();
    private BukkitTask task;
    private boolean warnedUnavailable;

    public ClanDirectorySync(SalchichonHardcore plugin, PlayerDataStore store) {
        this.plugin = plugin;
        this.store = store;
    }

    public void start() {
        if (!Bukkit.getPluginManager().isPluginEnabled("zMClans")) {
            plugin.getLogger().warning("zMClans no está instalado; las páginas públicas de clanes permanecerán sin sincronizar.");
            return;
        }
        syncNow();
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::syncNow,
                SYNC_INTERVAL_TICKS, SYNC_INTERVAL_TICKS);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    private void syncNow() {
        if (!Bukkit.isPrimaryThread() || writeInProgress.get()) return;
        Plugin clansPlugin = Bukkit.getPluginManager().getPlugin("zMClans");
        if (clansPlugin == null || !clansPlugin.isEnabled()) {
            if (!warnedUnavailable) {
                plugin.getLogger().warning("zMClans dejó de estar disponible; se conserva el último snapshot público.");
                warnedUnavailable = true;
            }
            return;
        }

        final Snapshot snapshot;
        try {
            snapshot = readSnapshot(clansPlugin);
        } catch (ReflectiveOperationException | RuntimeException exception) {
            plugin.getLogger().warning("No se pudo leer el API público de zMClans; se conserva el último snapshot válido.");
            plugin.getLogger().warning(exception.getClass().getSimpleName() + ": " + exception.getMessage());
            return;
        }

        if (!writeInProgress.compareAndSet(false, true)) return;
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                if (!store.replaceClanDirectory(snapshot.clans(), snapshot.members())) {
                    plugin.getLogger().warning("La sincronización de clanes no se confirmó; se conserva el último snapshot válido.");
                }
            } catch (RuntimeException exception) {
                plugin.getLogger().warning("Falló la escritura del snapshot de zMClans: " + exception.getMessage());
            } finally {
                writeInProgress.set(false);
            }
        });
    }

    private Snapshot readSnapshot(Plugin clansPlugin) throws ReflectiveOperationException {
        Object api = clansPlugin.getClass().getMethod("getAPI").invoke(clansPlugin);
        if (api == null) throw new IllegalStateException("zMClans devolvió un API nulo.");

        Method leaderboardMethod = api.getClass().getMethod("getLeaderboard", String.class);
        Method membersMethod = api.getClass().getMethod("getMembers", UUID.class);
        Object rawClans = leaderboardMethod.invoke(api, "points");
        if (!(rawClans instanceof List<?> clanList)) {
            throw new IllegalStateException("El leaderboard de zMClans no devolvió una lista.");
        }

        List<ClanRecord> clans = new ArrayList<>();
        List<ClanMemberRecord> members = new ArrayList<>();
        for (Object clan : clanList) {
            UUID clanId = uuid(call(clan, "id"));
            UUID leaderId = uuid(call(clan, "leaderId"));
            Instant createdAt = instant(call(clan, "createdAt"));
            ClanRecord record = new ClanRecord(
                    clanId,
                    string(call(clan, "name")),
                    string(call(clan, "tag")),
                    string(call(clan, "description")),
                    leaderId,
                    createdAt,
                    integer(call(clan, "memberCount")),
                    integer(call(clan, "maxMembers")),
                    number(call(clan, "points")),
                    integer(call(clan, "level")),
                    number(call(clan, "kills")),
                    number(call(clan, "deaths")),
                    decimal(call(clan, "kdr"))
            );
            clans.add(record);

            Object rawMembers = membersMethod.invoke(api, clanId);
            if (!(rawMembers instanceof List<?> memberList)) {
                throw new IllegalStateException("La lista de miembros de " + clanId + " no es válida.");
            }
            for (Object member : memberList) {
                members.add(new ClanMemberRecord(
                        uuid(call(member, "playerId")),
                        uuid(call(member, "clanId")),
                        string(call(member, "rankId")),
                        instant(call(member, "joinedAt")),
                        number(call(member, "kills")),
                        number(call(member, "deaths")),
                        decimal(call(member, "kdr"))
                ));
            }
        }
        return new Snapshot(List.copyOf(clans), List.copyOf(members));
    }

    private Object call(Object target, String method) throws ReflectiveOperationException {
        if (target == null) throw new IllegalStateException("Snapshot nulo al leer " + method);
        return target.getClass().getMethod(method).invoke(target);
    }

    private UUID uuid(Object value) {
        if (value instanceof UUID uuid) return uuid;
        throw new IllegalStateException("UUID ausente o inválido.");
    }

    private Instant instant(Object value) {
        if (value instanceof Instant instant) return instant;
        throw new IllegalStateException("Fecha de clan ausente o inválida.");
    }

    private String string(Object value) {
        return value == null ? "" : value.toString();
    }

    private int integer(Object value) {
        if (value instanceof Number number) return Math.toIntExact(number.longValue());
        throw new IllegalStateException("Entero de clan ausente o inválido.");
    }

    private long number(Object value) {
        if (value instanceof Number number) return number.longValue();
        throw new IllegalStateException("Contador de clan ausente o inválido.");
    }

    private double decimal(Object value) {
        if (value instanceof Number number) return number.doubleValue();
        throw new IllegalStateException("Ratio de clan ausente o inválido.");
    }

    private record Snapshot(List<ClanRecord> clans, List<ClanMemberRecord> members) { }
}
