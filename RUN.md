# Сборка и запуск на Linux

Рабочие компоненты: `orchestrator-java` (Java), `cppAnalisSurface` (C++) и
`front-end` (React + Electron). Камеры, подсветка, DI/DO и ПЛК обслуживаются
оркестратором; `camera-worker` содержит исходники JNI-библиотеки камер.

Требуются JDK 17+, Maven, CMake 3.25+, компиляторы C/C++17, pkg-config,
OpenCV, cpp-httplib, nlohmann-json, Node.js >=20.19 и npm. Для оборудования
нужен Linux MVS SDK (`MVS_ROOT=/opt/MVS`). Python 3 используется скриптами
управления и диагностики; отдельного Python-сервиса нет.

Общие настройки находятся в `config/` и отслеживаются Git. На новой машине выполните
`python3 deploy/init-ips.py` и введите адреса камер, света и ПЛК.
Единственный локальный конфиг адресов — `config/ips.yaml` (игнорируется Git).
В остальных YAML используются ссылки `${ip:camera_0}`, `${ip:plc}` и т. п.
Список ключей определяется автоматически по ссылкам; файл нельзя перезаписать этой командой.
Карта регистров ПЛК находится в `plk/register-map.yaml`.
Настройки камер редактируются только в YAML; ручного `config.json` больше нет.
Java создаёт `config/.camera-worker.generated.json` для JNI автоматически при запуске.
`config/data/` содержит локальные сохранённые настройки оператора и также не входит в Git.
Подробнее: [конфигурация](config/README.md).
Разделы `python_detector` и `integration.analis_surface_*` сохраняют прежние
имена для совместимости конфигурации, но задают C++-анализатор.

Полная пересборка с загрузкой новых backend и UI одной командой:

```bash
bash ./rebuild-and-run-all.sh
IML_BUILD_JOBS=4 bash ./rebuild-and-run-all.sh # Параллельная сборка C++
bash ./rebuild-and-run-all.sh --build-only   # Сборка и установка без перезапуска
```

Используются существующие кэши сборки. После успешной сборки скрипт перезапускает
`defect-detector.service`, ждёт ответа backend и API дообучения, затем перезапускает
киоск. Запускайте от обычного пользователя; для привилегированных операций
используются `sudo`/`pkexec`. Нужны установленный systemd-сервис из этого checkout
и помощник киоска (`sudo bash deploy/linux/setup-frontend-updates.sh` — один раз).
Тесты запускаются отдельно через `run-all-tests.sh`.

Сборка отдельных компонентов прежним скриптом:

```bash
bash ./rebuild-and-run.sh          # Все три компонента и JNI камер
bash ./rebuild-and-run.sh -c       # Без пересборки JNI
bash ./rebuild-and-run.sh -f       # Без сборки и установки фронта
bash ./rebuild-and-run.sh -c -o -d # Только фронт
```

Сборка не запускает и не останавливает сервисы. Фронт устанавливается в
`/opt/defect-detector-ui` (переопределяется `FRONTEND_INSTALL_DIR`); Electron
и его `node_modules` должны быть подготовлены заранее. После установки
перезапустите киоск. Однократная настройка обновлений:

```bash
sudo bash deploy/linux/setup-frontend-updates.sh o
bash ./rebuild-and-run.sh -c -o -d --restart-frontend
```

Запуск и остановка:

```bash
bash ./run-supervised.sh
bash ./run-supervised.sh -f # Без автозапуска фронта
CONFIG=/path/to/config.yaml bash ./run-supervised.sh
bash ./stop-dev.sh
```

Для установленного systemd-сервиса: `sudo systemctl start defect-detector.service`.
Оркестратор запускает C++-анализатор и фронт согласно конфигурации. Киоск
может запускаться отдельной системной сессией. Геометрия и позиционирование
не запускаются как отдельные сервисы.

Проверка: `bash ./run-all-tests.sh` (JNI, Java, Linux-скрипты, C++, HTTP/SHM,
фронт). Диагностика уже работающей системы: `bash ./run-rt.sh -r`.

Подробности: [C++ на Linux](deploy/linux/CPP-DETECTOR.md),
[JNI камер](orchestrator-java/CAMERA-JNI.md), [установка Linux и киоска](deploy/linux/README.md),
[диагностика CPU](RT-LINUX.md), [логи](LOGGING.md).
