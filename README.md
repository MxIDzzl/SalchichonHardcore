# ✦ SalchichonHardcore ✦

<p align="center">
  <img src="https://img.shields.io/badge/ESCUADRÓN%20SALCHICHÓN-SMP-FF3D5A?style=for-the-badge" alt="Escuadrón Salchichón SMP">
  <img src="https://img.shields.io/badge/Minecraft-Paper- D8B4FE?style=for-the-badge" alt="Minecraft Paper">
  <img src="https://img.shields.io/badge/Season-UHC%201-FF8FA3?style=for-the-badge" alt="UHC Season 1">
</p>

<p align="center">
  <b>Un sistema de supervivencia Hardcore hecho para Escuadrón Salchichón SMP.</b><br>
  Vidas, estadísticas y seguimiento de la temporada en un solo plugin.
</p>

---

## 🌸 Sobre el proyecto

**SalchichonHardcore** es un plugin personalizado para el servidor **Escuadrón Salchichón SMP**. Su objetivo es complementar la experiencia UHC/Hardcore con información persistente de supervivencia y placeholders para mostrar estadísticas dentro del servidor.

Este repositorio contiene el **código fuente** del plugin. Para consultar los cambios publicados y el historial de actualizaciones del servidor, visita el repositorio independiente:

<p align="center">
  <a href="https://github.com/MxIDzzl/SalchichonSMP-Updates">
    <img src="https://img.shields.io/badge/✦%20Ver%20actualizaciones-FF3D5A?style=for-the-badge" alt="Ver actualizaciones">
  </a>
</p>

## ⚔️ Funciones

- Sistema de vidas y estado de supervivencia.
- Registro persistente de datos de jugadores mediante SQLite.
- Contador de usos de tótems de inmortalidad.
- Estadística de muertes y tiempo jugado.
- Información de salud y absorción.
- Datos de temporada: nombre, dificultad y día actual.
- Integración con **PlaceholderAPI** para mostrar información en **TAB** y otros plugins compatibles.

> El contador de tótems registra los usos desde la instalación de la versión que incorpora esta función; no reconstruye usos anteriores.

## 🧩 Placeholders

Con PlaceholderAPI y la expansión registrada por el plugin:

| Placeholder | Descripción |
|---|---|
| `%salchichonhardcore_lives%` | Vidas del jugador |
| `%salchichonhardcore_status%` | Estado del jugador |
| `%salchichonhardcore_totems%` | Tótems usados por el jugador |
| `%salchichonhardcore_deaths%` | Muertes del jugador |
| `%salchichonhardcore_playtime%` | Tiempo jugado |
| `%salchichonhardcore_health%` | Salud total considerada por el plugin |
| `%salchichonhardcore_health_display%` | Salud formateada para mostrar |
| `%salchichonhardcore_season%` | Nombre de la temporada |
| `%salchichonhardcore_difficulty%` | Dificultad configurada |
| `%salchichonhardcore_season_day%` | Día de la temporada |

## 🗓️ Temporada actual

| Dato | Valor |
|---|---|
| Temporada | UHC SEASON 1 |
| Dificultad | HARD |
| Inicio | 19 de octubre de 2026 |

La configuración de temporada y zona horaria se administra desde `config.yml`.

## 🛠️ Construcción

El proyecto utiliza Maven. Para generar el JAR:

```bash
mvn clean package
```

El artefacto se genera en el directorio `target/`.

Asegúrate de compilar y probar el plugin con las versiones de Paper, Java y dependencias que correspondan a tu servidor antes de desplegarlo.

## 📜 Actualizaciones

El historial público de cambios, novedades y versiones del servidor se mantiene por separado:

**[MxIDzzl/SalchichonSMP-Updates](https://github.com/MxIDzzl/SalchichonSMP-Updates)**

---

<p align="center">
  <b>✦ ESCUADRÓN SALCHICHÓN SMP ✦</b><br>
  <sub>Sobrevive. Coopera. Llega hasta el final.</sub>
</p>


## Player Value viewer

The `/value [page]` command opens a paginated inventory viewer over durable vault records. `/value deposit` supports allow-listed plain items from the main hand. It persists a PDC operation marker and PREPARED journal before removing the stack, then commits the item and aggregate score transactionally. On join, uncertain deposits are reconciled using both the exact marker and a durable state transition: marker present with PREPARED/RECOVERY_REQUIRED means cancel and keep the item; marker absent with DEPOSIT/DELIVERY_PENDING means commit. Ambiguous combinations remain blocked for manual review. Inventory actions are locked while a deposit/recovery is in progress.

The feature remains disabled by default in `config.yml`. Withdrawals now use a durable claim, a plugin-owned PDC marker, inventory save, and atomic claim/operation acknowledgement. Interrupted claims are reconciled only when the exact marked amount is present; missing or partial evidence remains quarantined. Full crash-injection integration tests against SQLite and PostgreSQL are still required. Do not enable Player Value on the live server yet.


## Intercambios de objetos

Los intercambios se gestionan mediante el plugin externo ya instalado en el servidor. SalchichonHardcore no registra ni administra un sistema propio de trades. Player Value es un sistema de prestigio no transferible y no forma parte de los intercambios.


## Alcance actual del ecosistema

Consulta [docs/PRODUCT-SCOPE.md](docs/PRODUCT-SCOPE.md) para el alcance correcto, integraciones externas (zMClans, SkinRestorer y trades) y funcionalidades que se dejan para fases posteriores.
