# Файловый WAL, checkpoint и recovery (QL-006)

Основание: прямой запрос пользователя реализовать следующий срез M1 в одной ветке. Пользователь поручил код/тесты ассистенту, push оставил себе. Ниже — решения реализации ассистента внутри согласованной архитектуры собственного WAL, а не отдельно утверждённые пользователем детали формата.

## Подходы и выбор

- Атомарно заменяемый checkpoint: WAL force → checkpoint temp force → atomic rename → directory force. Небольшой формат и однозначная граница; требует проверенного Linux provider и синхронизации каталога. Выбран для M1.
- Append-only журнал checkpoint: ещё один журнал и отдельная проблема отличия оборванного последнего маркера от повреждённого подтверждённого. Не выбран.
- Два чередующихся слота: меньше rename, но fallback на старую копию при повреждении новой может откатить уже подтверждённое состояние. Не выбран.

## Контракт и файлы

Один активный append-only сегмент `wal-0000000000000001.log`; ротация/compaction/snapshot остаются будущей работой. `checkpoint` фиксирует UUID сегмента, generation и exclusive byte offset. `checkpoint.tmp` никогда не используется как источник recovery. `writer.lock` не удаляется; OS file lock удерживается весь срок работы экземпляра. Каталог уже существует, родительская иерархия подготовлена вызывающей стороной; create синхронизирует родителя и каталог. create/open различаются: open никогда не создаёт пустой WAL взамен пропавшего.

Все числа big-endian. Заголовок сегмента: `QSEG:4, version:u16=1, UUID:16, CRC32C:4` (26 bytes). Checkpoint: `QCHK:4, version:u16=1, UUID:16, generation:i64, offset:i64, CRC32C:4` (42 bytes). CRC покрывает все предшествующие байты. Начальная generation=0, offset=26. WAL records сохраняют формат QL-005. UUID связывает файлы; это не clusterId/nodeId. Checkpoint offset — байты, Raft commitIndex — логические записи.

`FileWal.create/open(Path)` возвращает синхронный ресурс AutoCloseable. `persist(List<WalRecord>)` сначала проверяет весь непустой пакет и кодирует записи без I/O. Затем последовательно пишет все байты с обработкой short writes, force(true) WAL, пишет/force/закрывает temp, atomic replace checkpoint, force каталога. Только затем публикует новое immutable WalState и возвращает его. IOException оставляет исход неизвестным, отравляет экземпляр и запрещает дальнейшие операции до close/reopen. Нет fallback к non-atomic move или игнорирования force errors. Неправильный пакет отвергается до I/O без отравления.

Публичный адаптер WalStorage переводит существующий PersistHardState в один пакет term/vote + commit и добавляет ровно одно PersistCompleted/PersistFailed с исходным OperationId в очередь driver. Ядро напрямую не вызывается. Runtime/выборы/RPC пока отсутствуют.

## Replay и tail policy

1. Захватить lock, прочитать и проверить checkpoint и заголовок сегмента: version, exact size, CRC, UUID, bounds; missing/invalid → IOException/FAIL_CLOSED.
2. Streaming replay только `[26, offset)`: не загружать целый WAL, проверять header/length до allocation, CRC и логическую историю каждой записи. Offset обязан быть границей полного record. Повреждение любого байта подтверждённого префикса, включая последнюю запись, не лечится обрезкой.
3. Только после успешной проверки префикса обрезать все байты за checkpoint (полные, неполные или испорченные), force WAL, удалить stale temp, force каталога. Это явно неподтверждённый suffix, не Raft committed suffix. Возврат восстановленного состояния — после этих действий.
4. Если crash случился после rename, но до ACK, допустимо восстановить целый новый пакет или старый checkpoint (в модели сбоя ОС до directory force). Отсутствие ACK не означает отсутствие записи. После ACK весь пакет обязателен. Нельзя выдавать частично применённый пакет.

Лог начинается с index=1, последующие append строго последовательны; term записей не убывает и не превышает currentTerm. Term и commitIndex не уменьшаются; уже выданный vote в том же term нельзя снять/сменить. Commit не выходит за конец лога. TruncateSuffix разрешён только выше commitIndex и не дальше lastIndex+1. Проверка current-term commit rule, кворума и apply принадлежит будущему Raft, не Storage.

## Проверки и границы

JUnit: metadata corruption/version/bounds; state transitions; реальные операции production FileWal с injectable package-private I/O model; short/zero writes, write/force/rename/directory failures, ENOSPC injection; crash snapshots различают volatile bytes, forced file bytes и durable directory entries. Проверить до/после каждого barrier и restart, атомарность metadata batch, отсутствие completion до durable barrier, poison/lock/cleanup.

Linux tests: реальные FileChannel/force/atomic move/lock и отдельный JVM halt на границах протокола. Запуск через scripts/test-wsl.sh на свежем ext4 snapshot без build cache. На Windows переносимые model/codec тесты выполняются; Linux filesystem tests явно skipped. Публичный адаптер не содержит слабого Windows режима, невозможность directory force — ошибка. Физический power loss, ложь накопителя о flush, bit rot всего корректного checkpoint с заменой на старую валидную копию и враждебные внешние изменения каталога не доказаны этой проверкой. Production I/O не попадает в core.

Источники: [FileChannel Java 21](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/nio/channels/FileChannel.html), [Files.move](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/nio/file/Files.html#move(java.nio.file.Path,java.nio.file.Path,java.nio.file.CopyOption...)), [Linux fsync](https://man7.org/linux/man-pages/man2/fsync.2.html).
