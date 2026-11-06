# Trade system scope — external plugin is authoritative

**Status (2026-11-06):** SalchichonHardcore does not provide or register a native trade workflow. All player-to-player trades are handled by the external trade plugin already installed on Escuadrón Salchichón SMP.

This document is retained only as a historical design record. Its proposed native GUI, escrow, confirmations, delivery, and recovery flows are not part of the active release scope and must not be implemented or exposed through commands or the website.

Legacy trade persistence classes and schema objects may remain for compatibility with older data. They are not permission to run a second trade system. Do not add /trade commands, native trade menus, delivery automation, or trade administration to SalchichonHardcore.

For current scope, see [PRODUCT-SCOPE.md](PRODUCT-SCOPE.md).