# Escuadrón Salchichón SMP — Plan técnico

## Decisiones confirmadas

- El proyecto continúa dentro del plugin existente `SalchichonHardcore`; no se crea un plugin SalchichonCore separado.
- PostgreSQL es la base de datos central para estadísticas, bóveda, auditoría, trades, contenido web y licencias.
- Player Value es prestigio público, no moneda: no se transfiere, no se compra y no se usa como contraprestación en trades.
- `/value` abre una bóveda personal paginada, sin límite artificial de páginas, que conserva objetos reales y sus metadatos.
- `/trade <jugador>` permite exclusivamente intercambio de objetos por objetos.
- El TAB muestra el Player Value global con formato compacto (por ejemplo 950, 1.2k, 3.5m).
- Web pública y panel privado comparten backend, con identidad visual oscura rosa/morada/azul.
- El plugin requiere licencia privada validada por backend. No se incrustan secretos permanentes en el JAR.

## Arquitectura

1. **Paper plugin (Java 25 / Paper API)**: captura eventos del juego, ejecuta inventarios seguros, comandos y placeholders.
2. **Backend Spring Boot**: API autenticada para el plugin, API pública de lectura y API administrativa protegida.
3. **PostgreSQL**: fuente de verdad para estadísticas corregibles, bóveda, operaciones, auditoría, anuncios, temporada y licencias.
4. **Web Next.js**: páginas públicas, perfiles, ranking, skins y actualizaciones; panel administrativo separado por autorización.
5. **SkinRestorer**: integración mediante API soportada por la versión instalada; no inferir skins desde UUID offline.

La base de datos no se expone directamente a Internet. El plugin y el backend se conectan por HTTPS con credenciales rotables. La web nunca recibe secretos del servidor.

## Fases

### Fase 1 — Fundación y datos
- Inventariar el esquema SQLite actual y respaldar `hardcore.db`.
- Diseñar migración no destructiva SQLite → PostgreSQL; conservar UUID, nombre, fechas de muerte, tótems y auditoría.
- Crear esquema versionado y migraciones repetibles.
- Incorporar pool de conexiones y configuración PostgreSQL por variables/secretos.
- Implementar repositorios transaccionales y pruebas de migración/rollback.
- No borrar ni sobrescribir SQLite hasta verificar conteos y checksums en PostgreSQL.

### Fase 2 — API y licencia
- Identidad de instalación/servidor y licencia revocable.
- Autenticación plugin↔backend con secreto rotatorio y firma/HMAC, TLS y timestamps/nonce.
- Endpoints versionados; límites de peticiones; auditoría.
- La licencia no se considera protección anticopia absoluta: un JAR modificable puede parchearse. Se usa para autorizar instalaciones legítimas y revocar claves filtradas.

### Fase 3 — Estadísticas oficiales
- Separar estadísticas oficiales corregibles de estadísticas vanilla observadas.
- Eventos inmutables de auditoría para cada corrección administrativa.
- TAB y PlaceholderAPI leen el valor oficial de la API/cache sincronizada.
- Formato compacto de Player Value: K, M, B, T; redondeo consistente y sin overflow.

### Fase 4 — Bóveda Player Value
- Catálogo allowlist de materiales y puntuaciones configurables.
- Almacenamiento de ItemStack serializado/versionado y metadatos íntegros.
- Depósitos/retiros transaccionales con idempotency key, bloqueo por jugador, registro de operación y reconciliación.
- GUI paginada con páginas creadas bajo demanda; nunca funciona como inventario adicional de uso libre.
- Cooldown configurable entre operaciones exitosas; el cooldown no sustituye la protección transaccional.
- No retirar el objeto del inventario del jugador hasta confirmar persistencia; recuperación si el servidor cae entre pasos.

### Fase 5 — Trade seguro
- Trade solo de objetos.
- Dos ofertas aisladas; los iconos/controles no son objetos extraíbles.
- Confirmaciones ligadas a versión de oferta; cualquier cambio invalida ambas.
- Validación final de inventario, bloqueo de participantes y entrega idempotente.
- Expiración, cancelación, desconexión y recuperación de operaciones pendientes.

### Fase 6 — Web y administración
- Next.js con estética oscura rosa/morada/azul.
- Ranking, perfiles, estadísticas, Player Value, skins SkinRestorer, actualizaciones/leaks y dificultad/temporada.
- Panel privado con roles, MFA recomendado, correcciones con motivo obligatorio y auditoría.
- Próxima actualización: fecha opcional, título, descripción y teaser.

### Fase 7 — QA y despliegue
- Pruebas de concurrencia, desconexión, reinicio, DB caída, duplicación y recuperación.
- Backups PostgreSQL automáticos, cifrados y pruebas periódicas de restauración.
- Despliegue gradual primero en servidor de pruebas; no migrar el servidor principal hasta validar.

## Modelo de datos inicial

- `players`: UUID, nombre actual, primera/última conexión.
- `player_stats`: muertes oficiales, tótems, tiempo jugado y métricas corregibles.
- `stat_events`: historial inmutable de incrementos/correcciones con actor, motivo y referencia.
- `value_catalog`: material, puntos, activo y versión.
- `vault_items`: propietario, serialización del objeto, cantidad, puntos, estado y versión.
- `vault_operations`: depósito/retiro, idempotency key, estado y timestamps.
- `trades`, `trade_offers`, `trade_items`, `trade_events`: ciclo de vida y recuperación.
- `announcements`: versión, título, cuerpo, teaser, fecha prevista y estado.
- `server_state`: temporada, dificultad y descripción de modificadores.
- `server_licenses`: instalación, hash de credencial, estado, expiración y revocación.
- `admin_audit`: actor, acción, objetivo, motivo y cambios.

## Reglas críticas de integridad

- PostgreSQL es la fuente de verdad una vez completada la migración.
- Ninguna operación de bóveda o trade se confirma solo por una animación de GUI.
- Operaciones económicas/de objetos usan transacciones y claves idempotentes.
- Los objetos se respaldan junto con la base de datos; backups deben incluir prueba de restauración.
- Las correcciones de estadísticas nunca borran el rastro de auditoría.
- No se almacenan contraseñas ni tokens en el repositorio, JAR o logs.
- Los endpoints administrativos requieren autorización independiente de la licencia del plugin.

## Estado inicial conocido

El repositorio actual usa SQLite en `hardcore.db`, JDBC directo y SQL específico de SQLite (`PRAGMA`, `AUTOINCREMENT`). La migración a PostgreSQL es un cambio real de persistencia y no se resuelve solo agregando el driver JDBC. Debe hacerse con migraciones y pruebas antes de cambiar el JAR del servidor.
