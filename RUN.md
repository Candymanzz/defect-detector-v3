# Сборка и запуск на Linux

Рабочие компоненты: `orchestrator-java` (Java), `cppAnalisSurface` (C++) и
`front-end` (React + Electron). Камеры, подсветка, DI/DO и ПЛК обслуживаются
оркестратором; `camera-worker` содержит исходники JNI-библиотеки камер.

Требуются JDK 17+, Maven, CMake 3.25+, компиляторы C/C++17, pkg-config,
OpenCV, cpp-httplib, nlohmann-json, Node.js >=20.19 и npm. Для оборудования
нужен Linux MVS SDK (`MVS_ROOT=/opt/MVS`). Python 3 используется скриптами
управления и диагностики; отдельного Python-сервиса нет.

Скопируйте `config.exemple/` в `config/` и настройте камеры, свет и ПЛК своей
линии. Карта регистров ПЛК находится в `plk/register-map.yaml`.
Настройки камеры в `config/config.json` должны соответствовать YAML.
Разделы `python_detector` и `integration.analis_surface_*` сохраняют прежние
имена для совместимости конфигурации, но задают C++-анализатор.

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
