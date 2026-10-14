# Сборка и запуск на Linux

Из корня проекта:

```bash
bash ./rebuild-and-run.sh       # Сборка компонентов и запуск supervisor
bash ./rebuild-and-run.sh -f    # Без установки, сборки и запуска фронтенда
bash ./rebuild-and-run.sh --build-only  # Только сборка
```

Нужны JDK 17+, Maven, Python 3 с venv/pip, CMake и C-компилятор.
Для фронтенда также нужны Node.js (не ниже 20.19) и npm.
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
