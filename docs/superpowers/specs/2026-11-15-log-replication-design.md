# M3: репликация журнала

Пользователь разрешил M3 (QL-010…012): один локальный branch/PR, код и проверки выполняет ассистент, push — пользователь. Ниже технические решения реализации в этих границах, не отдельно согласованные новые функции.

## Решение

Расширяем существующий алгоритм выборов до `RaftNode`, конфигурацию до `RaftConfig`, стенд до `RaftSimulation`. Pure Java core остаётся event/effect machine. `LogEntry` и `RaftState` описывают весь восстановленный журнал; формат WAL v1 сохраняется. `PersistBatch` атомарно сохраняет term/vote, optional truncate, append и commit через существующий checkpoint-протокол.

Полный `AppendEntries` заменяет временный M2 heartbeat. Проверяем prev index/term даже для пустого запроса, удаляем только конфликтующий uncommitted suffix. Commit follower ограничен концом проверенного запроса. Success выпускается после durable completion. При pending persist новый append-body отбрасывается без ответа; higher term всё равно обрабатывается и сохраняется следующим batch. Это ограничивает память и упрощает доказательство ordering.

Лидер сохраняет NO_OP своего term, затем реплицирует. На peer один outstanding запрос с OperationId; таймер повторяет тот же запрос. Поздние ответы не меняют progress нового запроса. Backtracking по одному индексу; batches до 64 entries и 1 MiB entry payload. Конвейер и ускорение conflict term отложены. Commit продвигается только к записи current term с durable большинством 2/3, затем сохраняется локально и применяется по порядку.

`ApplyCommittedEntries`/completion отделяет выдачу работы от lastApplied. Один apply batch в полёте. После restart volatile state machine повторно получает committed prefix с индекса 1; snapshots и KV появятся позже. `Propose` — внутренний тестовый вход opaque command: queued означает лишь приём в работу, не клиентский успех. NOT_LEADER/BUSY/FAILED явны. Клиентские requestId/dedup/read semantics относятся к M4.

## Проверки

Unit tests: immutable/validated DTO; persist-before-append-response; prev mismatch; conflict repair; committed prefix protection; stale responses; NO_OP; current-term commit; durable ordered apply; failure/restart. Сохраняются M2 election tests.

Симулятор хранит durable log, отделяет write/completion/apply, проверяет election safety, log matching, leader completeness, committed-prefix preservation и state-machine safety. Figure 8 воспроизводится для трёх фиксированных voters: A копирует старую term-2 запись на B, но не может commit до current-term NO_OP; после сбоя A узел C с term-3 suffix выигрывает и заменяет старую uncommitted запись. Это эквивалентная причина ошибки, не буквальная пятиузловая схема статьи.

Интеграция с реальным FileWal проверяет append/truncate/commit/recovery и очередность effects. Финальные gates: Windows clean test build; scripts/test-wsl.sh на свежем ext4 snapshot; фактические XML counts/manifest. Эти проверки не доказывают физический power-loss на произвольном диске или full distributed linearizability.
