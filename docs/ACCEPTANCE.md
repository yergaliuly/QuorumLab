# Матрица приёмки QuorumLab

Основание: [ТЗ v1.0](TECHNICAL_SPECIFICATION.md), разделы 2, 3 и 8. Ниже — карта будущих проверок, а не отчёт об их прохождении. **Все требования пока не реализованы, все сценарии не запускались.** После реализации рядом с результатом нужно сохранять test path/команду, build/commit и ссылку на артефакт прогона.

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
