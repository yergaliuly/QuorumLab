# M2: выборы лидера

Основание: прямой запрос пользователя реализовать M2 после merge PR #5, код/проверки выполняет ассистент, push — пользователь. M1 принят eabf1b3; CI PR 43718265515 и main 43718416419, Ubuntu/Windows jobs success. Одна ветка feat/m2-leader-election, существующий Windows checkout, один исполнитель. Решения ниже — детали реализации ассистента внутри согласованного scope, не отдельное пользовательское утверждение каждого DTO.

## Подходы

1. Детерминированный ElectionCore с effects и минимальным виртуальным runtime — выбран: сохраняет pure core, позволяет проверить гонки persistence без sleep и сети.
2. Сразу реальный трёхпроцессный кластер — требует transport/runtime M5 и затрудняет воспроизведение каждой гонки, отложен.
3. Сразу полный AppendEntries/log replication — расширяет M2 до M3, отложен. Heartbeat M2 — только внутренний сигнал term/leader contact без response, log mutation, matchIndex, commit или quorum lease. M3 заменит его настоящим AppendEntries с проверкой prevLogIndex/Term.

## Интерфейсы

- ElectionConfig: self, ровно три distinct voters, положительная incarnation, election timeout [min,max), heartbeat interval >0 и <min. Voters копируются в стабильном порядке; peer identity из RpcReceived проверяется до обработки term.
- LogPosition(index,term): пустой (0,0), иначе оба положительны. При старте commitIndex<=lastLogIndex и lastLogTerm<=currentTerm. Лог в M2 не изменяется.
- ElectionCore implements RaftCore, входы прежние плюс Start; выходы PersistHardState/SendRpc/ScheduleTimer. Status содержит role, volatile/durable HardState, leader hint, pending persist, votes и failure reason. Роли FOLLOWER/CANDIDATE/LEADER/FAILED.
- Внешний RandomSource выбирает таймауты; seeded реализация находится в simulator, JDK RNG в core запрещён. Одинаковые входы и последовательность RNG дают одинаковые effects. Счётчик OperationId общий для timer/persist в пределах incarnation.

## Переходы и persistence

Start один раз заводит election timer. Актуальный timeout начинает следующий term, self-vote и persist. RequestVote рассылается только после matching completion. До этого голоса не учитываются. Majority для трёх узлов — self + один distinct peer; дубликаты/старые ответы не дают дополнительных голосов. Leader сразу отправляет heartbeat и заводит heartbeat timer. Stale timer IDs/kinds игнорируются.

RequestVote проверяет свежесть лога лексикографически по (lastLogTerm,lastLogIndex), возможность голосования в этом term и membership. Новый vote сохраняется до granted response. Повтор того же durable vote может отвечать сразу. Отказ не сбрасывает election timer; выдача голоса и неустаревший heartbeat сбрасывают. При более высоком term в любом RPC — follower, vote очищается, term сохраняется; same-term heartbeat также останавливает candidate. Понижения term нет.

Максимум один persist in flight. Core держит desired HardState и durable HardState отдельно. Более высокий term обрабатывается немедленно даже во время I/O; старое завершение только обновляет durable snapshot, затем выпускает следующий persist нужного состояния. Устаревшая рассылка/ответ отменяются. Pending reply занимает один слот; ответы без актуального сохранения могут теряться как в ненадёжной сети, не накапливаются в безграничную очередь. При записи уже выбранного vote другие кандидаты получают отказ только когда текущий term durable; grant первого кандидата не затирается отказом второму.

Election timeout во время persist перевзводится без ещё одного term/self-vote. PersistFailed matching ID останавливает участие (FAILED), поскольку исход неизвестен; restart только из восстановленного storage с новой incarnation. Старые completions/failures не влияют на новый процесс. Overflow term/ID или неисправный RNG также останавливают core, не разрешая частичный список эффектов.

Изоляция лидера не обязана немедленно менять его локальную роль: старый и новый лидеры могут наблюдаться в разных terms. Safety: не два разных избранных лидера в одном term. В M2 нет клиентских операций, lease, pre-vote или check-quorum.

## Виртуальный runtime и проверки

ElectionSimulation использует существующий DeterministicScheduler: stable ordering, timers, RPC delay, disk write delay и отдельный completion delay. Состояние storage сохраняется между restart; crash явно выбирает discard/persist pending write. Старые timer/persist envelopes фильтруются incarnation, старые сетевые RPC могут приходить после restart и проверяются алгоритмом. Есть isolate/heal, injected duplicate/delayed RPC и storage failure с известным тесту исходом. Trace содержит seed-driven эффекты/события, а не wall clock. Это минимальный election harness, не общий M7 fault engine.

Core tests проверяют transitions, freshness, persist barriers, competing requests, higher-term during in-flight persist, stale timers/completions, denial flooding, storage failure и recovery. Simulator: stable election, split vote/re-election, isolation/heal, 2/3 versus 1/3, restart, repeatable traces, seeded fault smoke с election safety assertion на каждом событии. Linux integration: настоящий FileWal + WalStorage + ElectionCore, crash/reopen до доставки completion, запрет второго vote после restart, затем новая election.

Gate M2 — core/simulator/integration tests плюс Windows build и fresh WSL/ext4 build; затем пользовательский push/CI/merge. Не обещаем ограниченное время выборов при произвольных задержках или завершённый Raft/KV.

Алгоритмический источник: [Raft extended paper, Figure 2, §5.2/5.4.1](https://raft.github.io/raft.pdf); архитектурный контракт — ADR-002, storage — ADR-003.
