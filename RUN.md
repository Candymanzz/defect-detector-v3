# Сборка и запуск на Linux

Для ветки `линукс+кресты`: [сборка, конфигурация и тесты C++-бэкенда](deploy/linux/CPP-DETECTOR.md).
Основная сборка теперь также включает C++-детектор и Java-геометрию;
`-d` пропускает C++, `-g` — геометрию. Для сборки только фронта используйте `-c -o -d -g`.

Пересборка из корня проекта:

```bash
bash ./rebuild-and-run.sh             # Оркестратор, геометрия, JNI камер, C++ и фронт
bash ./rebuild-and-run.sh -c          # Без пересборки JNI камер
bash ./rebuild-and-run.sh -o          # Без пересборки оркестратора
bash ./rebuild-and-run.sh -c -o -g -d # Только фронт с установкой
bash ./rebuild-and-run.sh -f          # Весь бэкенд, без фронта
```

Скрипт только собирает выбранные компоненты и завершает работу. Он не
останавливает и не запускает сервисы, не вызывает systemctl. Фронт собирается
через `npm run build`; `dist/`, `electron/` и `package.json` копируются в
`/opt/defect-detector-ui`. Установленный Electron и его `node_modules` используются
повторно. Для защищённого каталога скрипт запрашивает `sudo` до начала сборки.
Путь установки можно изменить через `FRONTEND_INSTALL_DIR`. После установки
нужно отдельно перезапустить Electron киоска, чтобы загрузить новую версию.

Однократная настройка обновлений без повторного ввода пароля (пользователь `o`):

```bash
sudo bash deploy/linux/setup-frontend-updates.sh o
bash ./rebuild-and-run.sh -c -o -g -d --restart-frontend
```

Настройка передаёт `o` только `dist/`, `electron/` и `package.json`. Каталог
приложения, Electron в `node_modules` и `chrome-sandbox` сохраняют прежние права
доступа к среде исполнения; корень приложения принадлежит `root`.
`--restart-frontend` вызывает через `sudo -n` фиксированную команду
`/usr/local/sbin/defect-detector-restart-ui` без аргументов. Она завершает только
процессы Electron пользователя `kiosk`; цикл киоска запускает их снова через 5 секунд.
Без этого параметра пересборка не перезапускает интерфейс. Команда настройки
сохраняет сведения о прежних правах и копии существующего helper/sudoers в
`/var/backups/defect-detector-frontend.*`.

Java-геометрия и C++-инспекция включены в сборку. Python и отключённое Java-позиционирование не пересобираются.
`-f`, `--skip-frontend`, `--no-frontend` пропускают сборку и установку фронта.
Старые параметры `-r`, `--build-only`, `--config`, `--rt-interval`
принимаются для совместимости, но на сборку не влияют; скрипт сообщает об этом.

Для оркестратора нужны JDK 17+ и Maven; для JNI камер — тот же JDK, CMake и C-компилятор.
Для C++ нужны C++17-компилятор, pkg-config, OpenCV, cpp-httplib и nlohmann-json;
команда установки пакетов Ubuntu приведена в [инструкции](deploy/linux/CPP-DETECTOR.md).
Камеры работают внутри JVM; сборка и настройка описаны в [CAMERA-JNI.md](orchestrator-java/CAMERA-JNI.md).
Для фронта нужны Node.js >=20.19.0 и npm; скрипт обнаруживает установки NVM.
Если TypeScript/Vite ещё не установлены, он выполняет `npm ci`. При изменении
зависимостей выполните `npm ci` в `front-end` перед пересборкой.
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
