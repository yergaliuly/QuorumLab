# План M2: выборы лидера

Spec: [design](../specs/2026-11-15-leader-election-design.md). Ветка feat/m2-leader-election от eabf1b3. Один исполнитель, без computer use, без push. Пользователь явно поручил реализацию; повторное согласование существующего стека не требуется.

## Task 1 — deterministic election core

- RED: config/log position/heartbeat validation; start/timeout/self-vote, majority, freshness, same/higher term step-down; persist-before-send, higher-term during pending save, stale completion/incarnation, failure и timeout flooding.
- GREEN: ElectionConfig, LogPosition, ElectionCore; RaftEvent.Start и RaftRpc.Heartbeat. Существующий RaftCore остаётся effect API; core не получает I/O/RNG implementation dependencies.
- Проверить :quorum-core:test (включая guards). Ожидается GREEN после реализации; диагностировать реальные ошибки, не заменять проверки ожиданиями.

## Task 2 — seeded simulator

- RED: reproducible RNG bounds, три узла выбирают лидера, split vote/следующий term, loss of leader/partition/heal, restart, delayed/duplicate RPC/completion, storage failure, seeded safety smoke.
- GREEN: SeededRandomSource, ElectionSimulation на существующем scheduler. Disk write и delivery completion — разные события; crash outcome контролируется тестом. Не добавлять M7 engine.
- Проверить :quorum-simulator:test, сохранить seed/trace для regression при ошибке.

## Task 3 — WAL integration и завершение

- Linux JUnit в quorum-integration с test-only dependencies core/storage; temporary files в build на ext4.
- FileWal/WalStorage/core: crash после durable vote, до completion; recovered vote запрещает второй grant; stale event старой incarnation не выпускает ответ; следующий term работает после persist.
- Windows clean test build и fresh scripts/test-wsl.sh, XML counts/skips, manifest/FS. Self-review сложных pending-state сценариев и сверка с design.
- ADR-004, учебный ELECTIONS, PROJECT_STATE/ROADMAP/ACCEPTANCE/README. Локальный коммит после проверок, точная push-команда пользователю.

## Review focus

Устаревший persist завершился после higher term; grant и self-vote до durability; повторные голосования одного peer; лог свежий по term, но короче по index; таймер не должен сбрасываться отказами; старые incarnation events; failure с фактически сохранённым vote; два лидера разных terms не ошибочно считаются election safety violation.

## Журнал

- Подготовка: PR #5 merged eabf1b3, CI PR/main jobs Ubuntu/Windows success; main fast-forward, рабочая ветка создана. Реализация ещё не начата.
- Task 1: complete — 26/26 новых cases сначала RED на заглушках; после реализации core suite + architecture/classpath guards GREEN. Реализованы pending/durable state, сериализация persist, membership/log freshness, stale events и failure stop.
- Task 2: complete — 14/14 новых simulator cases RED на заглушках, затем GREEN; 100 seeded fault trials внутри одного case, assertions election safety и durable-before-send выполняются на каждом соответствующем событии. Trace повторяется для одинакового seed/script.
- Task 3: local verification passed — Windows clean/test/build: 230 выполненных + 12 Linux methods skipped; WSL run-WVW1bqw8: 256/256, 0 errors/failures/skips, no cache. Настоящие FileWal/WalStorage/core integration tests используют ext4 build/tmp/test-files.
- Final review: self-review согласно предпочтению одного исполнителя. Проверены сериализация persist, отмена старых replies/campaign при higher term, vote set, freshness ordering, denied timer behavior, pending failure, incarnation filtering и отдельная семантика election-only heartbeat. Непредвиденных падений после реализации не было; RED был запланирован.
- Ruling: heartbeat M2 не является AppendEntries, не меняет log/commit и не даёт quorum evidence. Полноценный replication protocol — M3.
- Ruling: in-flight persist блокирует новые writes; при таймауте ожидания просто заводится новый timer. После неизвестного I/O failure core FAILED до recovery. Liveness требует завершения storage и подходящих задержек, фиксированный election deadline не обещается.
- Minor deferred: существующее предупреждение Gradle про registering-синтаксис в core; исправление не связано с выборами и остаётся подготовкой к Gradle 10.
