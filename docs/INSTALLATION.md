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
- Player Value: incluye visor, selector de depósito tipo cofre, depósitos por pila con journal y retiros protegidos. Sigue desactivado por defecto hasta completar pruebas de fallos y staging.
- Intercambios: los gestiona el plugin externo del servidor; este plugin no registra el comando /trade.
- MySQL: el conector y el pool JDBC están preparados, pero todavía falta el adaptador de persistencia, el esquema y el importador SQLite→MySQL. `database.mysql.enabled` debe permanecer en `false`; si se activa prematuramente, el plugin se deshabilita de forma segura en lugar de iniciar accidentalmente con SQLite.
- PostgreSQL: implementación heredada de una fase anterior; no es el destino de despliegue acordado. Déjalo desactivado.
- Los niveles/atributos de mobs se dejan para una fase posterior.

## Futuro cambio de SQLite a MySQL

La instalación de producción debe continuar usando SQLite hasta completar el backend MySQL y validarlo en staging. No actives `database.mysql.enabled` todavía: el plugin se detendrá intencionalmente si detecta esa opción, para evitar que parezca que está usando MySQL cuando en realidad no lo hace.

Antes del cambio deberán estar terminados y probados:
1. El esquema MySQL versionado y compatible con MySQL/MariaDB del proveedor.
2. El adaptador completo de `PlayerDataStore`, incluidas transacciones, estadísticas, auditoría y operaciones durables de Player Value.
3. El importador SQLite→MySQL con validación de integridad, destino vacío, conteos, rollback y preservación de IDs.
4. Pruebas reales contra MySQL/MariaDB y ensayos de recuperación ante interrupciones.
5. Backup restaurable y ensayo completo en staging.

La base SQLite original y sus respaldos se conservarán. No habrá cambio de producción ni activación de Player Value hasta superar esas comprobaciones.
