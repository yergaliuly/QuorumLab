# M2: как узлы выбирают лидера

> Исторический разбор соответствующего этапа. В M3 ElectionCore/ElectionConfig/ElectionSimulation расширены до RaftNode/RaftConfig/RaftSimulation; PersistHardState заменён PersistBatch, временный heartbeat — AppendEntries. Текущее поведение — в [REPLICATION](REPLICATION.md).

Теперь проект умеет выбирать лидера между тремя виртуальными узлами, переизбирать его после сбоя и восстанавливать term/vote. Это реализация election-части Raft; репликация команд, KV и настоящий сетевой runtime ещё впереди. [ADR-004](adr/0004-deterministic-leader-election.md) фиксирует решения, [ACCEPTANCE](ACCEPTANCE.md) — реальные проверки.

## Три роли и term

| Роль | Что делает наш ElectionCore |
|---|---|
| FOLLOWER | Ждёт leader contact или выдаёт один голос за term; без контакта запускает выборы |
| CANDIDATE | Сохраняет следующий term и self-vote, запрашивает голоса, ждёт большинство |
| LEADER | Периодически отправляет heartbeat; в M2 ещё не принимает клиентские команды |
| FAILED | Перестаёт выдавать эффекты после неизвестного исхода persistence; нужен restart/recovery |

Term — номер раунда протокола, а не время и не индекс записи. Узел может узнать о большем term из запроса или ответа и перейти в follower. Старый term не возвращается. `OperationId(incarnation,sequence)` решает другую задачу: отличает конкретную запись/таймер, включая операции прошлого запуска процесса.

## Выборы на примере A, B, C

Начальное состояние: все followers, term=0, vote отсутствует. Пусть первым истёк таймер A.

1. A становится candidate, выбирает term=1 и vote=A. Возвращает PersistHardState; RequestVote пока нет.
2. Storage сохраняет состояние и присылает PersistCompleted с тем же ID. Теперь A учитывает self-vote и отправляет RequestVote узлам B/C.
3. B проверяет, что не голосовал за другого кандидата и журнал A достаточно свежий. Сначала сохраняет vote=A, затем отвечает granted=true.
4. A получает голос B: A+B — два из трёх. A становится leader и рассылает heartbeat.
5. Получивший heartbeat follower перевзводит election timer. Если контакты прекратятся, новый timeout запускает следующий раунд.

Голос C уже не нужен для большинства. Повтор ответа B не добавляет ещё одного участника: голоса хранятся как Set<NodeId>. Старые term, неизвестные участники и собственные входящие RPC отвергаются. При split vote кандидаты ждут новые таймауты и пробуют следующий term.

## Зачем случайные таймауты и seed

Если каждый follower ждёт ровно одинаковое время, несколько кандидатов часто стартуют одновременно и голосуют за себя. RandomSource выбирает время из [min,max), распределяя попытки во времени. Это обычный приём Raft для выборов, а не обещание фиксированного времени завершения. [Алгоритмический источник](https://raft.github.io/raft.pdf).

Core знает только интерфейс RandomSource. Реализация SeededRandomSource в simulator использует JDK Random с явным seed. Например, одинаковый seed и одинаковый сценарий isolate/crash/restart дают ту же последовательность таймаутов и effects. Это позволяет повторить ошибку. Модель сети и порядок событий тоже должны совпасть; одного seed для любого окружения недостаточно.

Все длительности выражены в наносекундах, но в симуляторе это виртуальные значения. `runSteps(500)` обрабатывает максимум 500 событий, а не спит 500 наносекунд. Время двигает DeterministicScheduler: раньше deadline — раньше событие, равные deadlines — FIFO по insertionOrder.

## Почему мы разделили current и durable state

Представим гонку:

```text
A начал сохранять term=2, vote=A
→ до завершения пришёл RequestVote(term=4) от B
→ A уже FOLLOWER, current term=4, но запись term=2 ещё выполняется
→ completion старой записи обновляет только durable snapshot до term=2
→ A выпускает следующий persist(term=4, vote=B)
→ только completion этой записи разрешает VoteResponse(term=4, granted=true)
```

Старый completion не должен выпускать RequestVote(term=2). Поэтому ElectionCore хранит current HardState, последний подтверждённый durable HardState и одну pending operation отдельно. Реальный driver не запускает параллельные записи старых и новых metadata.

Если запись вернула ошибку, неизвестно, успела ли она сохраниться. Core переходит в FAILED; retry того же живого объекта запрещён логикой. После recovery создаётся новое ядро с восстановленными metadata и новой incarnation. Старое событие PersistCompleted не может подтвердить новую операцию.

## Свежесть журнала

Проверяется последняя пара (term,index), причём **term важнее длины**. Если локальный лог заканчивается на (term=3,index=10):

- кандидат с (2,100) отвергается, хотя его журнал длиннее;
- кандидат с (3,9) отвергается;
- кандидат с (3,10) подходит;
- кандидат с (4,2) подходит, несмотря на меньшую длину.

LogPosition при старте приходит из восстановленного storage. M2 его не меняет. Реальное добавление/исправление лога относится к M3.

## Таймеры и задержавшиеся события

Каждый перевзвод создаёт новый OperationId. Старый timer остаётся в очереди scheduler, но ядро его игнорирует. Это проще и надёжнее скрытой отмены уже доставляемого события. Отказ в голосе не сбрасывает текущий таймер: иначе поток устаревших запросов мог бы бесконечно откладывать выборы. При незавершённой записи timeout лишь перевзводится, не создавая второй persist.

Simulator также помечает локальные события incarnation. RPC могут пережить restart и проверяются по term; локальный timer/completion прошлого процесса отбрасывается. Это разные виды запоздавших сообщений.

## Что моделирует стенд

ElectionSimulation запускает три одинаковых ElectionCore, исполняет effects и держит durable metadata отдельно от состояния ядра. Есть задержки RPC, записи и доставки completion; isolate/heal; crash с явным выбором судьбы pending write; restart; injected storage failure. Перед каждым отправленным RPC стенд проверяет нужные durable term/vote. History term→leader ловит двух разных избранных лидеров в одном term.

После изоляции старого лидера A узлы B/C могут выбрать нового в большем term. A ещё может считать себя leader старого term: это допустимая локальная картина. После восстановления связи более высокий term заставит его уступить. Отсюда нельзя выводить право A обслуживать чтения/записи: клиентского API и quorum-backed serving пока нет.

Heartbeat M2 содержит только term и sender. Он не имеет подтверждения, не проверяет совпадение журналов и не доказывает кворум. В M3 его заменит полноценный AppendEntries. Это осознанная граница учебного этапа, а не законченная реализация Raft RPC.

## Проверки и следующий шаг

26 новых core cases и 14 simulator cases сначала падали на заглушках, затем прошли. Непредвиденных ошибок реализации в этих прогонах не возникло. Есть stable election, split vote, minority/majority, isolate/heal, higher-term during persist, stale IDs, failure и повторяемость traces. Один smoke-test выполняет 100 seeded сценариев; это не 100 отдельных JUnit cases и не математическое доказательство.

Четыре Linux integration tests связывают ElectionCore с настоящими FileWal/WalStorage: durable vote сохраняется после restart до доставки completion, второй vote в том же term не выдаётся, higher-term writes сериализованы, recovered log влияет на свежесть. Здесь restart моделируется пересозданием ядра и повторным открытием WAL в той же JVM; отдельные аварийные halt JVM проверяет существующий storage-набор M1. Test data находятся на ext4. Все 256 Linux cases и 230 portable Windows cases прошли; на Windows 12 Linux test methods skipped.

Новых внешних библиотек нет: Java 21, существующие JUnit/Gradle, очередь событий и seeded RNG. Production I/O осталось в storage; integration добавил только test dependencies core/storage. Полный сетевой кластер, allocator incarnation, pre-vote/check-quorum, log replication, commit/apply и KV ещё не реализованы. Следующий этап после CI/merge M2 — M3, репликация лога и current-term commit rule.
