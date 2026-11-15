# Как работает файловый WAL

Этот срез M1 добавил физическое сохранение и восстановление поверх существующего [кодека](WAL_CODEC.md). Алгоритма Raft, KV apply и сети ещё нет. [ADR-003](adr/0003-wal-checkpoint-and-recovery.md) содержит точные решения и ограничения, [матрица приёмки](ACCEPTANCE.md) — результаты проверок.

## От Java-объекта до подтверждения

Предположим, на диске term=4, vote отсутствует. Узел собирается проголосовать за `node-b` в term=5. Будущий Raft выдаст `PersistHardState(id, HardState(5, node-b, commitIndex))`, а `WalStorage` преобразует его в **один пакет**:

```text
SET_HARD_STATE(term=5, vote=node-b)
SET_COMMIT_INDEX(старый commitIndex)
```

`FileWal.persist` принимает список операций. Сначала `WalState.Builder` вычисляет допустимое будущее состояние на копии текущего лога; ошибка не трогает диск. Затем `WalCodec` превращает записи в bytes. Только потом начинается I/O:

```text
записать все frames → force WAL
→ записать checkpoint.tmp → force temp → закрыть temp
→ atomic move temp поверх checkpoint → force каталога
→ опубликовать новое WalState → PersistCompleted(id) в очередь
```

Если любой I/O шаг завершился ошибкой, успешного события нет. WalStorage ставит `PersistFailed(id, reason)`, а FileWal запрещает дальнейшие операции до close/open. Это не «отмена записи»: checkpoint мог успеть замениться до ошибки. При перезапуске нужно прочитать фактическое состояние.

Сейчас bridge принимает только HardState, как и существующий core Storage. `FileWal.persist` уже умеет пакеты с append/truncate/commit. Расширение эффектов ядра и исполнение зависимых RPC входят в M2/M3; вызов storage сам по себе ещё не реализует выборы.

## Какие технологии участвуют

| Средство | Вообще | В QuorumLab и пример |
|---|---|---|
| Java 21 FileChannel | Чтение/запись файла по byte offset | NioWalIo пишет в сегмент. Если write записал 5 из 31 bytes, цикл допишет оставшиеся 26; read тоже может быть частичным |
| ByteBuffer | Массив/область bytes с position/limit | Position показывает, сколько байтов уже обработано; replay читает bounded frame, не весь WAL в byte array |
| force(true) | Просит provider сохранить данные и метаданные файла | После записи WAL и нового checkpoint выполняется отдельный force; простого close для этого недостаточно |
| Files.move с ATOMIC_MOVE | Атомарно меняет видимое имя файла, если provider это поддерживает | Recovery видит старый или новый checkpoint целиком. Замена не означает синхронизацию каталога: после неё нужен directory force |
| UUID и CRC32C | Идентификатор связывает объекты; checksum обнаруживает случайное повреждение | UUID не даёт принять checkpoint другого сегмента; CRC защищает header/checkpoint/frames. Это не шифрование |
| FileLock + реестр JVM | Согласованное исключение второго writer | Другой процесс и другой экземпляр в той же JVM не могут одновременно писать в один data-dir |
| Queue и OperationId | Передают результат операции с идентификатором | В очередь попадает PersistCompleted(7,2), driver позже доставит событие ядру. Нет прямого callback в step; для разных потоков нужна thread-safe unbounded очередь |
| JUnit, WSL/ext4, child JVM | Проверки логики и окружения | Portable disk model управляет сбоями; реальная JVM аварийно останавливается на выбранной границе в Linux |

Новых внешних библиотек нет. Используем JDK и уже подключённые JUnit/Gradle. Все файловые операции находятся в quorum-storage; core остаётся pure Java без I/O.

## Checkpoint — граница байтов

Файлы каталога:

```text
writer.lock                    # постоянное имя для блокировки
wal-0000000000000001.log        # QSEG header + существующие QWAL frames
checkpoint                     # UUID + generation + exclusive byte offset + CRC
checkpoint.tmp                 # существует только во время обновления/после сбоя
```

Заголовок сегмента занимает 26 bytes, checkpoint — 42. Generation увеличивается на каждый успешно опубликованный пакет. Допустим, прежний offset=800, новый пакет заканчивается на byte 950. После подтверждения `[26,950)` обязан читаться и проходить проверки. Если recovery выбрал старый checkpoint, любые байты начиная с 800 не входят в подтверждённый prefix и будут удалены.

Это отличается от `commitIndex=7`: он означает, что Raft разрешил применение логических записей 1…7. WAL может содержать entry 8, сохранённую локально, но ещё не committed большинством. При recovery она остаётся в логе, однако Storage не применяет её к KV.

Один сегмент — текущая реализация M1. Ротация и compaction не реализованы; журнал и восстановленный лог пока растут.

## Три примера restart

1. **Краш после половины WAL frame, до checkpoint.** Старый checkpoint валиден. Подтверждённый prefix проверяется; лишний suffix обрезается, truncate синхронизируется. Половина нового состояния не появляется.
2. **Краш после замены checkpoint, до ответа.** Возможно восстановление целого нового пакета, хотя caller не получил успех. Отсутствие ответа не означает отсутствие сохранения. Это обычный неизвестный исход; будущий runtime обязан учитывать его.
3. **Повреждён последний byte внутри подтверждённого prefix.** CRC не сходится: startup FAIL_CLOSED. Нельзя назвать его «оборванным хвостом» и молча потерять уже подтверждённую запись. В диагностике есть byte offset проблемного frame.

Неполный/испорченный checkpoint, неподдерживаемая версия, неверный UUID, offset за концом файла или внутри frame также останавливают recovery. Временный checkpoint не используется как догадка о состоянии.

## Правила логики

WalState восстанавливает metadata и immutable список записей. Он отвергает, например:

- term 5 → 4;
- vote `node-b` → `node-c` или пустой vote в том же term;
- append index=9, если последний index=7;
- commitIndex=9, если записано только 8 entries;
- truncate с index=6, если commitIndex=7.

TruncateSuffix — логическая операция внутри append-only WAL. Она удаляет suffix восстановленного Raft-лога; сами старые frames WAL остаются. Физический truncate применяется только к неподтверждённому byte suffix при recovery.

## Что обнаружили и исправили

**Потеря блокировки.** Второй opener внутри JVM получал OverlappingFileLockException и закрывал свой FileChannel. На Linux закрытие этого дескриптора снимало OS lock первого владельца; следующий процесс мог открыть WAL. Исправление: реестр канонических data-dir отклоняет второй opener до открытия дескриптора. Проверка намеренно сначала делает неудачный same-JVM open, затем запускает отдельную JVM — она тоже обязана получить отказ.

**Неправильное место тестовых файлов.** Снимок исходников был на ext4, но JUnit по умолчанию создавал файлы в /tmp, который оказался tmpfs. Gradle теперь задаёт java.io.tmpdir внутри quorum-storage/build/tmp/test-files. Linux-тест проверяет расположение и filesystem; финальный отчёт показывает ext4. Первый прогон не считается ext4-свидетельством WAL.

## Что проверено и что ещё нет

Windows: 190 выполненных случаев; Linux methods явно skipped. WSL/ext4: 212 случаев, без failures/errors/skipped. Есть short/zero writes/reads, faults до/после каждого barrier, corruption, 15 halt boundaries, 100 последовательных generated batches с truncate/commit/reopen. /dev/full возвращает настоящий ENOSPC после частичной записи: успешного completion нет, checkpoint не продвигается, reopen возвращает прежнее состояние. Сам WAL-раздел при этом не заполнялся.

Crash model различает volatile bytes, forced bytes и durable namespace. Runtime.halt проверяет гибель процесса, сохраняя ОС живой. Ни одна из этих проверок не равна физическому отключению питания WSL/накопителя. Гарантии ограничены описанной моделью Linux local FS; сетевые FS и durability Windows не проверены. Вызов force/atomic move без поддержки приводит к ошибке, слабого fallback нет.

После push/CI/merge этого среза следующий этап — M2: follower/candidate/leader, выборы и зависимость ответа от PersistCompleted. Подробные свидетельства и текущий статус — в [PROJECT_STATE](PROJECT_STATE.md).
