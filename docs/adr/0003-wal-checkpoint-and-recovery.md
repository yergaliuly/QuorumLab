# ADR-003: атомарный persist batch и восстановление WAL

Дата: 2026-11-15. Статус: принято для реализации QL-006; локальный gate пройден, GitHub CI нового среза ожидает push.

Основание: собственный WAL и durable checkpoint заданы ТЗ §6; пользователь прямо поручил реализовать файловый WAL/checkpoint/recovery в одной ветке. Конкретный протокол и форматы ниже — решения ассистента в этом направлении, не отдельные утверждения пользователя. [Дизайн и альтернативы](../superpowers/specs/2026-11-15-durable-wal-design.md).

## Контекст

Успешный write может означать только попадание байтов в кеш ОС. CRC определяет повреждение, но не говорит, была ли запись подтверждена. SET_HARD_STATE и SET_COMMIT_INDEX — отдельные frames, тогда как HardState ядра содержит оба вида метаданных. Нужна единая точка подтверждения пакета, чтобы recovery не публиковал его половину.

## Решение

1. Один активный append-only сегмент и атомарно заменяемый checkpoint. Checkpoint содержит UUID сегмента, generation и exclusive byte offset. Заголовок сегмента и checkpoint имеют version=1 и CRC32C; формат WAL frames из QL-005 не меняется. Ротация/compaction в этом срезе отсутствуют.
2. Пакет полностью валидируется/кодируется перед I/O. Все frames записываются с циклом short writes, затем WAL force(true), temp checkpoint write/force(true)/close, ATOMIC_MOVE поверх checkpoint, directory force. Только после последнего barrier новое состояние/успех доступны вызывающему коду. Обычного rename без ATOMIC_MOVE в fallback нет.
3. Любая ошибка после начала I/O означает неизвестный durable outcome; экземпляр блокирует state/persist до close/reopen. Нет автоматического retry на том же экземпляре и попытки откатить файл вслепую. Ошибочный пакет до I/O отвергается без изменения WAL.
4. Recovery требует валидных existing checkpoint/header и совпадения UUID. Читается только подтверждённый byte prefix; размер frame проверяется до выделения памяти, затем CRC и последовательность операций. Ошибка даже в последнем frame этого префикса → FAIL_CLOSED. Только после успешного replay удаляется весь suffix за checkpoint, включая полные/повреждённые/неполные frames, затем force WAL и directory. Stale checkpoint.tmp не является запасной копией.
5. Подтверждение обязательно переживает recovery в принятой модели. При ошибке/краше после rename до ACK допустим старый или целый новый пакет; ошибка не доказывает отсутствие записи. Process halt сохраняет ОС и её namespace, поэтому после состоявшегося rename в этом тесте восстанавливается новый пакет. Отдельная disk model проверяет оба исхода до directory force.
6. Replay запрещает откат term/commit, снятие/замену vote внутри term, разрывы индексов, уменьшение log term, entry term выше currentTerm, commit за концом лога и truncate committed prefix. Current-term commit rule, quorum и apply остаются обязанностью Raft M2/M3+.
7. WalStorage реализует существующий metadata-only Storage: один пакет term/vote+commit, затем ровно одно событие в очередь с исходным OperationId. Это не runtime: выполнение эффекта/доставка completion/stale IDs/зависимые RPC реализуются позже. При разных worker/driver threads очередь обязана быть thread-safe и unbounded.
8. Весь срок жизни WAL удерживается OS lock. Внутри JVM реестр канонических путей отвергает второй opener **до** открытия нового дескриптора: на Linux закрытие другого дескриптора того же inode может снять исходную блокировку. Lock-файл не удаляется. Остальные программы обязаны соблюдать этот advisory lock; это не защита от внешнего злонамеренного изменения файлов.

## Последствия и границы

- Простота и проверяемость оплачены несколькими force на пакет, копированием текущего log state и хранением восстановленного лога в памяти. Group commit, индекс на диске и ротация не добавляются без отдельной задачи.
- Каталог существует до create; подготовленная иерархия родителей — обязанность caller. Create синхронизирует непосредственного родителя и сам каталог. Open никогда не инициализирует пропавший store. Незавершённая первая инициализация может оставить каталог, требующий явного вмешательства, но не даёт ложного успеха.
- Production путь не содержит ослабленного режима Windows. Неподдерживаемые directory force/atomic replacement приводят к ошибке. Проверенное окружение: Linux/WSL ext4; Windows исполняет portable model/codec tests.
- Реальные JVM halt, FileChannel и ENOSPC через /dev/full + deterministic disk model не доказывают hardware power loss, честность дискового flush или корректность произвольной FS. Замена всех файлов старой самосогласованной копией, внешнее вмешательство, bind-mount aliases и несколько classloaders с независимыми экземплярами библиотеки вне модели.

## Свидетельства

18 state/metadata и 43 protocol/adapter случаев сначала падали на заглушках, затем прошли. Первый Linux запуск выявил потерю OS lock после неудачного второго opener; существующий межпроцессный тест прошёл после исправления. /tmp оказался tmpfs: Gradle направляет JUnit temp files в build/tmp/test-files, тест проверяет путь и FS.

Финальные проверки: Windows 190 выполненных случаев, 8 Linux methods skipped; WSL/ext4 212 случаев, failures/errors/skipped=0, включая 15 child-JVM halt boundaries, /dev/full и 100 шагов seeded history. Подробности — [ACCEPTANCE](../ACCEPTANCE.md).

Источники: [Java FileChannel](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/nio/channels/FileChannel.html), [Files.move](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/nio/file/Files.html#move(java.nio.file.Path,java.nio.file.Path,java.nio.file.CopyOption...)), [FileLock platform dependencies](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/nio/channels/FileLock.html), [Linux fsync](https://man7.org/linux/man-pages/man2/fsync.2.html).
