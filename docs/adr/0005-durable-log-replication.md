# ADR-005: durable AppendEntries и ordered apply

Дата: 15 ноября 2026. Статус: реализовано в локальном M3 по запросу пользователя; remote gate ожидает push/CI. Уточняет ADR-002/004 в ранее заданном направлении Raft, без расширения v1.

## Контекст

M2 умел выбирать лидера, но временный heartbeat не сверял журналы. M1 уже умеет атомарный checkpointed batch с term/vote, truncate, append и commit. M3 должен связать эти части и проверять свойства репликации до появления KV и реальной сети.

## Решения

1. Один `RaftNode` расширяет бывший ElectionCore; `RaftSimulation` исполняет тот же алгоритм. В ядре нет I/O, времени ОС или новых внешних зависимостей. `RaftState` содержит восстановленные metadata и весь непрерывный журнал с индекса 1. Snapshot/compaction — M6.
2. AppendEntries содержит term, requestId, prev index/term, immutable entries и leaderCommit. Пустой запрос проходит ту же проверку prev. Matching short request не удаляет лишний suffix; truncate начинается только с первого различающегося term выше commit. Различные данные при одинаковых index/term или попытка заменить committed entry останавливают узел.
3. `PersistBatch` переводится WalStorage в один FileWal batch: SetHardState → optional TruncateSuffix → AppendEntry* → SetCommitIndex. Формат диска v1 не изменён. Success выпускается только после checkpoint/force completion. Неизвестный исход ошибки останавливает core до recovery.
4. В один момент один storage batch. Higher term немедленно отменяет старую campaign/ответ, затем сохраняется после уже начатой записи. Пока storage busy, append-body не принимается и не подтверждается; leader contact обновляет таймер, повтор обеспечит лидер. Это сознательный простой вариант вместо очереди конкурирующих log mutations.
5. Новый лидер сначала сохраняет NO_OP своего term. Один незавершённый RPC на peer; heartbeat повторяет тот же immutable RPC/id. Success обновляет progress только при matching id и точном конце запроса; stale negative игнорируется. При mismatch nextIndex уменьшается на 1, не ниже matchIndex+1. Ограничения: 1…64 entries и 1 MiB суммарных entry payload bytes. Это не будущий protobuf wire limit.
6. Лидер выбирает максимальный N выше commit, где log[N].term равен currentTerm и durable match есть у большинства 2/3. Сначала сохраняет commit metadata, затем объявляет новый leaderCommit и выдаёт apply. Follower commit ограничен последним проверенным индексом запроса, а не непроверенным локальным хвостом.
7. `ApplyCommittedEntries` выдаёт подряд до 64 записей из durable committed prefix; одновременно один apply batch. `lastApplied` меняется только на matching ApplyCompleted. Ошибка apply — FAILED. После restart volatile consumer начинает с нуля и повторяет committed prefix. Это пока collector/test port, не durable KV/snapshot и не exactly-once delivery.
8. `Propose(byte[])` — внутренний вход для opaque commands. ProposalQueued — только admission с index/term; BUSY/NOT_LEADER/FAILED явны. Клиентский успех, request identity, дедупликация и unknown outcome относятся к M4.

## Варианты и цена

Stop-and-wait выбран вместо pipelining ради явной корреляции и ограниченного состояния peer. Линейный backtracking проще conflict-term hints, но медленнее на длинном расхождении. Сериализация storage проще объединения конкурирующих log mutations, но добавляет повтор RPC при смене term. Эти ограничения подходят correctness-first этапу; throughput здесь не измерялся.

## Свидетельства и границы

Новые contracts/core/simulator/integration tests, сохранённые M2 regression tests и отрицательная mutation-проверка current-term guard описаны в [ACCEPTANCE](../ACCEPTANCE.md). Симулятор отдельно проверяет log matching, committed history, leader completeness при избрании и apply equality. Figure 8 адаптирован к трём voters; исходная иллюстрация статьи использует пять. [Raft paper, Figure 2 и §5.4](https://raft.github.io/raft.pdf).

Реальная сеть, отдельные процессы узлов, KV linearizability, snapshots и hardware power loss этим решением не закрыты.
