# C++-анализатор на Linux

Анализатор работает отдельным HTTP-процессом; кадры получает через SHM.
Java-геометрия, Java-позиционирование и Python-анализатор не требуются.

Для Ubuntu:

```bash
sudo apt install build-essential cmake pkg-config libopencv-dev libcpp-httplib-dev nlohmann-json3-dev
bash cppAnalisSurface/build.sh
```

Требуются CMake >=3.25 и cpp-httplib >=0.14. Общая сборка описана в [RUN.md](../../RUN.md).

Пример конфигурации уже настроен на C++. Для существующего машинного YAML
можно создать профиль, который импортирует настройки оборудования:

```bash
python3 deploy/linux/configure-cpp-detector.py --config config/config.yaml --workers 4
CONFIG=config/cpp-linux.yaml bash ./run-supervised.sh -f
```

Исходный конфиг не перезаписывается. Профиль следует проверить перед запуском.
Путь к процессу задаёт `integration.analis_surface_command_linux`, каталог
работы — `integration.analis_surface_autostart.working_dir`.
Параметр `inspect_workers` ограничивает число параллельных инспекций.

Данные C++-анализатора хранятся в `cppAnalisSurface/data/`, если используется
`--data-dir data` и рабочий каталог `cppAnalisSurface`.
Раздел `python_detector` сохраняет имя для совместимости конфигурации:
`backend: cpp`, `base_url: http://127.0.0.1:8000`.

Проверка готовности: `curl http://127.0.0.1:8000/health`.
Тесты всего стека: `bash ./run-all-tests.sh`.
