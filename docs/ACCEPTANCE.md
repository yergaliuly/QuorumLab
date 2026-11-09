# Матрица приёмки QuorumLab

Основание: [ТЗ v1.0](TECHNICAL_SPECIFICATION.md), разделы 2, 3 и 8. Ниже — карта будущих проверок. **Продуктовые сценарии T-01…T-18 ещё не запускались.** Частичные свидетельства M0 перечислены отдельно; они не закрывают требования к Raft/storage. После реализации рядом с результатом нужно сохранять test path/команду, build/commit и ссылку на артефакт прогона.

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

Срез `chore/m0-architecture-guardrails` основан на `4a34a3f`, включает ранее подготовленные записи о CI PR #2. ADR-001 фиксирует scope/consistency/failure model и явно сохраняет отложенные вопросы. Локальный M0 gate выполнен; CI этого среза ожидает пользовательского push и не подменяется результатами PR #2.

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

## Функциональные требования

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
