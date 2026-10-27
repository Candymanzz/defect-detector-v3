# Конфигурация, сборка и диагностика

**Код: `cpp-detector` / `bfd9b54a`, 2026-10-27.** [Контекст](../PROJECT_CONTEXT.md).

## Источники конфигурации

- `config.exemple/` — отслеживаемый Git шаблон. Рабочий `config/` локальный и игнорируется; сначала выяснить, какой файл действительно передан оркестратору.
- `20-detectors.yaml`: `python_detector.backend: cpp`, `server_concurrency: 10`, `defer_heatmap: true`; `java_positioning.enabled: false` в шаблоне.
- `01-core.yaml`: `integration.analis_surface_autostart.inspect_workers: 12`, один C++ сервер, параллельные стадии Java; параметры performance-логирования.
- `52-io-input.yaml` и блок триггера в `01-core.yaml` вместе задают DI3/DO5, направления и задержки. Один конфиг не заменяет другой.
- Настройки C++-профилей — `<--data-dir>/settings.json`, не старый `analysis_settings.json` Python. Эффективные значения запрашивать у `/settings/:profile` и смотреть в ответе/логе инспекции.

Если `python_detector.backend` отсутствует, `DetectorBackend` по совместимости выбирает Python. Нельзя считать C++ выбранным лишь по имени ветки. Без заданного `server_concurrency` Java использует fallback 1.

## Сборка и запуск

Команды ниже выполнять из корня соответствующего checkout; это справка, а не результат деплоя. Изменение исходников/смена ветки само по себе не меняет уже работающий бинарный процесс.

Полная Windows-сборка и запуск существующим корневым скриптом:

```powershell
powershell -ExecutionPolicy Bypass -File .\rebuild-and-run.ps1
# Флаг -NoFrontend передаётся, если запуск frontend требуется пропустить.
```

Скрипт начинает с `stop-dev.ps1` и собирает компоненты. Его нельзя запускать как безобидную read-only проверку станка. Параметры сверять с [самим скриптом](../../rebuild-and-run.ps1).

Сборка только C++ его скриптом, без последующего запуска:

```powershell
powershell -ExecutionPolicy Bypass -File .\cppAnalisSurface\rebuild-and-run.ps1 -NoRun
```

Этот скрипт может останавливать процесс своей сборки и устанавливать зависимости; `-NoRun` означает отсутствие последующего запуска, а не гарантированное сохранение работающего процесса. Для изолированной проверки использовать отдельный build-каталог и случайный порт.

После настройки CMake в `cppAnalisSurface/build`:

```powershell
cmake --build cppAnalisSurface/build --config Release
ctest --test-dir cppAnalisSurface/build -C Release --output-on-failure
```

Текущие CTest-цели: `shift_tolerance_test`, `performance_log_test`, `performance_http_test`. В проверенной сборке X/Y и кэш покрывались 465 проверками; после изменения кода число может измениться. Для Java использовать Maven/JDK 17; на Windows проверка запуска shell-скриптов требует отдельного окружения.

В этой ветке есть [run-supervised.sh](../../run-supervised.sh) для Linux-запуска **уже собранного** оркестратора. Корневого `rebuild-and-run.sh` в этом срезе нет. Старые команды из других веток, systemd units и пути SSH сверять с реальным checkout/машиной перед применением. Флаги `.ps1`, `.sh` и systemctl не взаимозаменяемы.

## Логи текущей диагностики

По шаблону performance-логи располагаются в `<корень>/cppAnalisSurface/logs/`:

| Файл | Содержимое |
|---|---|
| `performance-orchestrator-YYYY-MM-DD-<pid>.jsonl` | Триггеры, камеры, очереди Java, HTTP/RPC, вердикты, FINS |
| `performance-detector-YYYY-MM-DD-<pid>.jsonl` | Все инструментированные C++ запросы, ожидание слота, SHM, алгоритм, карты, запись ответа |
| `cas-YYYY-MM-DD.log` | Старт, предупреждения, ошибки, сводки C++ |
| `inspections-YYYY-MM-DD.jsonl` | Выбранные подробные результаты: весь брак/пограничные/медленные, годные с выборкой |

`integration.performance_log.enabled: true`, `directory: cppAnalisSurface/logs`, очередь Java 8192. Отдельно запущенный C++: `CAS_PERFORMANCE_LOG=0` отключает performance-журнал, `CAS_PERFORMANCE_LOG_DIR` переопределяет каталог; обычный каталог логов можно задать `--log-dir`.

Performance-файлы добавляются и разделяются по UTC-дате/PID; при перезапуске прошлые файлы остаются. UTC-время performance нельзя путать с локальным временем текстового журнала. Performance-файлы автоматически не очищаются; при переполнении очереди/лимита возможны пропуски, смотреть `dropped_records_since_last_write` и stderr. Эти файлы не являются полным архивом изображений или тел всех HTTP-запросов.

Общий runtime-лог Java и вывод systemd зависят от способа запуска. Для анализа передавать оба performance-файла, `cas`, `inspections` и runtime за один период. Старые папки `service-traces` из переписки не считать единственным текущим источником времени.

Корреляция: `request_id` — RPC/HTTP, `cycle_id` и `trigger_sequence` — цикл, `camera_id`/`frame_id` — кадр; PID/`detector_id` отличают процессы. Длительности измерять монотонными часами процесса, не вычитать абсолютные метки разных процессов.

`trigger_received` → `camera_verdict` → `bucket_verdict` → `plc_output_confirmed` различают время камеры, группы и подтверждение PLC. `plc_output_unchanged` не означает новую FINS-запись. FINS-подтверждение не является датчиком физического срабатывания механизма.

Полные поля и границы: [PERFORMANCE_LOGGING.md](../../cppAnalisSurface/PERFORMANCE_LOGGING.md). Последний численный результат сохранён в [замере стабильности](../performance/2026-10-27-speed-stability.md), независимо от локальной `.verification/`.
