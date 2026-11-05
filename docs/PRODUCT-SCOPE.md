# Alcance funcional del ecosistema Escuadrón Salchichón SMP

Este documento corrige el alcance para que el desarrollo no invierta tiempo en sistemas que el servidor ya resuelve con plugins externos.

## Componentes

### 1. SalchichonHardcore (Paper)
- Sistema Hardcore diario y estadísticas oficiales.
- Player Value como cofre/almacén personal: el jugador deposita objetos, cada material tiene una puntuación configurada según su rareza y cantidad, y el agregado queda persistido.
- Protección de inventario y journal durable para que depósitos/retiros no se dupliquen ni se pierdan ante reinicios.
- Sincronización de estadísticas y datos públicos de jugador con el backend.
- Integración opcional con PlaceholderAPI y SkinRestorer.

### 2. Integraciones externas
- Los intercambios los realiza el plugin externo de trades ya instalado. SalchichonHardcore no debe registrar ni mantener un segundo sistema de intercambio.
- Los clanes pertenecen a zMClans. El ecosistema web debe mostrar sus datos, obtenidos desde la fuente autoritativa de zMClans; no se debe inventar ni duplicar la lógica de clanes.
- Las skins se leen desde la API de SkinRestorer, no desde el UUID offline de Mojang.

### 3. API y base de datos
- Persistir estadísticas de jugadores, Player Value, perfiles, skins y datos de clanes sincronizados.
- Mantener PostgreSQL privado y la API como interfaz de lectura para la web.
- Conservar SQLite y los respaldos hasta completar staging, migración y restauración.

### 4. Portal web
- Portal público con diseño trabajado y responsive.
- Inicio con temporada, dificultad actual, última actualización y próxima actualización.
- Directorio, perfiles individuales, rankings y páginas de clanes.
- Perfiles con skin de SkinRestorer, estadísticas, Player Value y clan.
- Anuncios y archivo de actualizaciones.
- Panel administrativo seguro; no enviar tokens privados al navegador.

## Fuera del alcance inmediato
- Sistema propio de trades: usar el plugin externo.
- Mobs de nivel avanzado, atributos especiales y variantes de enemigos: fase posterior. La dificultad puede mostrarse y administrarse, pero el escalado de mobs se desarrollará después.

## Estado conocido y trabajo pendiente
- El plugin ya contiene la integración opcional de SkinRestorer que persiste el hash de textura en el perfil.
- El plugin ya contiene persistencia de bóveda, visor, depósito desde la mano principal y flujo de retiro con marcadores PDC. El visor actual todavía no es el cofre interactivo de depósito solicitado; esa interfaz debe completarse.
- Player Value sigue desactivado por defecto hasta completar pruebas de crash/recovery, SQLite/PostgreSQL y staging.
- El backend/web ya exponen estadísticas base, perfiles, rankings y anuncios. Falta incorporar la fuente autoritativa de zMClans y presentar el clan en perfiles/directorio/páginas de clan.
- La web ya tiene estructura de última actualización y próxima actualización; debe pulirse visualmente y validarse con datos reales.
- No activar PostgreSQL ni cambiar el servidor real hasta completar respaldos, restauración y pruebas de staging.
