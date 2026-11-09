# M0: архитектурные границы и Linux-стенд

**Goal:** завершить локальную часть M0: ADR-001, исполняемые ограничения core и воспроизводимая сборка на ext4 в WSL. Remote CI после пользовательского push — последний gate.

**Architecture:** существующая модель event → effects сохраняется. Производственный core — только JDK; ArchUnit подключается к тестам для анализа bytecode. Gradle отдельно проверяет отсутствие compile/runtime dependencies. Linux-стенд хранит снимки исходников и будущие данные вне `/mnt/c`.

**Tech stack:** Java 21, Gradle 9.9.0, JUnit 5.14.4, ArchUnit 1.5.1 (test only), WSL Ubuntu/ext4, Bash/rsync.

**Основание:** прямой запрос пользователя; [ROADMAP M0](../../ROADMAP.md), [ТЗ](../../TECHNICAL_SPECIFICATION.md). Один исполнитель, текущий checkout, ветка `chore/m0-architecture-guardrails`. Ранее обновлённые записи о CI включаются сюда. Push выполняет пользователь.

## Шаги

- [x] Зафиксировать ADR-001: scope, consistency, failure model, отложенные API-детали и связь с ADR-002.
- [x] Добавить архитектурные правила и отрицательные примеры: внешний тип, I/O, wall clock, потоки и скрытая случайность, включая method references. Импортировать все production-классы core, исключая test helpers.
- [x] Проверить, что обычный Gradle test действительно падает при временном нарушении в main и при лишней production dependency; убрать probes и получить GREEN.
- [x] Добавить небольшой скрипт WSL: новый снимок исходников без `.git`/build/cache, проверка ext4, manifest, отдельный data-dir, полный build. Не удалять чужие/старые каталоги.
- [x] Проверить отказ стенда на Windows filesystem, затем Windows и WSL/ext4 build, classpath и фактические тестовые отчёты.
- [x] Обновить обучение/приёмку/память и подготовить единый локальный результат к review/коммиту. M0 до нового CI отмечен как «локально готов, remote gate ожидается».

## Границы и review

Не реализовать Raft/WAL и не объявлять fsync/power-loss durability доказанной. Архитектурный тест — защита от обычных прямых зависимостей/API, не sandbox против reflection/native обходов. Проверки должны обнаруживать новые production-классы, references и неиспользуемые зависимости. Снимок WSL не является второй веткой/worktree; пользовательский source of truth остаётся в Windows checkout. Модель отказов и API-детали не расширяются молча.

## Результат

Один агент, один checkout/branch. Windows: 60 случаев PASS. WSL/ext4: финальный свежий прогон run-XGIWiigS, 60 случаев PASS, manifest подтверждён. Первоначальный Linux-прогон использовал часть build cache; исправлен контракт скрипта (`--no-build-cache`) и повторён полный прогон в новом каталоге. Отрицательные probes подтвердили все четыре архитектурных правила, интеграцию с root test, отказы compileOnly/runtimeOnly и отказ стенда на 9p. Новых production-зависимостей и кода Raft/WAL нет. CI нового кода не запускался без пользовательского push.

Self-review: границы импорта main/test проверены реальным probe; источник Windows не перезаписывается, скрипт не удаляет прошлые прогоны/data и отвергает неподходящий filesystem. Исходное ТЗ и исходный контекст не изменены. Локальные Markdown-ссылки и whitespace diff проверены; временные probes/logs исключены из Git. Блокирующих замечаний нет; ограничения статических правил и непроверенная durability записаны явно.
