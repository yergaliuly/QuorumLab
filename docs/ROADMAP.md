# Дорожная карта QuorumLab

Основание: [ТЗ v1.0, разделы 8–11](TECHNICAL_SPECIFICATION.md). Статус на 7 ноября 2026: **M0 — учебная подготовка окружения; M1–M9 не начаты**. Пользователь подтвердил Java 21/Git и выбрал VS Code; Linux-окружение и сборка ещё не проверены. Подготовка документов не закрывает M0. Чекбоксы обозначают будущие результаты, а не выполненную работу.

## Порядок и зависимости

`M0 → M1 → M2 → M3 → M4 → M5 → M6 → M7 → M8 → M9`.

Тесты и минимальная симуляция развиваются вместе с алгоритмом с M0; M7 завершает расширенную лабораторию. Независимая KV-модель нужна уже для M4, внешний checker — в M8. Наблюдаемость добавляется по мере появления runtime; UI остаётся в M9.

Каждый этап закрывается только по своим проверкам. Несколько небольших PR допустимы внутри этапа; после завершения можно отметить milestone/release. [GitHub-репозиторий](https://github.com/yergaliuly/QuorumLab) создан пользователем; до переноса задач в Issues эта страница служит локальным backlog. Оценка исходного ТЗ 12–20 недель — предварительный ориентир, без обещания дат.

## M0 — Foundation

**Результат:** воспроизводимая сборка и проверяемые границы компонентов, на которых дальше строится Raft.

- [ ] Разобрать O-01…O-03, согласовать дизайн M0 и оформить ADR-001 scope/consistency/failure model.
- [ ] Проверить Java 21 и целевое Linux-окружение; выбрать совместимые версии Gradle и тестовых инструментов.
- [ ] Создать Gradle Kotlin DSL multi-module project и Wrapper; зафиксировать структуру из ТЗ, без реализации поздних функций.
- [ ] Определить `RaftEvent`, `Effect`, immutable DTO, `Storage`, `Transport`, виртуальное время и источник случайности.
- [ ] Описать зависимости persist/send/apply, события `PersistCompleted`/`PersistFailed`, границы ошибок.
- [ ] Реализовать минимальный детерминированный event scheduler с устойчивым порядком равновременных событий и первый тест перехода узла.
- [ ] Подготовить CI skeleton и правило отсутствия framework/I/O-зависимостей в core. После создания GitHub подтвердить выполнение CI.

**Gate:** `./gradlew clean test` работает локально, интерфейсы и ADR зафиксированы, первый тест проверяет реальное поведение scheduler/core. На Windows эквивалент — `gradlew.bat clean test`. Эти команды пока не существуют. Полный CI gate не считается пройденным до фактического запуска workflow в GitHub.

**Задачи:** QL-001…QL-004. **Не входит:** рабочие выборы, полная БД, dashboard.

## M1 — Storage

**Результат:** собственный WAL и durable metadata с однозначным recovery.

- [ ] Зафиксировать binary format/version/CRC32C и максимальные длины records.
- [ ] Реализовать encode/decode и записи `SET_HARD_STATE`, `APPEND_ENTRY`, `TRUNCATE_SUFFIX`, `SET_COMMIT_INDEX`.
- [ ] Реализовать durable append и сохранение term/vote/commit metadata в корректном порядке.
- [ ] Определить checkpoint/offset и восстановление segment state; commit не может ссылаться на недолговечную запись.
- [ ] Различать incomplete tail и corruption устойчивой середины; при последней завершать startup с FAIL_CLOSED.
- [ ] Проверить форматы, несовместимые версии, partial writes, force/crash boundaries, ENOSPC и replay на Linux.

**Gate:** WAL roundtrip, HardState persistence и crash-recovery tests проходят; восстановленное состояние совпадает с моделью. Гарантии ограничены проверенной моделью fsync, а не произвольным hardware power loss.

**Задачи:** QL-005…QL-006; часть T-12/T-13/T-17.

## M2 — Raft Election

**Результат:** три simulated nodes выбирают лидера с устойчивыми term/vote.

- [ ] Реализовать follower/candidate/leader, randomized election timeouts и heartbeat scheduling.
- [ ] Реализовать RequestVote и проверку актуальности candidate log.
- [ ] Сохранять term/vote до зависимых сообщений; учитывать delayed/duplicate responses.
- [ ] Реализовать step-down при большем term и восстановление term/vote после crash.
- [ ] Проверить split vote, повторные выборы и election safety на минимальном виртуальном runtime.

**Gate:** election/step-down/split-vote/restart tests проходят; не бывает двух легитимно избранных лидеров одного term. Живость проверяется после стабилизации связи.

**Задачи:** QL-007…QL-009; T-01/T-02.

## M3 — Log Replication

**Результат:** реплицируемый журнал с корректным commit и apply.

- [ ] Реализовать AppendEntries, проверку prevLogIndex/Term и восстановление конфликтующего неподтверждённого suffix.
- [ ] Вести `nextIndex`/`matchIndex`; корректно обрабатывать повторы и запоздавшие ответы.
- [ ] Связать положительные RPC-ответы с завершённым durable persist.
- [ ] Реализовать current-term commit rule, сохранение commit metadata и ordered apply.
- [ ] Добавить NO_OP нового лидера; проверить возврат старого лидера и сохранность committed prefix.
- [ ] Добавить Figure 8 regression и assertions log matching/leader completeness/state-machine safety.

**Gate:** Figure 8 и safety simulation проходят; нельзя получить ложный commit по числу копий записи старого term.

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
| QL-001 | M0 | Scope, API semantics, failure model, ADR-001 | Согласованы границы, открытые контракты не скрыты, ADR оформлен | Частично: материалы сохранены |
| QL-002 | M0 | Gradle multi-module и CI | Wrapper/test работают; workflow подтверждён после появления remote | Не начато |
| QL-003 | M0 | RaftEvent, Effect, DTO, clock abstraction | Зафиксированы интерфейсы и порядок выполнения зависимых эффектов | Не начато |
| QL-004 | M0 | Детерминированный event scheduler | Повтор событий даёт тот же порядок и результат | Не начато |
| QL-005 | M1 | WAL binary format и CRC32C codec | Roundtrip и негативные format/checksum tests | Не начато |
| QL-006 | M1 | Persist/replay HardState и WAL recovery | Crash/restart не создают ложного durable state | Не начато |
| QL-007 | M2 | Переходы follower/candidate/leader | Transition и step-down tests | Не начато |
| QL-008 | M2 | RequestVote и term/vote ordering | Голос не отправляется до завершения требуемого persist | Не начато |
| QL-009 | M2 | Election safety и split vote | T-01/T-02 и restart tests | Не начато |
| QL-010 | M3 | AppendEntries consistency и append | Conflict repair сохраняет committed prefix | Не начато |
| QL-011 | M3 | nextIndex/matchIndex | Репликация/повторы/запаздывающие ответы корректны | Не начато |
| QL-012 | M3 | Current-term commit и Figure 8 | Regression ловит неправильное правило commit | Не начато |

Подробные issue-срезы для M4–M9 формируются перед соответствующим этапом. Дополнительные номера и сроки сейчас не выдумываются.
