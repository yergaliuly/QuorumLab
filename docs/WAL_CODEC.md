# M1: как устроены первые записи WAL

WAL (write-ahead log) — журнал, из которого будущий storage сможет восстановить term, голос, log entries и commit index. Сначала мы реализовали **codec**: преобразование Java-объектов в байты и обратно. Файловая запись, force, checkpoint и recovery пока не реализованы.

Формат и причины выбора зафиксированы в [дизайне v1](superpowers/specs/2026-11-15-wal-codec-design.md). Исходное ТЗ задаёт envelope и четыре операции; порядок байтов, wire IDs, схема payload и лимит 1 MiB — решения этого среза.

## Четыре операции

| Тип | Что представляет | Пример |
|---|---|---|
| SetHardState | Устойчивые term и votedFor | В term=3 узел проголосовал за n2 |
| AppendEntry | Запись с index, term, типом и байтами команды | Добавить команду в log index=7, term=3 |
| TruncateSuffix | Логическое удаление entries, начиная с index включительно | Удалить неподтверждённый suffix с index=8 при исправлении конфликта |
| SetCommitIndex | Новую границу подтверждённых entries | commitIndex=7 |

TruncateSuffix — тоже запись append-only журнала. Сам codec не обрезает файлы и не проверяет историю: можно ли удалить конкретный suffix или продвинуть commit, решит будущий replay/Storage. Команда NO_OP не содержит payload; COMMAND хранит opaque bytes — формат KV-команды появится в M4.

Существующий core HardState содержит также commitIndex. В WAL term/vote и commit записаны разными операциями; будущий storage объединит нужные frames в persist batch и подтвердит его целиком. Отдельное успешное кодирование двух записей не делает такую группу атомарной.

## Из объекта в байты

```java
var codec = new WalCodec();
byte[] frame = codec.encode(new WalRecord.SetCommitIndex(7));
```

Результат — 23 байта:

```text
51 57 41 4c | 00 01 | 04 | 00 00 00 08 | 00 00 00 00 00 00 00 07 | 7d f9 7d 6b
   QWAL    | ver=1 |type| payload=8   |       commitIndex=7       |   CRC32C
```

Числа записаны в big-endian: старший байт идёт первым. Явный порядок нужен, чтобы один формат читался одинаково на Windows/Linux независимо от настройки переданного буфера. Header занимает 11 байт, payload здесь 8, checksum ещё 4.

`ByteBuffer` — контейнер для байтов с методами putInt/getInt, putLong/getLong и курсором `position`. `limit` задаёт границу доступных данных. При encode `flip()` переводит заполненный payload в режим чтения перед переносом в итоговый frame. Это операции с памятью; использования FileChannel в этом срезе нет.

**CRC32C** — контрольная сумма для обнаружения повреждений. Она вычисляется по header **и** payload. Например, изменение типа с SetCommitIndex на TruncateSuffix должно обнаруживаться, даже если оба типа умеют прочитать одно положительное восьмибайтовое число. CRC не является шифрованием, а совпадение checksum само по себе не доказывает durable запись.

Java 21 уже содержит CRC32C, ByteBuffer и UTF-8 encoder/decoder, поэтому новые библиотеки не понадобились. `quorum-storage` теперь зависит от core через Gradle `api`, потому что NodeId виден в его публичных records. Core по-прежнему не зависит от storage и не выполняет I/O.

## Полная, неполная и повреждённая запись

```java
var input = ByteBuffer.wrap(frame);
input.limit(10);
codec.decode(input);        // Optional.empty(); position остаётся 0

input.limit(frame.length);
codec.decode(input);        // SetCommitIndex(7); position становится 23
```

Decoder работает через отдельный cursor (`duplicate`), а исходную position изменяет только после всех проверок. Сначала проверяет header и длину, затем наличие полного frame, CRC, допустимость полей и отсутствие лишних байтов в payload. Для чтения нескольких записей его вызывают последовательно.

| Вход | Результат |
|---|---|
| Недостаточно байтов для header/payload/CRC | Optional.empty, исходный курсор не меняется |
| Полная корректная запись | WalRecord; курсор сдвинут ровно на один frame |
| Неизвестная версия/тип, неверная длина, CRC или поля | WalFormatException с конкретным reason; курсор не меняется |

При malformed UTF-8 decoder не подставляет символ замены: неизвестное имя узла не должно незаметно превращаться в другое. При encode непарный UTF-16 surrogate тоже вызывает ошибку. Header length проверяется до выделения памяти, поэтому отрицательная длина или Integer.MAX_VALUE не приводят к попытке создать такой массив. Payload ограничен 1 MiB, у APPEND_ENTRY 21 байт занимают его поля.

Неполная запись не означает «можно удалить хвост». Например, повреждённое поле длины может выглядеть как нехватка байтов. Будущий recovery должен сверять durable checkpoint/offset; отсутствие данных внутри подтверждённой области недопустимо. Этот код пока только сообщает результат разбора.

## Неизменяемость байтов

У record поля final, но содержимое массива остаётся изменяемым. Поэтому AppendEntry делает defensive copy:

```java
byte[] command = {1, 2};
var entry = new WalRecord.AppendEntry(7, 3, WalRecord.EntryType.COMMAND, command);
command[0] = 9;             // entry по-прежнему содержит [1, 2]
entry.command()[0] = 8;    // accessor тоже возвращает копию
```

Для массива отдельно реализованы equals/hashCode по содержимому. Это позволяет сравнить исходную запись и декодированную, даже когда это разные объекты и массивы. Иначе стандартное поведение record сравнивало бы массивы по ссылкам.

## Что проверено

69 новых JUnit-случаев: 57 для codec и 12 для records. Есть шесть фиксированных golden vectors: точные байты из спецификации с checksum, вычисленным независимым побитовым алгоритмом, а не проверяемым codec. Это ловит совместную ошибку encoder/decoder, которую простой roundtrip мог бы пропустить.

Также проверены все неполные prefixes эталонных frames, heap/direct/read-only/sliced buffers, ненулевая position, ограниченный limit, little-endian настройка входа, последовательные записи, неправильные заголовки/CRC, невозможные поля даже с корректным CRC, UTF-8 и границы размеров. Один тест дополнительно выполняет 200 воспроизводимых roundtrips по фиксированному seed; это не 200 отдельных JUnit-случаев.

До реализации все 69 случаев были красными: API имел временные заглушки, records ещё не проверяли аргументы и не копировали массив. После реализации новый набор прошёл; полный проект — 129 случаев на Windows и свежем WSL/ext4-прогоне без build cache. Подробности и run path — в [ACCEPTANCE](ACCEPTANCE.md). Ошибок готового storage здесь не диагностировали: файлового storage ещё нет.

Следующий шаг M1 — согласованный контракт persist batch, durable checkpoint и recovery, затем реализация записи/force/replay с тестами отказов. До этого M1 не считается завершённым, T-12/T-13/T-17 и гарантии durability остаются открытыми.

## Источники

- [Java 21 ByteBuffer](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/nio/ByteBuffer.html).
- [Java 21 CRC32C](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/zip/CRC32C.html).
