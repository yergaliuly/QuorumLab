# Дорожная карта QuorumLab

Основание: [ТЗ v1.0, разделы 8–11](TECHNICAL_SPECIFICATION.md). Статус на 15 ноября 2026: **M0–M2 завершены после CI/merge PR #6. M3 реализован и локально проверен: Windows 256 non-skipped, WSL/ext4 287/287.** CI/merge M3 ожидают пользовательского push; M4–M9 не начаты.

## Порядок и зависимости

`M0 → M1 → M2 → M3 → M4 → M5 → M6 → M7 → M8 → M9`.

Тесты и минимальная симуляция развиваются вместе с алгоритмом с M0; M7 завершает расширенную лабораторию. Независимая KV-модель нужна уже для M4, внешний checker — в M8. Наблюдаемость добавляется по мере появления runtime; UI остаётся в M9.

Каждый этап закрывается только по своим проверкам. Одна связная задача — одна рабочая ветка и PR; связанные код, тесты, документация и исправления остаются вместе. Не создавать PR на каждый файл. Большой milestone может содержать несколько таких задач. [GitHub-репозиторий](https://github.com/yergaliuly/QuorumLab) создан пользователем; до переноса задач в Issues эта страница служит локальным backlog. Оценка исходного ТЗ 12–20 недель — предварительный ориентир, без обещания дат.

## M0 — Foundation

**Результат:** воспроизводимая сборка и проверяемые границы компонентов, на которых дальше строится Raft.

- [x] Разобрать начальный объём O-01…O-03 и оформить ADR-001 scope/consistency/failure model и ADR-002 event/effects. Расширение контрактов и открытые API-детали привязаны к соответствующим этапам.
- [x] Проверить Java 21 и целевое Linux-окружение; закрепить Gradle 9.9.0/JUnit 5.14.4/ArchUnit 1.5.1. Выбран и проверен WSL Ubuntu/ext4, создан отдельный стенд и скрипт свежей сборки без build cache. Файловые гарантии WAL предстоит проверять в M1.
- [x] Создать Gradle Kotlin DSL multi-module project и Wrapper; зафиксировать структуру из ТЗ, без реализации поздних функций.
- [x] Определить начальные `RaftEvent`, `Effect`, immutable DTO, `Storage`, `Transport`, виртуальное время и порт источника случайности. RPC/сохранение пока ограничены голосованием/HardState; остальные операции расширят контракты в M1–M6.
- [x] Описать зависимости persist/send/apply, `PersistCompleted`/`PersistFailed`, границы ошибок в ADR-002. Исполнение правил ещё не реализовано; safety-тесты следуют за storage/алгоритмом.
- [x] Реализовать минимальный детерминированный event scheduler с устойчивым порядком равновременных событий и тестом повторяемой трассы.
- [x] Подготовить CI skeleton; workflow проверен на PR #1/#2 и main, оба jobs Ubuntu/Windows прошли.
- [x] Добавить автоматические правила отсутствия production dependencies и прямых обращений к I/O/времени/потокам/случайности в core. Проверены как negative fixtures, так и реальные временные нарушения в production/Gradle.
- [x] Подтвердить GitHub CI финального среза: [PR #3](https://github.com/yergaliuly/QuorumLab/pull/3) merged, PR run 42657904435 и [main run 43712610759](https://github.com/yergaliuly/QuorumLab/actions/runs/43712610759) прошли Ubuntu/Windows.

**Gate M0 выполнен 15 ноября:** локальный clean/test/build, 60 случаев, интерфейсы/ADR и реальное поведение scheduler; CI финального среза и main после merge подтверждены. Тесты протокольных переходов узла относятся к M2, как и реализация RaftCore.

**Задачи:** QL-001…QL-004. **Не входит:** рабочие выборы, полная БД, dashboard.

## M1 — Storage

**Результат:** собственный WAL и durable metadata с однозначным recovery.

- [x] Зафиксировать binary format/version/CRC32C и максимальные длины records: [формат v1](superpowers/specs/2026-11-15-wal-codec-design.md), big-endian, payload <=1 MiB, CRC по header+payload.
- [x] Реализовать encode/decode и записи `SET_HARD_STATE`, `APPEND_ENTRY`, `TRUNCATE_SUFFIX`, `SET_COMMIT_INDEX`: 69 новых тестовых случаев, включая golden vectors, corruption и incomplete prefixes. Файловая запись ещё не реализована.
- [x] Реализовать durable append и atomic metadata batch: FileWal + WalStorage, успех после WAL/temp/directory force.
- [x] Определить versioned checkpoint/UUID/byte offset и восстановление одного активного сегмента; commit не может ссылаться на отсутствующую запись. [ADR-003](adr/0003-wal-checkpoint-and-recovery.md).
- [x] Различать suffix за checkpoint и corruption любого байта durable prefix; при последней startup FAIL_CLOSED, без fallback на temp.
- [x] Проверить metadata formats, partial/zero writes, model force/crash boundaries, 15 реальных JVM halt boundaries, ENOSPC через /dev/full и seeded replay на ext4.
- [x] Подтвердить CI PR #5 43718265515 и main 43718416419 после merge eabf1b3: оба jobs Ubuntu/Windows success.

**Gate:** WAL roundtrip, HardState persistence и crash-recovery tests проходят; восстановленное состояние совпадает с моделью. Гарантии ограничены проверенной моделью fsync, а не произвольным hardware power loss.

**Gate M1 выполнен:** Windows/WSL clean test build успешны, Linux 212 случаев без ошибок/пропусков; CI PR #5/main прошли, merge подтверждён. Первый Linux run обнаружил lock bug и /tmp на tmpfs; исправления подтверждены ext4 run-aBKvD37K.

**Задачи:** QL-005…QL-006; часть T-12/T-13/T-17.

## M2 — Raft Election

**Результат:** три simulated nodes выбирают лидера с устойчивыми term/vote.

- [x] Реализовать ElectionCore follower/candidate/leader/failed, randomized timeouts и election-only heartbeat scheduling.
- [x] Реализовать RequestVote, fixed membership и log freshness по (term,index).
- [x] Сохранять term/vote до зависимых сообщений; сериализовать persist, отменять устаревшие replies/campaign, проверять IDs и distinct votes.
- [x] Реализовать step-down при большем term, same-term leader contact и recovery после crash; реальные FileWal integration tests.
- [x] Проверить split vote, повторные выборы, 2/3 vs 1/3, isolate/heal, election safety и 100 seeded fault trials на виртуальном runtime.
- [ ] Подтвердить CI PR/main после пользовательского push/merge M2.

**Gate:** election/step-down/split-vote/restart tests проходят; не бывает двух легитимно избранных лидеров одного term. Живость проверяется после стабилизации связи.

**M2 gate выполнен:** 26 core + 14 simulator + 4 Linux integration новых cases; Linux 256/256, Windows 230 executed + 12 Linux methods skipped. Свидетельство run-WVW1bqw8, [ADR-004](adr/0004-deterministic-leader-election.md). PR #6 merged, CI PR/main Ubuntu/Windows success. В M3 временный heartbeat заменён полным AppendEntries.

**Задачи:** QL-007…QL-009; T-01/T-02.

## M3 — Log Replication

**Результат:** реплицируемый журнал с корректным commit и apply.

- [x] Реализовать AppendEntries, проверку prevLogIndex/Term и восстановление конфликтующего неподтверждённого suffix.
- [x] Вести `nextIndex`/`matchIndex`; корректно обрабатывать повторы и запоздавшие ответы.
- [x] Связать положительные RPC-ответы с завершённым durable persist.
- [x] Реализовать current-term commit rule, сохранение commit metadata и ordered apply.
- [x] Добавить NO_OP нового лидера; проверить возврат старого лидера и сохранность committed prefix.
- [x] Добавить Figure 8 regression и assertions log matching/leader completeness/state-machine safety.

**Локальный gate выполнен:** Figure 8/safety simulation и real-WAL integration прошли; mutation probe без current-term guard дал ожидаемые падения (commit 2 вместо 0). WSL/ext4 run-AIH1CRxD: 287/287, Windows XML 256 non-skipped + 17 skipped. [ADR-005](adr/0005-durable-log-replication.md), [объяснение](REPLICATION.md). Remote gate ожидает CI/merge.

**Задачи:** QL-010…QL-012; протокольная часть T-03…T-08.

## M4 — KV и Client

**Результат:** линейризуемая state machine и клиентский SDK поверх работающего протокола.

- [ ] Закрыть O-05…O-08: семантика отсутствующих ключей, revisions, последовательности, ответы и неизвестный исход.
- [ ] Реализовать PUT, GET через журнал, DELETE, CAS, tombstones и защиту от ABA.
- [ ] Реализовать dedup по clientId/seq/hash с сохранением результата, включая CAS_MISMATCH.
- [ ] Реализовать лимиты ключей/значений/сессий и детерминированное поведение при их достижении.
- [ ] Реализовать клиентскую последовательность с одним in-flight, повтор того же запроса и rediscovery лидера.
- [ ] Ввести invocation/completion history и независимую KV-модель с коротким linearizability checker.
- [ ] Проверить последовательные и конкурентные операции, retry после потерянного ответа и recovery дедупликации.

**Gate:** end-to-end линейризуемые сценарии и retry tests проходят в тестовом runtime; T-09…T-11 и KV-часть T-03…T-06. Реальный HTTP/gRPC transport и три процесса проверяются в M5.

**Позднее отдельное решение:** ReadIndex. Для первой корректной версии достаточно согласованного log-based GET.

## M5 — Real Cluster

**Результат:** три отдельных JVM с реальными RPC, HTTP API и Docker Compose.

- [ ] Описать versioned Protobuf envelope, cluster/node identity и проверки membership.
- [ ] Подключить gRPC peers и HTTP adapter с API v1 и согласованной картой ошибок.
- [ ] Реализовать runtime event loop, управление очередями/backpressure, reconnect, graceful stop и диагностику ошибок диска.
- [ ] Проверять clusterId/dataDir при запуске; выделить каждому узлу собственные порты и каталог.
- [ ] Добавить status, структурированные логи и метрики role/term/leader/commit/applied/RPC/latency/failure.
- [ ] Подготовить Docker Compose и multiprocess/Testcontainers tests в изолированной сети.
- [ ] Проверить один отказ, потерю большинства, возвращение старого лидера и restart всех узлов.

**Gate:** три процесса выполняют API-сценарии; при отказе одного работающая пара продолжает прогресс, при недоступном quorum нет ложного успеха. Подтверждённые данные переживают restart. T-03…T-11/T-18 проверены на применимых реальных сценариях.

## M6 — Snapshot и Compaction

**Результат:** durable checkpoint и догоняющий follower без бесконечного хранения всего лога.

- [ ] Зафиксировать snapshot format и протокол установки, вопросы O-10.
- [ ] Сохранять согласованный state-machine image с index/term, revisions/tombstones и dedup results.
- [ ] Реализовать temp write → force → manifest/rename с проверенным durable ordering.
- [ ] Выполнять compaction только после durable checkpoint; корректно сохранять последующий suffix.
- [ ] Реализовать InstallSnapshot, chunk validation и восстановление после прерванной установки.
- [ ] Проверить crash на границах записи/rename/compaction, restore и далеко отставшего follower.

**Gate:** snapshot crash matrix, восстановление KV/dedup и InstallSnapshot проходят. T-14/T-15; повторная проверка T-09/T-10/T-18 с snapshots.

## M7 — Полный Chaos Lab

**Результат:** расширенная детерминированная лаборатория на уже используемом тестовом runtime.

- [ ] Довести virtual clock/network/disk и порядок событий до полного воспроизводимого контракта.
- [ ] Добавить drop/delay/duplicate/reorder и направленные partitions, pause/resume/crash/restart.
- [ ] Моделировать buffered/durable disk state, write delay/failure/ENOSPC/partial tail.
- [ ] Моделировать клиентские timeout/retry/lost response и неизвестные исходы.
- [ ] Сохранять seed, полный trace, client history, build/config manifest и checker report.
- [ ] Проверять инварианты при выполнении, сохранять каждый найденный counterexample как regression.
- [ ] Добавить replay assertion, PR smoke и nightly suite с фактическим учётом seeds/времени.

**Gate:** сохранённые counterexamples воспроизводятся на том же build; T-16 и применимые fault scenarios проходят. Цели: PR 100 seeds + 10 regression traces, nightly ≥10 000 seeds. Недостигнутый бюджет отмечается явно. Минимизация trace — дополнительное расширение, не замена сохранению исходной трассы.

## M8 — Deep Validation

**Результат:** независимая проверка гарантий и воспроизводимые измерения.

- [ ] Интегрировать внешний checker (Knossos/Jepsen или Porcupine), сверить формат и семантику неизвестных исходов.
- [ ] Провести реальные network fault, disk failure и многопроцессные recovery-сценарии.
- [ ] Пройти всю T-01…T-18 acceptance matrix, включая негативные условия.
- [ ] Запустить нагрузку: concurrency 1/8/32/64, разные GET/PUT, значения 128 B/1 KiB/64 KiB, без/с faults.
- [ ] Измерить throughput, median/p95/p99, retries, queue depth, CPU/RAM, fsync/election/recovery и bytes/op.
- [ ] Опубликовать benchmark manifest: железо, ОС, JVM, storage, конфигурация, workload, commit и raw results.
- [ ] Описать фактическое coverage, достигнутые gates и оставшиеся ограничения.

**Gate:** обязательные тесты проходят, нет известных нерешённых safety/linearizability violations, внешняя проверка и результаты воспроизводимы. Скоростные ориентиры не достигаются ослаблением correctness.

## M9 — Showcase

**Зависимость:** M4–M8 пройдены.

- [ ] Создать React/TypeScript dashboard: выборы, роли/термы, лог, commit/applied, метрики, сценарии и trace replay.
- [ ] Завершить README, architecture/consistency/failure model, ADR, benchmark methodology и ограничения.
- [ ] Подготовить воспроизводимый demo script и видео.
- [ ] Проверить запуск и демонстрацию по README человеком, не знакомым с проектом.

**Gate:** новый reviewer воспроизводит демо; интерфейс показывает действительные данные и результаты проверки.

Демо из ТЗ: запуск 3 узлов → PUT/GET → отключение лидера → переизбрание и сохранность записи → изоляция нового лидера и отсутствие ложного успеха → восстановление сети → replay regression trace → history checker/Figure 8 → restart всех узлов/WAL recovery → benchmark report.

## Первые 12 задач

Это локальные записи, **не созданные GitHub Issues**. Идентификаторы сохранены из ТЗ.

| ID | Этап | Работа | Критерий результата | Статус |
|---|---|---|---|---|
| QL-001 | M0 | Scope, API semantics, failure model, ADR-001 | Согласованы границы, открытые контракты не скрыты, ADR оформлен | Выполнено в объёме M0: ADR-001; отложенные API/WAL-детали явно отнесены к M1/M4+ |
| QL-002 | M0 | Gradle multi-module и CI | Wrapper/test работают; workflow подтверждён после появления remote | Выполнено: PR #1 merged, CI 42643809327 Ubuntu/Windows success |
| QL-003 | M0 | RaftEvent, Effect, DTO, clock abstraction | Зафиксированы интерфейсы и порядок выполнения зависимых эффектов | Начальный объём выполнен: ADR-002 и 36 тестовых случаев; расширение контрактов в M1–M6 |
| QL-004 | M0 | Детерминированный event scheduler | Повтор событий даёт тот же порядок и результат | Выполнено: 8 тестов, включая повтор trace и равновременные события |
| QL-005 | M1 | WAL binary format и CRC32C codec | Roundtrip и негативные format/checksum tests | Выполнено: 69 случаев, PR #4 merged d00dcd7, CI main 43715944608 success |
| QL-006 | M1 | Persist/replay HardState и WAL recovery | Crash/restart не создают ложного durable state | Выполнено: PR #5 merged, CI PR/main success |
| QL-007 | M2 | Переходы follower/candidate/leader | Transition и step-down tests | Выполнено, PR #6 и CI PR/main success |
| QL-008 | M2 | RequestVote и term/vote ordering | Голос не отправляется до завершения требуемого persist | Выполнено, PR #6 и CI PR/main success |
| QL-009 | M2 | Election safety и split vote | T-01/T-02 и restart tests | Выполнено, PR #6 и CI PR/main success |
| QL-010 | M3 | AppendEntries consistency и append | Conflict repair сохраняет committed prefix | Локальный gate M3 пройден; CI/merge ожидаются |
| QL-011 | M3 | nextIndex/matchIndex | Репликация/повторы/запаздывающие ответы корректны | Локальный gate M3 пройден; CI/merge ожидаются |
| QL-012 | M3 | Current-term commit и Figure 8 | Regression ловит неправильное правило commit | Локальный gate M3 пройден; CI/merge ожидаются |

Подробные issue-срезы для M4–M9 формируются перед соответствующим этапом. Дополнительные номера и сроки сейчас не выдумываются.
