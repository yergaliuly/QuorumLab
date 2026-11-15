# ADR-004: детерминированные выборы и persistence barrier

Дата: 2026-11-15. Статус: принято для M2; локальные проверки пройдены, remote CI ожидает пользовательского push.

Основание: пользователь прямо поручил реализовать M2 после merge M1. Сохраняем ADR-001 fixed membership, ADR-002 event/effects и ADR-003 durable storage. Конкретные DTO/политики ниже — решения реализации ассистента в этом объёме. [Дизайн/альтернативы](../superpowers/specs/2026-11-15-leader-election-design.md).

## Решение

1. ElectionCore реализует RaftCore как stateful pure Java machine. Driver последовательно подаёт Start, TimerFired, RpcReceived, PersistCompleted/Failed. Ядро возвращает immutable effects; не выполняет I/O, sleep, clock или создание RNG. Config требует ровно три distinct voters, self входит в состав, incarnation положительна. Большинство — два разных участника.
2. Recovered HardState и LogPosition задают старт FOLLOWER; commit<=lastLogIndex, lastLogTerm<=currentTerm и votedFor принадлежит группе. В M2 log/commit не изменяются. Свежесть candidate log сравнивается сначала по lastLogTerm, затем по lastLogIndex. Peer membership проверяется до обработки term; это не аутентификация сети.
3. Election timeout выбирается через injected RandomSource в [min,max); heartbeat interval положителен и меньше min. SeededRandomSource живёт в simulator. Determinism требует тех же inputs, RNG stream, конфигурации и build; один seed не задаёт все сбои сети.
4. На актуальный election timer узел увеличивает term, выбирает self-vote, становится CANDIDATE, перевзводит timer и выпускает persist. Рассылка RequestVote и учёт собственного голоса происходят только после matching completion. До этого входящие votes не учитываются. Set<NodeId> исключает повторный подсчёт. При большинстве — LEADER, немедленный heartbeat и heartbeat timer.
5. Новый term любого RPC означает FOLLOWER и очищение vote. Это происходит сразу в volatile state, даже при незавершённой записи. Durable state обновляется только matching PersistCompleted. В каждый момент не больше одного persist in flight; после старого completion сначала сохраняется последнее desired state, а прежние dependent messages отменены. Same-term heartbeat также прекращает campaign.
6. Grant требует свежего лога и отсутствия другого vote. Если vote/term меняются, positive response ждёт durable barrier; повтор уже durable vote отвечает сразу. Отказ не перевзводит текущий election timer. При переходе из LEADER без election timer он заводится. Grant/неустаревший heartbeat сбрасывают таймер. Stale IDs/kinds, old terms и чужие completion/failure игнорируются.
7. Pending reply занимает один слот; blocked ответы могут отбрасываться как сетевые потери, не образуют неограниченной очереди. Отказ конкурирующему кандидату не затирает выбранный pending grant. Timeout во время persist перевзводится без нового term/write. Matching PersistFailed → FAILED до recovery; успешное завершение после этого не оживляет узел. Overflow term/ID и сбой RNG также останавливают участие.
8. Heartbeat M2 — временный election-only RPC (term и sender), без response, log consistency check, matchIndex, commit, lease или quorum evidence. Это **не полноценный AppendEntries**. В M3 он заменяется AppendEntries с prevLogIndex/Term и корректной репликацией. Не добавляем сейчас pre-vote, check-quorum, leader transfer или dynamic membership.
9. Минимальный ElectionSimulation использует M0 scheduler и persistent metadata model. Write и completion — отдельные события; crash явно выбирает outcome незавершённой записи. Timers/completions фильтруются incarnation, старые RPC могут доставляться после restart. На каждом отправленном RPC проверяется durable term/vote, при каждом избрании сохраняется history term→leader; два разных узла в одном term — test failure.
10. Изолированный старый LEADER может сохранять роль в старом term, когда большинство избрало нового в следующем term. Это не S-01 violation и не разрешение обслуживать клиентские запросы. Клиентского API в M2 нет.

## Последствия и границы

Выбрана виртуальная модель вместо реальной сети/процессного кластера, чтобы управлять каждой гонкой. Реальный FileWal проверяется отдельными Linux integration tests через WalStorage, но полноценный runtime M5 отсутствует. Allocator incarnation вне тестового driver ещё предстоит; восстановленные metadata требуют новой incarnation при каждом запуске. Нет bounded election time при произвольной задержке, потерях, disk stall или постоянных сбоях.

Локальный gate: 26 core, 14 simulator и 4 Linux integration новых cases; Windows 230 выполненных + 12 skipped Linux methods, WSL/ext4 256/256. Seeded smoke выполняет 100 независимых сценариев внутри одного JUnit case; это экспериментальное свидетельство, не формальное доказательство Raft. Существующие WAL/architecture tests прошли. Подробности — [ACCEPTANCE](../ACCEPTANCE.md).

Алгоритмический источник: [Raft extended paper, Figure 2, §5.2, §5.4.1](https://raft.github.io/raft.pdf). Реализация асинхронного persistence barrier конкретизирует архитектуру проекта; heartbeat M2 явно ограничен по сравнению с полным протоколом статьи.
