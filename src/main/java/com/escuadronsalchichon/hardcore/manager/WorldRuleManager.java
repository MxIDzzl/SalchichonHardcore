
package com.escuadronsalchichon.hardcore.manager;

import org.bukkit.Difficulty;
import org.bukkit.GameRules;
import org.bukkit.World;

public class WorldRuleManager {

    public void applyRules(World world) {

        // Dificultad HARD.
        world.setDifficulty(Difficulty.HARD);

        // Desactivar regeneración natural.
        world.setGameRule(
                GameRules.NATURAL_HEALTH_REGENERATION,
                false
        );

        // Permitir el comportamiento normal de los mobs.
        world.setGameRule(
                GameRules.MOB_GRIEFING,
                true
        );

        // No conservar inventario al morir.
        world.setGameRule(
                GameRules.KEEP_INVENTORY,
                false
        );
    }
}
