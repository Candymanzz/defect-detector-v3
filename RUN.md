# Сборка и запуск на Linux

Из корня проекта:

```bash
bash ./rebuild-and-run.sh       # Сборка оркестратора и camera-worker, затем запуск
bash ./rebuild-and-run.sh -c    # Только оркестратор
bash ./rebuild-and-run.sh -o    # Только camera-worker
bash ./rebuild-and-run.sh -f    # Запуск без фронтенда
bash ./rebuild-and-run.sh --build-only  # Только сборка
```

Для сборки оркестратора нужны JDK 17+ и Maven; для camera-worker — CMake
и C-компилятор. Инструменты пропущенного компонента не требуются для сборки.
Python, geometry, positioning и фронтенд не пересобираются и не переустанавливаются;
при запуске используются уже подготовленные компоненты.
Флаги можно сочетать: `-c -f`, `-o -f` или `-c -o` (запуск без пересборки).
SDK камеры задаётся через `MVS_ROOT` (по умолчанию `/opt/MVS`).
Рабочий конфиг по умолчанию — `config/config.yaml`.

Другой конфиг и пропуск сборки камеры:

```bash
bash ./rebuild-and-run.sh --config /path/to/config.yaml --skip-camera-worker
```

Запуск уже собранного приложения:

```bash
bash ./run-supervised.sh
bash ./run-supervised.sh --no-frontend
CONFIG=/path/to/config.yaml bash ./run-supervised.sh
```

Привязка к CPU: `bash ./run-rt.sh` — см. [RT-LINUX.md](RT-LINUX.md).
Остановка интерактивного запуска — Ctrl+C.
При работе через systemd управляйте службой `defect-detector.service`.

Тесты: `bash ./run-all-tests.sh`.
Логи каждой сессии: `logs/<дата-время>/` — см. [LOGGING.md](LOGGING.md).
Подготовка Linux-ПК и киоска: [deploy/linux/README.md](deploy/linux/README.md).
Скрипт сборки не обновляет установленную копию интерфейса в `/opt`.
