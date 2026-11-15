# План M3

Основание: [design](../specs/2026-11-15-log-replication-design.md), ROADMAP QL-010…012. Ветка `feat/m3-log-replication` от PR #6 merge `6e05e41`. CI M2 PR/main: Ubuntu/Windows success.

- [x] Контракты log/recovery/AppendEntries/persist/apply/propose; RED → GREEN.
- [x] Расширить election core до RaftNode, сохранить M2 regression coverage.
- [x] Follower matching/repair/durability; leader progress/NO_OP/current-term commit; ordered apply.
- [x] Расширить simulator, Figure 8 и seeded failures/safety assertions.
- [x] Соединить PersistBatch с FileWal, проверить restart и committed replay на Linux.
- [x] Review опасных interleavings, Windows + WSL gates, manifest и ссылки.
- [x] ADR-005, учебное объяснение, PROJECT_STATE/ROADMAP/ACCEPTANCE.
- [x] Подготовка локального коммита после проверки manifest/ссылок; push остаётся пользователю.

Итог: Windows clean test build success (256 non-skipped, 17 Linux skips); WSL/ext4 run-AIH1CRxD 287/287 без cache. Проверены 104 файла manifest, 69 совпадающих code/config files и 131 локальная ссылка. Отрицательный probe без current-term guard: оба regression tests обнаружили commit 2 вместо 0. Ветка готова к локальному коммиту и пользовательскому push.

Проверки повторяются после изменения исполняемого кода или выявленного риска. Полные исходные требования не редактируются. Работа в одном checkout и одной ветке; дополнительных агентов нет.
