# Сборка и запуск на Linux

Пересборка из корня проекта:

```bash
bash ./rebuild-and-run.sh       # Оркестратор и camera-worker
bash ./rebuild-and-run.sh -c    # Только оркестратор
bash ./rebuild-and-run.sh -o    # Только camera-worker
```

Скрипт только собирает выбранные компоненты и завершает работу. Он не
останавливает и не запускает сервисы, не вызывает systemctl и не меняет киоск.
Python, geometry, positioning и фронтенд не пересобираются.
Старые параметры `-f`, `-r`, `--build-only`, `--config`, `--rt-interval`
принимаются для совместимости, но на сборку не влияют; скрипт сообщает об этом.

Для оркестратора нужны JDK 17+ и Maven; для camera-worker — CMake и C-компилятор.
Инструменты пропущенного компонента не требуются. Maven ищется в PATH,
MAVEN_HOME/M2_HOME, Maven Wrapper и стандартных установках IntelliJ IDEA.
CMake ищется в PATH, CMAKE_HOME, /opt, ~/.local/bin, Downloads, Загрузки и CLion.
Явный путь можно задать переменными `MAVEN_CMD` и `CMAKE_CMD`:

```bash
CMAKE_CMD=/путь/к/bin/cmake bash ./rebuild-and-run.sh -o
MAVEN_CMD=/путь/к/bin/mvn bash ./rebuild-and-run.sh -c
```

SDK камеры задаётся `MVS_ROOT` (по умолчанию `/opt/MVS`).

Запуск выполняется отдельно:

```bash
bash ./run-supervised.sh
bash ./run-supervised.sh -f    # Без автозапуска фронтенда
bash ./run-rt.sh -r           # Только логирование системы, без запуска сервисов
bash ./run-supervised.sh -r --rt-interval 2
CONFIG=/path/to/config.yaml bash ./run-supervised.sh
```

Для systemd: `sudo systemctl start defect-detector.service`.
Остановка интерактивного запуска — Ctrl+C; отдельная полная остановка —
`bash ./stop-dev.sh`. Штатный backend-launcher сохраняет отдельную сессию киоска.

Тесты: `bash ./run-all-tests.sh`.
Настройки CPU и диагностика: [RT-LINUX.md](RT-LINUX.md).
Логи: [LOGGING.md](LOGGING.md).
Подготовка ПК и киоска: [deploy/linux/README.md](deploy/linux/README.md).

Режим `-r` в `run-rt.sh` и `run-supervised.sh` только собирает метрики уже работающей системы. Он не вызывает systemctl/sudo, не запускает и не останавливает приложение и не меняет affinity его процессов. Ctrl+C останавливает только сборщик. Прямой запуск: `bash ./rt-logs.sh --rt-interval 2`; свой каталог: `--log-dir /путь/к/логам`.
