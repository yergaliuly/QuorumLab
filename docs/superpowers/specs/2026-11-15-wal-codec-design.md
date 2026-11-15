# M1, первый срез: формат WAL и codec

Дата: 2026-11-15. Основание: пользователь поручил начать следующий этап после M0; [ТЗ §6](../../TECHNICAL_SPECIFICATION.md) уже задаёт собственный WAL, структуру frame и четыре record types. Это первый законченный срез QL-005, не весь M1. Конкретные числа/кодировки ниже — решения реализации ассистента в выбранном направлении; не отдельные требования исходного ТЗ.

## Подходы и границы

Можно сначала сделать только envelope с непрозрачным payload: меньше кода, но невозможные поля и схема четырёх операций останутся непроверенными. Выбран типизированный codec для всех четырёх операций: немного больше Java-кода, зато появляются проверяемый формат и полезные негативные тесты. Новых библиотек/стоимости инфраструктуры нет: Java 21 ByteBuffer, UTF-8 и CRC32C; storage зависит от core только ради NodeId. JSON/Protobuf вместо заданного frame не рассматриваются: это изменило бы уже выбранный контракт.

Этот срез не пишет файлы, не вызывает force, не исполняет replay, не удаляет tail и не реализует порт Storage. Atomic persistence/checkpoint и recovery будут отдельным связанным срезом QL-006. Правила persist-before-response из ADR-002 остаются обязательными.

## Frame v1

Все многобайтовые числа — **big-endian**. Размеры/индексы/термы — signed Java int/long с проверкой диапазона; type/version и CRC читаются как unsigned значения соответствующей ширины.

| Поле | Байт | Значение |
|---|---:|---|
| magic | 4 | ASCII `QWAL`, hex `51 57 41 4c` |
| formatVersion | 2 | 1 |
| recordType | 1 | 1=SET_HARD_STATE, 2=APPEND_ENTRY, 3=TRUNCATE_SUFFIX, 4=SET_COMMIT_INDEX |
| payloadLength | 4 | 0…1 048 576; размер payload без header/CRC |
| payload | N | схема ниже |
| crc32c | 4 | CRC32C по всем предыдущим байтам этого frame, включая header |

Header = 11 байт, overhead = 15 байт. Максимум payload 1 MiB — технический лимит формата, не увеличение лимита KV value 64 KiB из ТЗ. Decoder проверяет длину до выделения памяти. Повреждённый length, magic, неизвестный type/version не дают перехода к поиску следующей magic.

## Payload

| Операция | Схема |
|---|---|
| SET_HARD_STATE | currentTerm:i64, voteByteLength:i32, voteUtf8:bytes |
| APPEND_ENTRY | index:i64, term:i64, entryType:u8, commandByteLength:i32, command:bytes |
| TRUNCATE_SUFFIX | fromIndexInclusive:i64 |
| SET_COMMIT_INDEX | commitIndex:i64 |

SET_HARD_STATE сохраняет **пару term/vote**; commitIndex задаётся отдельной операцией. Существующий core HardState содержит все три поля: будущий adapter переведёт требуемые изменения в batch, а checkpoint подтвердит batch целиком. Наличие отдельных frames сейчас не объявляется атомарностью такой группы.

Для отсутствующего голоса voteByteLength=-1 и байтов нет. Иначе длина >0, NodeId не blank, UTF-8 строго корректен; malformed UTF-16 при encode тоже отвергается, без замены символов. Term >=0, в term=0 голоса нет.

Append index/term >0. entryType: 0=NO_OP (command пуст), 1=COMMAND (opaque bytes; семантика KV появится в M4). Command защищён defensive copy и equality по содержимому. Максимальная длина command = 1 MiB − 21 байт полей APPEND_ENTRY.

Truncate удаляет логические entries с index >= fromIndexInclusive (>0); это запись в append-only WAL, не команда немедленно обрезать сам WAL-файл. Commit index >=0. Проверки истории (term не уменьшается, committed entries нельзя удалить, commit не выходит за durable log) принадлежат будущему replay/Storage; codec проверяет только локальную допустимость одной записи.

## API и ошибки

`WalCodec.encode(WalRecord) -> byte[]`: точный один frame. Недопустимое значение/слишком большой payload вызывает IllegalArgumentException, null — NullPointerException.

`WalCodec.decode(ByteBuffer) -> Optional<WalRecord>`: читает один frame с текущей position до limit, независимо от byte order исходного буфера. При успехе position сдвигается ровно на размер frame; следующие записи остаются для следующих вызовов. При недостатке байтов возвращает empty и не сдвигает position. При ошибке тоже не сдвигает position и бросает WalFormatException с reason: BAD_MAGIC, UNSUPPORTED_VERSION, UNKNOWN_RECORD_TYPE, INVALID_LENGTH, CHECKSUM_MISMATCH или INVALID_PAYLOAD. Обрабатываются heap/direct/read-only buffers.

Полный header должен присутствовать до проверки его полей. После проверки header отсутствие части payload/CRC означает только «недостаточно байтов». Корректный CRC не разрешает неверные payload поля или лишние байты внутри payload.

**Empty не является разрешением отбросить tail.** Например, повреждение length может выглядеть как нехватка данных. Будущий recovery обязан знать подтверждённый segment/offset: недостача/ошибка внутри устойчивой области — fail closed. Алгоритм атомарного checkpoint и точный handling недолговечного хвоста здесь не выбираются.

## Проверки

Golden byte vectors рассчитаны независимо от production codec, roundtrip всех типов, Unicode и отсутствие голоса, разные buffers/position/limit, последовательные frames, каждый неполный prefix, header/payload/CRC corruption, семантически неверный payload с правильным CRC, пределы длины и immutable bytes. Windows и свежий WSL/ext4 build через существующий скрипт. S-02/T-12/T-13/T-17 целиком этим срезом ещё не закрываются.
