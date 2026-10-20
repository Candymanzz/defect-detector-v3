# Состав сервисов текущей ветки

Проверено **2026-10-20**, ветка **`testlinux`**, HEAD **`e466367f`** («камераворкер теперь часть оркестратора»). Статус **[code]**: проверены исходники, шаблоны конфигурации и скрипты. Работающая конфигурация станка, процессы и аппаратные задержки в этой проверке не исследовались.

Начало базы: [README](./README.md) → [PROJECT_CONTEXT](./PROJECT_CONTEXT.md). Подробная карта: [global architecture](./global_architecture.md).

## Главное уточнение

**В Java остался не только оркестратор.** Последнее изменение убрало отдельные процессы захвата камер из рабочего пути, встроив существующий **C-код через JNI в JVM оркестратора**. Геометрия остаётся отдельным Java-сервисом. Java-позиционирование сохранено, но отключено в шаблоне. Наличие старой папки или слова `worker` в классе/логе не означает наличие отдельного работающего процесса.

| Компонент | Статус в проверенной ветке | Граница процесса / шаблон |
|---|---|---|
| `orchestrator-java` | Рабочий управляющий компонент: триггеры, захват, маршрутизация анализа, вердикты, DO/FINS, API | Основная JVM приложения; внешний `StackSupervisorMain` — отдельный Java-supervisor при supervised-запуске |
| `camera-worker` | C-реализация сохранена, захват встроен через `camera_jni` | До 10 состояний камер внутри JVM, **не 10 дочерних процессов**. Отдельный `camera_worker` оставлен для диагностики |
| `java-geometry-service` | **Не удалён и не встроен**: фабрика по-прежнему запускает отдельный пул | Шаблон: `geometry_pool_size: 10`, `java -jar …geometry…jar`, IMLB/stdio. Член пула не закреплён за одной камерой |
| `java-positioning-service` | Код, JAR-команды и стадия сохранены; **выключен в шаблоне** | `java_positioning.enabled: false` → пустой пул. Можно снова включить конфигурацией; это не удалённый модуль |
| `analisSurface/backend` | Python-анализ поверхности и базовое позиционирование X/Y сохранены | Шаблон: 6 процессов uvicorn, по 3 обработчика инспекции; HTTP/JSON, кадры читаются из SHM |
| Свет / IO | Работают внутри оркестратора через JNA/MVS | Отдельные `LightServer.v3` и `IoInputMonitor` заменены ранее, не этим JNI-коммитом. Их папки и `mvs-io-service` сохранены как legacy/диагностика |
| `front-end` | React/Electron сохранён | Отдельный UI; запуск через настроенный frontend-autostart либо установленный kiosk |

Это состав исходников и шаблона, **не список фактически запущенных процессов на станке**. Рабочий `config/` не версионируется; его отличия необходимо проверять отдельно.

## Фактический порядок инспекции

```text
Сигнал DI → оркестратор → C/JNI-захват камеры → кольцо BGR8 в SHM
  → закрепление кадра на цикл
  → опциональное Java-позиционирование [шаблон: выключено]
  → Java-геометрия [если не пропущена]
  → Python: позиционирование X/Y → сравнение поверхности, нормы/FP → ответ
  → итог камеры → агрегация группы → DO/FINS и сообщения UI
  → отложенные хитмапы/история [если включены]
```

Python-позиционирование выполняется **внутри Python после геометрической стадии**, а не предварительно для обоих детекторов. При выключенном Java-позиционировании нельзя считать, что геометрия получает уже выровненный Python кадр. У геометрического сервиса есть собственная логика выравнивания; её измерения не равны измерению положения этикетки относительно ведра.

Геометрический пул запускается независимо от того, будут ли в конкретном цикле запросы инспекции. `InspectGeometryExecutor` пропускает геометрию при отсутствии эталона, `geometry_enabled: false` или клиентском эталоне без пригодного joint ROI. В шаблоне геометрия выключена для ID **2 и 7**, а не для всех камер. Поэтому одни health-запросы в логах не доказывают удаление сервиса. Требования заказчика к новым допускам всё ещё отделены от реализации: [геометрия](./geometry/customer_requirements.md).

## Что изменил перенос камер в JNI

- Рабочий вызов: `CameraWorkerBootstrapService` → `WorkerProcessSupervisor` → `NativeCameraClient` → `NativeCameraBindings` → C-runtime `cw_camera_*`.
- `WorkerProcessSupervisor` сохранил название, таймауты и восстановление, но теперь переоткрывает **нативное состояние камеры**, а не запускает worker-процесс. Старый процессный конструктор/транспорт оставлен для диагностики.
- Через JNI передаются JSON-метаданные; изображение остаётся в восьмислотовом SHM-кольце `iml_cam_<id>_frame`. Нет камерного IMLB/stdio-транспорта; IMLB для геометрии сохраняется. Закрепление кадра копированием в `iml_line_pin_*` не удалено — весь путь не является «без копирований».
- Команды одной камеры сериализованы; разные камеры могут захватывать параллельно. Перечисление/открытие устройств защищено общим lock. Жизненный цикл MVS SDK разделён с IO/светом через `MvsSdkLease`; отмена ожидания предшествует освобождению состояния.
- `health` и проверка `capture_backend_ready` сохранены. Ошибка загрузки библиотеки/отсутствие JSON не даёт запустить интеграционный пайплайн; камера, не прошедшая startup-health, не попадает в список успешно запущенных камер. Наличие JVM не подтверждает готовность всех десяти камер.
- Нативный crash теперь может завершить **весь оркестратор**, а не один worker. В supervised-режиме восстановление JVM — задача внешнего supervisor. Обычная ошибка команды и crash нативного процесса — разные случаи.

## Сборка, конфигурация и производительность

- `rebuild-and-run.sh` собирает цель **`camera_jni`** и атомарно устанавливает `camera-worker/build/libcamera_jni.so`. На Windows типичный артефакт — `camera-worker/build/Release/camera_jni.dll`. Нужны JDK с JNI-заголовками, CMake/компилятор и нужный vendor SDK.
- Путь можно задать `integration.camera_native_library` или `-Diml.camera.native.library=…`; JSON камер по-прежнему задаёт `integration.worker_config_json` (по умолчанию `config/config.json`). Старые `worker_ipc_mode` / `worker_named_pipe_*` рабочий JNI-путь не переключают.
- Для применения новой JNI-библиотеки **нужен перезапуск JVM**. Флаг сборки `-c` пропускает теперь именно её сборку; оставлять его при первом переходе на JNI без готовой библиотеки нельзя. Скрипт сам backend не запускает. [Команды запуска](./deploy-ops/overview.md#7-current-update-and-launch-commands).
- Геометрический JAR по-прежнему собирается отдельно Maven; отсутствие его сборки в корневом rebuild-скрипте **не означает удаления сервиса**.
- JNI убирает камерные subprocess/IPC, но не ожидание аппаратного кадра, передачу GigE, геометрию, Python, очереди и запись выходов. **Свежих замеров JNI-версии здесь нет; выполнение 500 мс не подтверждено.** Старые результаты от 2026-10-16 относятся к прежнему камерному транспорту.
- В `run-all-tests.sh` добавлена сборка JNI без MVS/Aravis и Java-тесты с этой библиотекой. Это предусмотренные проверки без оборудования, не результат их нового запуска при обновлении документации.

## Источники для перепроверки

| Что проверять | Исходник |
|---|---|
| Камерный bootstrap и health | [CameraWorkerBootstrapService](../orchestrator-java/src/main/java/com/example/iml/orchestrator/integration/bootstrap/service/CameraWorkerBootstrapService.java), [IntegrationPreflightService](../orchestrator-java/src/main/java/com/example/iml/orchestrator/integration/bootstrap/service/IntegrationPreflightService.java) |
| JNI, блокировки, SDK, переоткрытие | [NativeCameraClient](../orchestrator-java/src/main/java/com/example/iml/orchestrator/integration/camera/NativeCameraClient.java), [WorkerProcessSupervisor](../orchestrator-java/src/main/java/com/example/iml/orchestrator/integration/camera/WorkerProcessSupervisor.java), [CAMERA-JNI](../orchestrator-java/CAMERA-JNI.md) |
| Реальный запуск Java/Python/UI | [ChildProcessStartupService](../orchestrator-java/src/main/java/com/example/iml/orchestrator/integration/bootstrap/service/ChildProcessStartupService.java), [DefaultIntegrationServicePoolFactory](../orchestrator-java/src/main/java/com/example/iml/orchestrator/integration/bootstrap/factory/DefaultIntegrationServicePoolFactory.java) |
| Пропуск геометрии и порядок стадий | [InspectGeometryExecutor](../orchestrator-java/src/main/java/com/example/iml/orchestrator/integration/pipeline/stages/InspectGeometryExecutor.java), [AsyncInspectionCycleRunner](../orchestrator-java/src/main/java/com/example/iml/orchestrator/integration/pipeline/session/AsyncInspectionCycleRunner.java) |
| Пулы / флаги / камеры | [01-core.yaml](../config.exemple/blocks/01-core.yaml), [20-detectors.yaml](../config.exemple/blocks/20-detectors.yaml), [10-cameras.yaml](../config.exemple/blocks/10-cameras.yaml) |
| Нативная сборка | [CMakeLists.txt](../camera-worker/CMakeLists.txt), [rebuild-and-run.sh](../rebuild-and-run.sh), [run-all-tests.sh](../run-all-tests.sh) |

При следующей смене ветки сверять эти факты заново. Не переносить статус «реализовано» или замеры из другой ветки только на основании истории чата.
