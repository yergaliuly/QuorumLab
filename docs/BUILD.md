# Сборка QuorumLab

Сборочный срез M0 настроил инструменты и модули; следующий срез добавил контракты и scheduler. Рабочей базы данных и тестов алгоритма Raft пока нет. Наличие зелёной сборки не означает, что Raft реализован или проверен.

## Быстрый запуск

Требуется JDK 21. Первый запуск также требует сеть для загрузки Gradle и зависимостей. Глобальный Gradle и Android Studio не нужны; проект можно открыть в VS Code.

Из корня проекта в PowerShell:

```powershell
.\gradlew.bat clean test build
```

На Linux:

```bash
./gradlew clean test build
```

В `quorum-core` и `quorum-simulator` теперь есть исходники и постоянные JUnit-тесты. В остальных восьми модулях `compileJava` и `test` пока показывают `NO-SOURCE`. Начальный временный probe в обычную suite не включён.

## Что делает каждый файл

| Файл | Ответственность |
|---|---|
| `settings.gradle.kts` | Имя проекта, список десяти Java-модулей и Maven Central как общий источник зависимостей |
| `build.gradle.kts` | Общие Java/JUnit-настройки, root-задачи для всех модулей и настройки генерации Wrapper |
| `gradle.properties` | UTF-8, лимит heap процесса Gradle и локальный build cache |
| `gradlew` / `gradlew.bat` | Команда запуска Wrapper для Linux/macOS и Windows |
| `gradle/wrapper/gradle-wrapper.jar` | Bootstrap-код, который загружает и запускает нужный Gradle |
| `gradle/wrapper/gradle-wrapper.properties` | Версия, URL, тайм-аут и SHA-256 дистрибутива Gradle |
| `quorum-*/build.gradle.kts` | Назначение отдельного модуля и его специфичные зависимости |
| `.github/workflows/ci.yml` | Автоматическая сборка Windows/Linux на GitHub |

Kotlin DSL — язык файлов настройки сборки. Исходный код QuorumLab и его тесты будут на Java. Файл настройки может содержать Kotlin-выражения, но отдельный Kotlin compiler и Kotlin application plugin проекту не нужны.

## Версии и зависимости

- Gradle **9.9.0**, закреплён в Wrapper. SHA-256 ZIP: `0b00b0ffe07c4d4789c12d4935a44a7057d55774b7540bd4a3f76f336deb177a`.
- SHA-256 официального Wrapper JAR: `621008afc4f5d59357fcf06ffa644dd5aade5e091bdc363742710445f73cdaf8`.
- Java **21**: toolchain выбирает JDK 21, `options.release=21` ограничивает доступные Java API и целевой байткод. Patch-версия JDK не зафиксирована; CI выбирает доступный Temurin 21.
- JUnit BOM/Jupiter **5.14.4**. BOM согласует версии компонентов JUnit; Jupiter предоставляет `@Test`, assertions и test engine. `junit-platform-launcher` используется при запуске тестов.
- `useJUnitPlatform()` связывает Gradle Test task с JUnit Platform.
- JUnit подключён через `testImplementation` и `testRuntimeOnly`. Он доступен тестам, но не является runtime-зависимостью приложения.

Пример: будущий `quorum-core/src/main/java/.../RaftCore.java` компилируется без JUnit. Тест `quorum-core/src/test/java/.../RaftCoreTest.java` сможет использовать `@Test` и `assertEquals`. Gradle строит отдельные classpath для этих двух наборов исходников.

При первом запуске Wrapper загружает ZIP и сверяет checksum; при несовпадении отказывается запускать его. Последующие запуски используют локальный Gradle cache. Wrapper JAR также проверяется `gradle/actions/setup-gradle` в CI.

## Модули

| Модуль | Будущая ответственность |
|---|---|
| `quorum-core` | Чистая логика Raft: события, переходы состояния и эффекты |
| `quorum-storage` | WAL, HardState, snapshots и recovery |
| `quorum-transport` | Сериализация и передача peer RPC |
| `quorum-kv` | Детерминированная KV state machine и deduplication |
| `quorum-node` | Runtime узла и клиентский API |
| `quorum-client` | Сессии, retries и поиск лидера |
| `quorum-simulator` | Виртуальные время, сеть и диск |
| `quorum-checker` | Независимая проверка histories и инвариантов |
| `quorum-integration` | Многопроцессные и fault-injection tests |
| `quorum-bench` | Workloads и обработка измерений |

Первая межмодульная зависимость — `quorum-simulator → quorum-core`: scheduler использует NodeId и реализует MonotonicClock. Она объявлена через `api`, поскольку типы core входят в публичный API simulator. У core производственных зависимостей нет. Общая настройка находится в одном корневом скрипте; отдельный build-logic plugin сейчас добавил бы лишний слой. Если правила модулей существенно разойдутся, можно выделить convention plugins.

`proto/` пока содержит только пояснение будущих схем. `quorum-dashboard/` — место для будущего React/TypeScript UI на M9; это не Java-модуль, он не входит в Gradle build. Фиктивные Java-классы для заполнения каталогов не создаются.

## Задачи и их порядок

`clean` удаляет результаты предыдущей сборки в `build/`, `test` запускает тестовые задачи модулей, `build` собирает модули и выполняет проверки. Корневые задачи явно зависят от соответствующих задач всех десяти подпроектов. Gradle исполняет задачу один раз за один запуск, даже если она нужна нескольким зависимым задачам.

Полезные команды:

```powershell
.\gradlew.bat projects
.\gradlew.bat :quorum-core:test
.\gradlew.bat :quorum-core:dependencies --configuration runtimeClasspath
.\gradlew.bat -q javaToolchains
```

В последней команде `javaToolchains` — диагностическая задача Gradle. Она показывает найденные JDK; настройка toolchain в проекте выбирает из них Java 21. Отсутствующий JDK здесь автоматически не устанавливается.

## GitHub Actions

Workflow запускается при push, pull request и вручную. Матрица создаёт два задания: `ubuntu-latest` и `windows-latest`. Каждое получает исходники, устанавливает Temurin 21, проверяет Wrapper, настраивает Gradle cache и выполняет `clean test build`.

Права workflow ограничены `contents: read`; публикации и deployment нет. Actions закреплены полными commit SHA, версии указаны комментариями. При появлении отчётов Gradle/JUnit они загружаются как artifacts на 7 дней. Отсутствие отчётов в пустом каркасе не выдаётся за прохождение продуктовых тестов. Лимит одного задания — 20 минут; устаревший запуск той же ветки/PR отменяется.

При push в ветку уже открытого PR возможны два запуска — push и pull_request. Пока это простой стандартный workflow; оптимизация триггеров возможна позже, если расход CI станет существенным.

## Проверка этого среза

Фактические результаты и команды записаны в [PROJECT_STATE](PROJECT_STATE.md). Для проверки JUnit использовался временный тест с Java 21 record pattern/switch и проверкой версии тестовой JVM. Сначала намеренно неверное ожидание делало сборку красной, затем корректное — зелёной. Probe подключался init script из игнорируемого `tmp/` и не входит в репозиторий или обычный `test`.

9 ноября CI сборочного PR #1 подтверждён на Ubuntu и Windows. Следующий срез выполняет 44 постоянных тестовых случая: [объяснение контрактов и scheduler](CORE_AND_SCHEDULER.md). HTML-отчёты находятся в `quorum-core/build/reports/tests/test/index.html` и `quorum-simulator/build/reports/tests/test/index.html`; их также собирает существующий workflow.

Проверка сборки в WSL Ubuntu не доказывает Linux durability будущего storage: сборка выполнялась из Windows-папки через `/mnt/c`. Для M1 понадобится отдельная проверка файловой системы и fsync-контракта.

## Официальные источники

- [Gradle Wrapper и проверка checksum](https://docs.gradle.org/9.9.0/userguide/gradle_wrapper.html).
- [Совместимость Gradle и Java](https://docs.gradle.org/9.9.0/userguide/compatibility.html).
- [JUnit 5.14.4](https://docs.junit.org/5.14.4/overview.html).
