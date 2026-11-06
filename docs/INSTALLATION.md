# Instalación inicial y puesta en marcha

## Requisitos del servidor
- Paper 26.3 (la compilación se verifica contra Paper 26.3 build 207).
- Java 25.
- PlaceholderAPI 2.12.3 (opcional, para placeholders).
- SkinsRestorer API 15 (opcional, para sincronizar skins).
- zMClans (opcional; el puente público de clanes se activa si está instalado).

## Primera instalación segura con SQLite
1. Apaga el servidor.
2. Conserva una copia del JAR actual y respalda completa la carpeta `plugins/SalchichonHardcore/`, especialmente `hardcore.db`.
3. Descarga el artefacto de prueba desde el workflow de [Maven build](https://github.com/MxIDzzl/SalchichonHardcore/actions/workflows/maven-build.yml). El artefacto debe corresponder al commit que se va a instalar.
4. Sustituye el JAR de SalchichonHardcore, sin borrar la carpeta de datos.
5. Mantén `database.postgresql.enabled: false` y `player-value.enabled: false`.
6. Inicia Paper y revisa la consola. Debe aparecer `SalchichonHardcore se ha iniciado correctamente.`.
7. Comprueba /hardcore check para un jugador y los placeholders si PlaceholderAPI está instalado.
8. Si zMClans está activo, el plugin toma un snapshot público y lo sincroniza cada cinco minutos. No administra clanes ni altera sus datos.

## Progresión de dificultad visible

La progresión se configura en `config.yml` bajo `season.difficulty-stages`. Cada clave es el primer día de temporada en que entra la etapa:

```yaml
season:
  difficulty: "HARD"
  difficulty-stages:
    "1": "HARD"
    "14": "HARD+"
    "28": "EXTREME"
```

Usa los mismos umbrales en la API mediante `SALCHICHON_SEASON_DIFFICULTY_STAGES=1:HARD,14:HARD+,28:EXTREME` y configura la misma fecha de inicio. Esto actualiza el placeholder `%salchichonhardcore_difficulty%` y la dificultad publicada en la web; no modifica mobs ni sus atributos.

## Estado de funciones en esta primera instalación
- Hardcore diario, estadísticas oficiales y sincronización de tiempo de juego: activables con SQLite.
- Skins: se guarda el hash público de textura que expone SkinsRestorer.
- Clanes: se leen desde el API público de zMClans y se proyectan a una caché de solo lectura.
- Player Value: permanece desactivado por defecto. El visor y el depósito actual no equivalen todavía al cofre de depósito multitem que se acordó.
- Intercambios: los gestiona el plugin externo del servidor; este plugin no registra el comando /trade.
- PostgreSQL: preparado, pero no debe activarse hasta probar la migración con una copia de la base de datos.
- Los niveles/atributos de mobs se dejan para una fase posterior.

## Migración a PostgreSQL para conectar la web
La API web requiere PostgreSQL compartido de forma privada. No cambies el backend de producción directamente:

1. Prepara una instancia PostgreSQL privada y una base destino vacía.
2. Haz dos copias de `hardcore.db` con el servidor detenido y verifica `PRAGMA integrity_check`.
3. Prueba primero en staging. Configura las credenciales del plugin y activa PostgreSQL solo en esa instancia.
4. Arranca sin jugadores conectados para que el plugin cree el esquema v1-v7.
5. Desde la consola ejecuta `hardcore migrate-sqlite CONFIRM`.
6. El importador copia jugadores, estadísticas oficiales, Player Value agregado, hashes de skins, historial estadístico y auditorías. Rechaza la operación si encuentra payloads/operaciones de bóveda o registros internos de trades que esta versión no migra.
7. Compara todos los conteos, prueba joins, muertes, reset diario, tótems, skins y la sincronización de clanes.
8. Conserva SQLite y sus respaldos sin modificarlos. No elimines el archivo original después de importar.

Si la migración se rechaza, no fuerces el cambio: vuelve a SQLite y revisa qué tabla operativa contiene registros. La negativa es deliberada para evitar pérdida silenciosa de objetos o historial.
