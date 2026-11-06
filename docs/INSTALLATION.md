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
5. Mantén `database.mysql.enabled: false`, `database.mysql.import-sqlite-on-startup: false` y `player-value.enabled: false`.
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
- MySQL: existen el esquema versionado, el pool JDBC con sesión UTC, el adaptador `PlayerDataStore` y el importador SQLite→MySQL de solo lectura. La base destino debe estar vacía y la importación conserva la SQLite original. CI ejecuta siete pruebas de integración contra MySQL 8.4: esquema/UPSERT, unicidad y rollback, persistencia real de estadísticas/tótems, dos depósitos consecutivos e idempotencia de Player Value, además de importar una SQLite real a una base MySQL vacía sin alterar el archivo fuente. Aún faltan validar la versión exacta del proveedor y ensayos de recuperación ante interrupciones en staging. MySQL ya está cableado para pruebas controladas, pero `database.mysql.enabled` debe permanecer en `false` en producción.
- PostgreSQL: implementación heredada de una fase anterior; no es el destino de despliegue acordado. Déjalo desactivado.
- Los niveles/atributos de mobs se dejan para una fase posterior.

## Futuro cambio de SQLite a MySQL

La instalación de producción debe continuar usando SQLite hasta completar staging. El backend MySQL ya puede seleccionarse en un servidor de pruebas con `database.mysql.enabled: true`; el valor predeterminado sigue siendo `false`. No cambies producción todavía.

Antes del cambio deberán estar terminados y probados:
1. Confirmar que la versión MySQL/MariaDB del proveedor admite el DDL usado (checks, columnas generadas e índices).
2. Confirmar que las pruebas de CI del adaptador y del importador SQLite→MySQL siguen pasando en el commit que se vaya a desplegar.
3. En staging, activar `database.mysql.enabled: true` con una base vacía. Si se migran datos, respaldar `hardcore.db`, activar también `database.mysql.import-sqlite-on-startup: true` durante un solo arranque y dejar que el importador valide integridad, destino vacío, conteos e IDs. Tras una importación correcta, volver esa opción a `false`.
4. Ensayar interrupciones y recuperación de depósitos/retiros sin duplicar ni perder objetos.
5. Verificar un backup restaurable y completar el ensayo de staging antes de habilitar el backend.

La base SQLite original y sus respaldos se conservarán. Si la importación falla, el plugin se desactiva sin borrar SQLite. No habrá cambio de producción ni activación de Player Value hasta superar esas comprobaciones.
