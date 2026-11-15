# План QL-006: durable WAL

Spec: [design](../specs/2026-11-15-durable-wal-design.md). Одна ветка feat/m1-durable-wal от d00dcd7; существующий Windows checkout, один исполнитель. Пользователь явно поручил реализацию и проверки; повторное согласование всего M1 не требуется. Push — пользователь.

## Ограничения

Сохраняем codec v1 и core API, не реализуем Raft/сеть/snapshots/rotation. Новых зависимостей нет. Production entry points требуют полного протокола без ослабленного режима. IOException после начала persist → неизвестный исход, запрет повторных writes на том же экземпляре. Все результаты проверок фиксируются фактически, не ожидаемым выводом.

## Task 1 — state и metadata

- RED: тесты последовательного лога, term/vote/commit/truncate, immutable state; header/checkpoint CRC/version/UUID/bounds.
- GREEN: WalState builder для validate/replay и WalMetadata bounded codec; checked WalRecoveryException.
- Проверить :quorum-storage:test, ожидается success после реализации.

## Task 2 — файловый протокол и bridge

- RED: FileWal на независимой модели storage, short writes, no progress, atomic batch, сбои каждого barrier, corruption в durable prefix, suffix policy, lock и reopen; WalStorage completion ordering.
- GREEN: package-private WalIo + NioWalIo, FileWal, WalStorage. Синхронный single-writer, bounded buffers, close/recovery cleanup.
- Проверить storage suite; model различает bytes и directory persistence, тестирует old/new rename outcomes.

## Task 3 — Linux и завершение

- Реальные Linux tests: create/open/replay, lock, partial tail/corruption, process halt до/после force/rename/directory sync; seeded history vs независимая модель.
- Windows clean test build и scripts/test-wsl.sh; читать XML counts, skips и source manifest. Проверить отсутствие зависимостей/I/O в core и git diff --check.
- Self-review порядка barriers, resource leaks, silent rollback, bounds/overflow, неизвестного исхода errors. Исправления с регрессиями.
- ADR-003, учебный WAL_STORAGE, PROJECT_STATE/ROADMAP/ACCEPTANCE/BRIEF; локальный коммит после проверок. Указать CI нового среза как ожидающий push, M1 не объявлять merged.

## Review focus

Частичный persist до checkpoint, directory force failure после rename, повреждение последнего durable frame, checkpoint из чужого сегмента, commit/truncate поверх committed prefix, stale tmp, повторное открытие после неудачной initialization, освобождение lock при recovery error, zero progress I/O, границы выделения памяти.

## Журнал выполнения

- Подготовка: PR #4 merged d00dcd7; main CI 43715944608, оба jobs success. main локально fast-forward, новая ветка создана; предыдущие изменения статуса сохранены. Реализация ещё не начата.
- Task 1: complete — 18 RED failures на заглушках; state/metadata suite GREEN вместе с прежними codec tests.
- Task 2: complete — 43 RED failures на заглушках; protocol/bridge suite GREEN, atomic batch и disk model реализованы.
- Task 3: complete локально — Windows clean/test/build: 190 executed + 8 Linux methods skipped; WSL run-aBKvD37K: 212/212, no cache, data на ext4. Remote CI ждёт пользовательского push.
- Ruling: строгий production protocol без Windows downgrade; Windows запускает portable tests, filesystem/process tests ограничены Linux. Модель ошибок отделена package-private WalIo от публичного API.
- Ruling: один активный сегмент, без rotation/compaction; это сохраняет объём M1, но рост файла и log state пока не ограничен политикой хранения.
- Debugging: первый Linux run-Tp1rVgAE выявил POSIX lock release при close второго дескриптора и /tmp=tmpfs. Предварительный JVM registry + build-local java.io.tmpdir, regression GREEN на ext4. Первый run не durability evidence.
- Final review: self-review одним исполнителем согласно предпочтению пользователя. Проверены barriers, partial IO, prefix bounds/CRC, poison/unknown outcome, initialization refusal, resource cleanup, no temp fallback, immutable state и границы core. Независимого agent review не было.
- Minor deferred: существующий Gradle warning о val ... by tasks.registering в core; относится к будущему Gradle 10, не изменялся в storage-срезе.
