# Матрица приёмки QuorumLab

Основание: [ТЗ v1.0](TECHNICAL_SPECIFICATION.md), разделы 2, 3 и 8. Ниже — карта проверок и фактических свидетельств. **M1 проверяет T-12/T-13 и storage-часть T-17 в указанной модели; полные распределённые сценарии T-01…T-18 ещё не закрыты.** Исторические результаты M0/codec не следует принимать за результаты более поздних этапов. После реализации сохраняем test path/команду, build/commit и артефакт прогона.

## Свидетельства M0 build от 7 ноября 2026

- Gradle Wrapper 9.9.0: официальный JAR проверен по SHA-256; ZIP checksum записан в Wrapper properties. Shell-скрипт имеет Git mode 100755.
- Windows: `.\gradlew.bat --no-daemon --console=plain --warning-mode=all clean test build` — успешно, сборка охватывает все десять модулей.
- WSL Ubuntu/Java 21: полный `clean test build` с временным JUnit probe и финальный обычный build без него — успешно; это не проверка storage durability на Linux filesystem.
- Временный JUnit probe: один тест с Java 21 record pattern/switch, JVM feature 21. Намеренное ожидание 22 дало assertion failure; правильное ожидание 21 — PASS на Windows и Linux. Probe не коммитится; в сборочном срезе 7 ноября постоянных тестов продукта было 0.
- `:quorum-core:dependencies --configuration runtimeClasspath` — `No dependencies`. Это наблюдение текущего classpath; архитектурное правило NFR-04 ещё не реализовано.
- Workflow Windows/Linux проверен `actionlint 1.7.12`; Actions закреплены SHA. 9 ноября подтверждён реальный [CI run 42643809327](https://github.com/yergaliuly/QuorumLab/actions/runs/42643809327): Ubuntu/Windows success на merge commit `3a0b5eea479084c783284decd64d2ad0bb8d62c0`, [PR #1](https://github.com/yergaliuly/QuorumLab/pull/1) merged. QL-002 закрыт.

Подробности и ограничения — в [BUILD](BUILD.md); полный M0 остаётся открытым.

## Свидетельства M0 contracts/scheduler от 9 ноября 2026

Проверен исходный код `cea21865dfcd523e6d6a8ac2f1142b6181a5ec6c` ветки `feat/m0-core-contracts`, основанной на `3a0b5ee`. Пользователь отправил код и принял [PR #2](https://github.com/yergaliuly/QuorumLab/pull/2); merge `4a34a3fb196c08518d6f06683ec7ada25fab8f0d` имеет то же дерево файлов.

| Проверка | Фактический результат |
|---|---|
| RED до реализации | CoreContractsTest: 36 случаев, 32 ожидаемых падения на отсутствии валидации. Scheduler: 8/8 ожидаемых падений на временных заглушках. Заглушки заменены реализацией |
| Windows, Java 21 | `gradlew.bat --no-daemon --console=plain --warning-mode=all clean test build`: SUCCESS; 36 core + 8 scheduler, failures/errors/skipped = 0 |
| WSL Ubuntu, Java 21 | `./gradlew --no-daemon --console=plain --warning-mode=all clean test build`: SUCCESS; те же 44 случая, failures/errors/skipped = 0 |
| GitHub PR CI | [Run 42648502160](https://github.com/yergaliuly/QuorumLab/actions/runs/42648502160), head cea2186: Ubuntu/Windows jobs и шаг `Build and test all Java modules` — success |
| GitHub main CI после merge | [Run 42649004223](https://github.com/yergaliuly/QuorumLab/actions/runs/42649004223), head 4a34a3f: completed/success, оба jobs и шаг сборки/тестов — success |
| Core runtimeClasspath | Нет зависимостей; production-код использует только JDK. Автоматический запрет framework/I/O пока не добавлен |
| Simulator runtimeClasspath | Только `project :quorum-core`; новых внешних библиотек нет |

Тесты: [CoreContractsTest](../quorum-core/src/test/java/io/github/yergaliuly/quorumlab/core/CoreContractsTest.java), [DeterministicSchedulerTest](../quorum-simulator/src/test/java/io/github/yergaliuly/quorumlab/simulator/DeterministicSchedulerTest.java). HTML/XML — в `quorum-{core,simulator}/build/reports/tests/test/` и `build/test-results/test/`. Локальные RED/Windows/WSL логи — игнорируемые `tmp/m0-contracts-*.log`, они не входят в репозиторий.

Проверены локальная допустимость DTO, идентичность ID, порядок очереди, FIFO для равного времени, повтор trace, относительные задержки, пустая очередь и отказ при ошибках без изменения состояния. Из этого не следует корректность Raft, persist ordering, реального restart или диска: алгоритма/адаптеров пока нет. FR-12/NFR-05 покрыты только на уровне начального scheduler. S-02, остальные safety-инварианты и T-01…T-18 ещё не запускались. WSL-прогон из `/mnt/c` не является durability-проверкой Linux filesystem.

## Свидетельства M0 guardrails/WSL от 9 ноября 2026

Срез `chore/m0-architecture-guardrails`, коммит `27eba87`, основан на `4a34a3f`. ADR-001 фиксирует scope/consistency/failure model и явно сохраняет отложенные вопросы. 15 ноября пользователь принял [PR #3](https://github.com/yergaliuly/QuorumLab/pull/3), merge `9d16be8a80e0218e2b0553b6b7479473e8dd4d2f` сохранил то же дерево исходников. [PR CI 42657904435](https://github.com/yergaliuly/QuorumLab/actions/runs/42657904435) и [main CI 43712610759](https://github.com/yergaliuly/QuorumLab/actions/runs/43712610759) завершились success: оба jobs и их шаг сборки/тестов успешны. M0 gate закрыт.

| Проверка | Фактический результат |
|---|---|
| Архитектурные fixtures RED → GREEN | Сначала 11 запрещённых примеров не обнаруживались permissive rule (11 падений из 13 случаев). После реализации все 11 обнаруживаются, допустимый пример проходит |
| Production import и method reference | Временный `probe.ForbiddenClockProbe` вне пакета core, содержащий `System::nanoTime`, сделал корневой `test` красным по двум правилам; probe удалён |
| Неиспользуемые зависимости | До verifyCoreClasspath лишняя compileOnly JUnit dependency позволяла тестам пройти; после добавления guard отдельные compileOnly/runtimeOnly probes завершились ожидаемым отказом с именами JAR |
| Windows | `gradlew.bat --no-daemon --console=plain --warning-mode=all clean test build`: SUCCESS; XML — 60 случаев, failures/errors/skipped = 0 |
| WSL/ext4, финальный свежий прогон | `bash scripts/test-wsl.sh`, внутри `clean test build --no-build-cache`: SUCCESS; все 60 случаев выполнены в новом Linux-каталоге, failures/errors/skipped = 0, без FROM-CACHE |
| Filesystem | Ubuntu 26.04.1 LTS, kernel 6.18.33.2-microsoft-standard-WSL2, OpenJDK 21.0.12.1; source snapshot и data-dir — ext4 `/dev/sdd`, mount data=ordered |
| Отрицательный тест стенда | Попытка использовать `/mnt/c/.../tmp` отвергнута: фактический тип 9p, ожидается ext4. Bash syntax check прошёл |
| Снимок исходников | SHA-256 manifest проверен; `.git`, tmp и старый build/cache не скопированы. source HEAD = 4a34a3f с незакоммиченными изменениями; точный состав кода задан manifest |
| Зависимости | verifyCoreClasspath подтверждает пустые production compile/runtime classpaths. Test runtime содержит ArchUnit 1.5.1 и его SLF4J API 2.0.19 вместе с JUnit 5.14.4 |

60 случаев = 36 DTO + 8 scheduler + 4 production architecture rules + 12 проверок самих правил. Классы: [CoreArchitectureTest](../quorum-core/src/test/java/io/github/yergaliuly/quorumlab/core/architecture/CoreArchitectureTest.java), [CoreArchitectureRulesTest](../quorum-core/src/test/java/io/github/yergaliuly/quorumlab/core/architecture/CoreArchitectureRulesTest.java). Проверки включены в обычный test, а значит в существующий CI workflow.

Финальное Linux-свидетельство: `/home/amateru/quorumlab-lab/runs/run-XGIWiigS/`, внутри environment.txt, source.sha256, build.log и source/*/build/test-results/test. Будущий data-dir: `/home/amateru/quorumlab-lab/data`. Первый run-ECdNJ8Yj был успешен, но переиспользовал cache; финальный прогон повторён после отключения build cache в скрипте, чтобы не принимать сохранённые результаты за выполнение на выбранном filesystem. Прогоны не удалялись и содержимое data-dir не перезаписывалось.

Артефакты стенда и локальные tmp/m0-*.log не входят в Git. После прогона изменялась проектная документация; проверенный исполняемый код сохранён без изменений. NFR-04 теперь защищён статическими проверками прямых зависимостей/API, но reflection/native обходы и общий детерминизм произвольного кода этим не доказываются. Готовность ext4-стенда не закрывает WAL recovery/fsync/power-loss гарантии M1.

## Свидетельства M1 WAL codec от 15 ноября 2026

Первый срез M1 — `feat/m1-wal-codec` от `9d16be8`, QL-005, коммит `d49e521005d21d2d606f569432efd4ad3b074222`. [Формат](superpowers/specs/2026-11-15-wal-codec-design.md) определяет record framing и payload; физическая запись/force/checkpoint/replay добавлены следующим срезом ниже. Пользователь принял [PR #4](https://github.com/yergaliuly/QuorumLab/pull/4), merge `d00dcd768f41df0583532853cd985dc7eb5c5b16`; [main CI 43715944608](https://github.com/yergaliuly/QuorumLab/actions/runs/43715944608) прошёл оба jobs Ubuntu/Windows.

| Проверка | Фактический результат |
|---|---|
| RED → GREEN | До реализации 69/69 новых случаев падали на API-заглушках, отсутствии валидации и mutable byte arrays. После реализации все прошли |
| Golden format | 6 фиксированных byte vectors для всех операций, включая отсутствие голоса и NO_OP; CRC рассчитан независимым побитовым Castagnoli-алгоритмом, reference `123456789` → e3069283 |
| Decode boundaries | Все incomplete prefixes шести frames, ненулевая position, ограниченный limit, heap/direct/read-only/sliced buffers, входной little-endian и последовательные records |
| Corruption/validation | Неизвестные version/type, неверные lengths, CRC повреждения header/payload/checksum, невозможные typed payload с правильным CRC, trailing bytes, strict UTF-8 и лимиты |
| Immutable data | Defensive copies command при создании/чтении, equality/hashCode по содержимому; 200 generated roundtrips с фиксированным seed внутри одного JUnit-теста |
| Windows | `gradlew.bat --no-daemon --console=plain --warning-mode=all clean test build` — SUCCESS, XML: 129 случаев, failures/errors/skipped = 0 |
| WSL/ext4 | `bash scripts/test-wsl.sh` → свежий `clean test build --no-build-cache` — SUCCESS, те же 129 случаев, failures/errors/skipped = 0, без FROM-CACHE |
| Classpath | quorum-storage runtimeClasspath содержит только project :quorum-core; новых внешних библиотек нет, архитектурные правила core прошли |
| GitHub CI кодека | [PR run 43715288132](https://github.com/yergaliuly/QuorumLab/actions/runs/43715288132) completed/success на d49e521; Ubuntu/Windows jobs и шаги build/test успешны. [Push run 43715264757](https://github.com/yergaliuly/QuorumLab/actions/runs/43715264757) и main run 43715944608 после merge также прошли оба jobs |

Новые тесты: [WalCodecTest](../quorum-storage/src/test/java/io/github/yergaliuly/quorumlab/storage/wal/WalCodecTest.java) — 57 случаев, [WalRecordTest](../quorum-storage/src/test/java/io/github/yergaliuly/quorumlab/storage/wal/WalRecordTest.java) — 12. Всего с 60 прежними — 129 JUnit-случаев, не 329; generated roundtrips не считаются отдельными JUnit invocation.

Linux-свидетельство: `/home/amateru/quorumlab-lab/runs/run-3rvmZSXH/`, environment.txt, build.log, проверенный source.sha256 и XML всех шести test suites. Source HEAD 9d16be8 с незакоммиченным срезом, точный исполняемый код зафиксирован manifest. `.git` и tmp не скопированы. После прогона обновлялась документация; исполняемый код не менялся. Локальные логи — игнорируемые tmp/m1-*.log.

Это часть проверки versioned disk format (NFR-08), но ещё не WAL recovery или durability. Optional.empty у decoder означает недостаток байтов, а не разрешение обрезать хвост. T-12/T-13/T-17 требуют будущих durable boundary и реального/симулируемого I/O; S-02 и S-08 этим срезом не закрываются.

## Функциональные требования

Последние storage-свидетельства приведены в отдельном разделе QL-006 в конце документа; таблицы ниже сохраняют трассировку исходного ТЗ.

| ID | Требование | Основные этапы | Свидетельство приёмки |
|---|---|---|---|
| FR-01 | Три фиксированных узла, clusterId/dataDir | M0, M5 | 3 процесса; чужой clusterId отвергается |
| FR-02 | Election и переизбрание | M2, M5 | T-01/T-02/T-03 |
| FR-03 | Replication и conflict repair | M3 | T-07/T-08, log matching assertions |
| FR-04 | PUT | M4–M5 | Подтверждённое значение видно последующему GET |
| FR-05 | Линейризуемый GET | M4–M5, M8 | T-04/T-05/T-11, history checker |
| FR-06 | DELETE и tombstone/revision | M4, M6 | Детерминированное удаление, ABA, snapshot/replay |
| FR-07 | CAS по revision | M4 | Успешный/mismatched/concurrent CAS; T-10 |
| FR-08 | Дедупликация | M4, M6 | T-09/T-10, replay и snapshot результатов |
| FR-09 | WAL recovery | M1, M5 | T-12/T-13/T-17/T-18 |
| FR-10 | Snapshot и compaction | M6 | T-14, durable checkpoint до удаления префикса |
| FR-11 | InstallSnapshot | M6 | T-15 |
| FR-12 | Fault simulator | M0–M3, M7 | Сценарии network/node/disk faults |
| FR-13 | Replay по seed и trace | M7 | T-16, тот же build/config |
| FR-14 | Наблюдаемость | M5, M7, M9 | Role/term/leader/log/commit/applied/RPC/latency/failure |
| FR-15 | Docker deployment | M5, M9 | Воспроизводимый запуск и проверка API |

## Нефункциональные требования

| ID | Требование | Этапы и проверка |
|---|---|---|
| NFR-01 | Safety при рассматриваемых faults | M2–M8: deterministic simulation + независимый checker |
| NFR-02 | Сохранность подтверждённых записей | M1/M5/M6: WAL/snapshot и crash/restart |
| NFR-03 | Нет ложного успеха без quorum | M3–M5/M8: 1/3 active, partitions, stale leader |
| NFR-04 | Core независим от I/O/framework/time | M0+: dependency rules/ArchUnit и simulated runtime |
| NFR-05 | Failure trace воспроизводим в том же build | M0 scheduler, M7 полный replay assertion |
| NFR-06 | Наблюдаемость объясняет election/commit/recovery | M5/M7/M9: метрики, structured logs, traces |
| NFR-07 | PR CI: unit/property/deterministic smoke | M0 skeleton, далее расширение; фактические GitHub checks |
| NFR-08 | Versioned wire/disk formats | M1/M5/M6: несовместимые версии явно отвергаются |
| NFR-09 | Localhost или изолированная сеть | M5/M9: bind/ports/Compose configuration |
| NFR-10 | Воспроизводимая performance methodology | M8: workload, hardware/JVM/fsync manifest и измерения |

## Safety и liveness

| ID | Свойство | Где проверять |
|---|---|---|
| S-01 | Не более одного избранного лидера на term | M2/M7: T-01/T-02 |
| S-02 | Term/vote сохраняются до ответа | M1/M2/M7: persist boundary + restart |
| S-03 | Log matching | M3/M7: prefix comparisons, T-07 |
| S-04 | Leader completeness, включая snapshot | M3/M6/M7: T-03/T-07/T-08/T-15 |
| S-05 | Нет разных применённых команд на одном index | M3–M8: apply assertions |
| S-06 | Majority commit только текущего term | M3/M7: T-08 Figure 8 |
| S-07 | Ordered apply и границы applied/commit/log | M3/M6/M7: assertions с учётом snapshot |
| S-08 | Успех после durable quorum и локального apply | M1/M3–M7: crash/force/response boundaries |
| S-09 | Linearizability по интервалам операций | M4/M8: T-11 и независимые checkers |
| S-10 | Повтор идентичной команды не меняет состояние дважды | M4/M6/M7: T-09/T-10, replay/snapshot |
| L-01 | Лидер появляется при стабильной связи большинства | M2/M5/M7: eventual election после стабилизации |
| L-02 | Команды завершаются со стабильным лидером и большинством | M4/M5/M7: workload после стабилизации |
| L-03 | Отставшая реплика догоняет log/snapshot | M3/M6/M7: catch-up, T-15 |

Liveness проверяется при оговорённых условиях связи/работоспособности. Фиксированное время election при произвольных задержках не обещается.

## Обязательные сценарии

| ID | Сценарий | Ожидаемый результат | Основные этапы |
|---|---|---|---|
| T-01 | Начальная election | После стабилизации один лидер в term | M2 |
| T-02 | Split vote | Нет двух избранных лидеров одного term | M2 |
| T-03 | Лидер отключён после успешного PUT | Новый лидер сохраняет PUT | M3–M5 |
| T-04 | Старый лидер изолирован в меньшинстве | Не подтверждает PUT/линейризуемый GET | M4–M5 |
| T-05 | Два узла недоступны | Нет нового успешного KV-запроса без quorum | M4–M5 |
| T-06 | Лидер умер до quorum commit | Нет ложного успеха; неизвестный исход/ошибка | M3–M5 |
| T-07 | Старый лидер с конфликтующим suffix | Исправляется только неподтверждённый suffix | M3, M5 |
| T-08 | Raft Figure 8 | Нет неправильного commit старого term | M3 |
| T-09 | Crash после quorum, до ответа | Retry того же запроса не изменяет состояние повторно | M4, M6–M7 |
| T-10 | Повторный CAS с тем же ID | Возвращается сохранённый результат | M4, M6 |
| T-11 | Concurrent PUT и GET | История линейризуема с учётом invocation/completion | M4, M8 |
| T-12 | Partial WAL tail | Recovery по policy без выдуманных записей | M1, M7 |
| T-13 | Corrupt WAL middle | Startup FAIL_CLOSED с диагностикой | M1, M7 |
| T-14 | Crash до/после snapshot rename | Последнее durable состояние восстанавливается | M6–M7 |
| T-15 | Сильно отставший follower | Catch-up через InstallSnapshot | M6 |
| T-16 | Повтор trace на фиксированном build | Те же события и результат checker | M7 |
| T-17 | ENOSPC | Нет успеха на недолговечных данных, есть диагностика | M1, M5, M7 |
| T-18 | Restart всех трёх узлов | Все подтверждённые записи сохранены | M5–M8 |

Привязка к раннему этапу означает появление проверки, а не отмену повторного выполнения после существенного изменения реализации.

## Уровни и целевые gates

| Уровень | Когда | Цель из ТЗ |
|---|---|---|
| Unit | Каждый commit | Transitions, append/commit, KV/dedup, WAL codec/recovery, snapshots |
| Property/model | Каждый PR | jqwik; независимые модели и invariants |
| Deterministic smoke | Каждый PR | 100 быстрых seeds + 10 regression traces после развития suite |
| Nightly | Отдельный вычислительный бюджет | Не менее 10 000 seeds, фактический счётчик и отчёт |
| Real process | PR/release по составу suite | Три JVM, реальные I/O, recovery и faults |
| Performance | Release | Воспроизводимые нагрузки и profiling |

CI также включает build и static analysis. Ориентиры core coverage: ≥90% line и ≥80% branch; это не доказательство корректности. Gate считается достигнутым после прогона, не после настройки числа seeds в конфигурации.

Начальные performance-ориентиры из ТЗ: p95 ≤200 ms и ≥100 успешных writes/s для 1 KiB, 32 concurrent clients, трёх узлов, SSD и отсутствия faults. Это не SLA и не измеренные результаты. При недостижении публикуются фактические цифры, correctness не ослабляется.

## Завершение v1.0

- [ ] Все T-01…T-18 проходят; негативные условия действительно введены.
- [ ] Нет известных нерешённых safety/linearizability violations; найденные ошибки имеют trace и regression test.
- [ ] Выполнены проверки уровней CI/release и зафиксирован фактически достигнутый объём симуляции/coverage.
- [ ] README объясняет запуск, demo, архитектуру, guarantees/failure model и ограничения.
- [ ] Бенчмарки воспроизводимы, результаты отделены от целей.
- [ ] Другой человек воспроизводит демонстрацию; нет заявлений о непроверенных гарантиях.

## Свидетельства M1 File WAL/checkpoint/recovery от 15 ноября 2026

QL-006, ветка `feat/m1-durable-wal` от `d00dcd7`. [Дизайн](superpowers/specs/2026-11-15-durable-wal-design.md), [ADR-003](adr/0003-wal-checkpoint-and-recovery.md), [учебное объяснение](WAL_STORAGE.md). Локальный gate выполнен; GitHub CI/merge этого среза ожидают пользовательского push.

| Проверка | Фактический результат |
|---|---|
| RED/GREEN | 18/18 state/metadata и 43/43 protocol/bridge случаев сначала упали на заглушках; после реализации прошли |
| Windows | `gradlew.bat clean test build --no-daemon --console=plain --warning-mode=all`: SUCCESS. XML: 198 зарегистрированных, 190 выполненных, 8 Linux methods skipped, failures/errors=0. Linux parameterized template раскрывается в 15 случаев только на Linux |
| WSL/ext4 | `bash scripts/test-wsl.sh`: свежий `clean test build --no-build-cache`, SUCCESS, XML: 212 случаев, failures/errors/skipped=0, без FROM-CACHE |
| Модель диска | Отдельные volatile/forced bytes и durable name bindings; short reads/writes, zero progress, failures до/после WAL write/force, temp write/force, rename и directory force; poisoned writer не возвращает state/success и не продолжает писать |
| Recovery T-12/T-13 | Неподтверждённые full/partial/corrupt bytes за checkpoint удаляются; повреждение любого durable frame, включая последний, invalid metadata/UUID/bounds/history → FAIL_CLOSED. Stale temp не источник recovery |
| Реальные process crashes | 15 child JVM halt boundaries от beforeWalWrite до afterAck; halt пропускает close/finally. Reopen видит целый старый/новый пакет и позволяет продолжить работу |
| ENOSPC, storage-часть T-17 | Реальный Linux /dev/full возвращает ENOSPC после 5 записанных WAL bytes. Нет PersistCompleted, checkpoint не продвинут, reopen сохраняет прежнее состояние. WAL-раздел не заполнялся; симуляция других I/O failures отдельная |
| Model/replay | 100 seeded batches (66821) с append/truncate/commit и reopen сравниваются с независимым reference log/metadata; это один JUnit case, не 100 |
| Блокировка | Неудачный same-JVM второй opener не снимает OS lock: отдельная JVM также получает отказ; normal close/recovery errors освобождают ресурсы |
| Core boundary | Полный build включает Gradle classpath guards + ArchUnit; I/O остаётся в storage, зависимость storage только project core, внешних библиотек не добавлено |

Тесты: [FileWalTest](../quorum-storage/src/test/java/io/github/yergaliuly/quorumlab/storage/wal/FileWalTest.java) — 40; [WalStorageTest](../quorum-storage/src/test/java/io/github/yergaliuly/quorumlab/storage/wal/WalStorageTest.java) — 3; WalStateTest — 15; WalMetadataTest — 3; [FileWalLinuxTest](../quorum-storage/src/test/java/io/github/yergaliuly/quorumlab/storage/wal/FileWalLinuxTest.java) — 22 Linux invocations. Новых 83 + прежних 129 = 212.

Первый Linux run `run-Tp1rVgAE` обнаружил реальный lock bug: закрытие второго дескриптора после OverlappingFileLockException снимало POSIX lock первого. Исправлен предварительным JVM registry до открытия дескриптора; тот же тест прошёл. Также /tmp оказался tmpfs, поэтому первый run **не считается** ext4-свидетельством WAL. Gradle java.io.tmpdir перенесён в module build/tmp/test-files; добавлена проверка пути/FS.

Финальное свидетельство: `/home/amateru/quorumlab-lab/runs/run-aBKvD37K/`. Source HEAD d00dcd7 + незакоммиченный срез; environment.txt, source.sha256, build.log и 11 XML suites. System-out FileWalLinuxTest явно показывает ext4 и data path внутри снимка `source/quorum-storage/build/tmp/test-files/`. Локальные логи — игнорируемые `tmp/m1-storage-*.log`. После финальной сборки менялась только документация.

S-02 подтверждён на границе storage completion, но запрет зависимого Raft RPC до completion проверяется в M2/M3. S-08/FR-09 подтверждены для локального WAL в данной модели; трёхузловой restart и клиентские подтверждения ещё предстоят. Process halt сохраняет ОС, модель диска не эмулирует всё оборудование, power loss/нечестный flush/network FS/Windows durability не доказаны. Незавершённый create может требовать ручного разбора каталога; storage не сбрасывает его автоматически в пустой.
