# Trade recovery — external plugin boundary

**Status (2026-11-06):** Trades on Escuadrón Salchichón SMP are managed by the external trade plugin. SalchichonHardcore does not own the active trade lifecycle and must not deliver, return, cancel, or reconcile items belonging to that plugin.

This file replaces the historical native-trade recovery procedure. Do not use legacy SalchichonHardcore trade tables or commands as evidence that a trade can be safely replayed. Staff must use the external trade plugin's own documented recovery and support process.

Legacy trade persistence artifacts in SalchichonHardcore are compatibility-only. They must not be exposed as active player commands, web actions, or automated delivery operations.

For current scope, see [PRODUCT-SCOPE.md](PRODUCT-SCOPE.md).