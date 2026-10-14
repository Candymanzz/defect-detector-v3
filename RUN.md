# Сборка и запуск на Linux

Из корня проекта:

```bash
bash ./rebuild-and-run.sh       # Сборка оркестратора и camera-worker, затем запуск
bash ./rebuild-and-run.sh -c    # Только оркестратор
bash ./rebuild-and-run.sh -o    # Только camera-worker
bash ./rebuild-and-run.sh -f    # Запуск без фронтенда
bash ./rebuild-and-run.sh -r    # Запуск с диагностикой CPU и RT-ядра
bash ./rebuild-and-run.sh --build-only  # Только сборка
```

Для сборки оркестратора нужны JDK 17+ и Maven; для camera-worker — CMake
и C-компилятор. Инструменты пропущенного компонента не требуются для сборки.

Если появляется `Command not found: mvn` или `Maven not found`, установите Maven:

```bash
sudo apt update
sudo apt install maven
bash ./rebuild-and-run.sh -r
```

Скрипт также ищет Maven через `MAVEN_HOME`, `M2_HOME`, Maven Wrapper и стандартные
каталоги установок IntelliJ IDEA. Явный путь можно передать так:
`MAVEN_CMD=/путь/к/bin/mvn bash ./rebuild-and-run.sh -r`.

Если CMake уже скачан, но не найден в `PATH`, задайте путь к исполняемому файлу:

```bash
CMAKE_CMD="$HOME/Загрузки/cmake-<версия>-linux-x86_64/bin/cmake" bash ./rebuild-and-run.sh -r
```

Подставьте реальное имя распакованного каталога. Скрипт также ищет CMake в
`/opt`, `/usr/local/bin`, `/snap/bin`, `~/.local/bin`, `~/Downloads`, `~/Загрузки`
и стандартных установках CLion. Поддерживается `CMAKE_HOME` — каталог,
содержащий `bin/cmake`. Скачанный архив сначала нужно распаковать.

Python, geometry, positioning и фронтенд не пересобираются и не переустанавливаются;
при запуске используются уже подготовленные компоненты.
Флаги можно сочетать: `-c -f`, `-o -f` или `-c -o` (запуск без пересборки).
SDK камеры задаётся через `MVS_ROOT` (по умолчанию `/opt/MVS`).
Рабочий конфиг по умолчанию — `config/config.yaml`.

Перед пересборкой скрипт останавливает supervisor, оркестратор и все процессы
сервисов этого проекта. Он дожидается завершения, затем собирает только два
компонента. При ошибке остановки сборка не начинается. `--build-only` также
останавливает приложение, но после сборки не запускает его.

Для установленного systemd-приложения останавливается соответствующая служба;
если нужны права, сначала выполните `sudo systemctl stop defect-detector.service`.
При ручном запуске скрипт не включает службу обратно — он запускает supervisor
в текущем терминале. Установленный киоск приостанавливает цикл перезапуска UI;
при следующем запуске без `-f` этот цикл возобновляется. Для другого каталога
установленного UI задайте `IML_INSTALLED_FRONTEND_DIR` (по умолчанию `/opt/defect-detector-ui`).

Штатный запуск и завершение backend через `run-supervised.sh` оставляют отдельную
сессию установленного киоска работающей (`--skip-kiosk` в служебной остановке).
Это позволяет автозапуску systemd от пользователя `o` работать без запроса sudo
для управления процессами пользователя `kiosk`. Полная остановка перед
пересборкой по-прежнему включает киоск и может требовать права sudo.

Остановка systemd ставится в очередь через `--no-block`: затем скрипт завершает
процессы и проверяет состояние службы, её stop job и cgroup. Если служба остаётся
в `deactivating`, завершаются оставшиеся процессы именно этой службы через
`systemctl kill --signal=SIGKILL`, после чего состояние проверяется повторно.
Если остановка всё ещё не завершена, сборка отменяется с диагностикой состояния.

Диагностика с периодом две секунды:

```bash
bash ./rebuild-and-run.sh -r --rt-interval 2
bash ./run-supervised.sh -r
bash ./run-rt.sh -r        # С существующей привязкой к CPU
```

Файлы `rt-*.jsonl` лежат в общей папке сессии `logs/<дата-время>/`.
Подробности метрик и ограничений — [RT-LINUX.md](RT-LINUX.md).

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
Отдельная остановка всех сервисов: `bash ./stop-dev.sh`.
При работе через systemd управляйте службой `defect-detector.service`.

Тесты: `bash ./run-all-tests.sh`.
Логи каждой сессии: `logs/<дата-время>/` — см. [LOGGING.md](LOGGING.md).
Подготовка Linux-ПК и киоска: [deploy/linux/README.md](deploy/linux/README.md).
Скрипт сборки не обновляет установленную копию интерфейса в `/opt`.
