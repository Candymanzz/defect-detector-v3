# Перенос системных настроек заводского Linux-ПК

## Пересборка приложения на Linux

Из корня проекта:

```bash
bash ./rebuild-and-run.sh       # Оркестратор + camera-worker, затем запуск supervisor
bash ./rebuild-and-run.sh -c    # Без пересборки camera-worker
bash ./rebuild-and-run.sh -o    # Без пересборки оркестратора
bash ./rebuild-and-run.sh -f    # Запуск без фронтенда
bash ./rebuild-and-run.sh --build-only  # Только пересборка
```

Скрипт пересобирает только оркестратор и camera-worker. Python, geometry,
positioning и фронтенд используются уже подготовленными: их сборка и установка
зависимостей не выполняются. Для оркестратора нужны JDK 17+ и Maven;
для camera-worker — CMake и C-компилятор. Флаги `-c`, `-o`, `-f` можно сочетать.
`MVS_ROOT` задаёт каталог SDK (по умолчанию `/opt/MVS`).
Другой конфиг: `--config путь/к/config.yaml`; пропуск сборки камеры:
`--skip-camera-worker`. Тесты запускаются отдельно через `run-all-tests.sh`.
До успешной сборки работающие процессы не останавливаются; при запуске
очистку выполняет `run-supervised.sh`. Установленная копия UI в `/opt` этим
скриптом не обновляется.

Для одинаковых ПК с Ubuntu 24.04 и одинаковой нумерацией CPU. Скрипт работает
на Python 3 без дополнительных Python-пакетов. Запускать его на Linux.

## Экспорт с настроенного ПК

Сначала проверьте, что изменения киоска реально установлены в `/opt` и
`/usr/local/bin`, а не только лежат в checkout. Экспорт снимает **установленные**
системные настройки.

```bash
cd /home/o/Документы/core-dev
sudo python3 deploy/linux/system-profile.py export "$HOME/factory-system-profile.zip"
```

Архив содержит:

- `defect-detector.service` и локальные drop-in настройки детектора, Tailscale,
  SSH, irqbalance; состояния их автозапуска;
- сценарий киоска, Xorg-сессию, выбор сессии пользователей `o` и `kiosk`;
- выбор автологина `kiosk` (не полный файл GDM исходной машины);
- действующие параметры изоляции из `/proc/cmdline`, топологию CPU, governor;
- версию ядра и список установленных пакетов для справки.

Не копируются: `/etc/shadow`, machine-id, SSH-ключи, состояние Tailscale,
Ubuntu Pro, сетевые адреса, UUID дисков, приложение и конфиг оборудования.
Не добавляйте пароли или auth keys в Environment/ExecStart экспортируемых служб:
их текст переносится вместе со службами. Просмотрите архив перед распространением.

## Подготовка нового ПК

1. Установите Ubuntu 24.04, создайте `o` с собственным паролем.
2. Установите и загрузите RT-ядро. Для Ubuntu Pro используйте свою регистрацию
   машины и `sudo pro enable realtime-kernel`, затем перезагрузку.
   [Официальная инструкция](https://ubuntu.com/pro-client/docs/en/v32/howtoguides/enable_realtime_kernel/).
   Архив не содержит ядро: его пакеты и лицензия устанавливаются отдельно.
3. Разместите собранное приложение по тому же пути
   `/home/o/Документы/core-dev`, включая `run-rt.sh`, рабочий `config`, JAR,
   Linux-venv и MVS SDK. Windows-venv и Windows-бинарники не переносятся.
   Конфиг камеры/света/ПЛК задайте для конкретной линии.
4. Подготовьте Node.js подходящей версии и соберите интерфейс на Linux:

```bash
sudo apt update
sudo apt install python3 openjdk-17-jdk openssh-server x11-xserver-utils rt-tests
cd /home/o/Документы/core-dev/front-end
npm ci
node node_modules/electron/install.js
npm run build
sudo install -d /opt/defect-detector-ui
sudo cp -a dist electron node_modules package.json /opt/defect-detector-ui/
sudo chown -R root:root /opt/defect-detector-ui
sudo chmod -R go-w /opt/defect-detector-ui
```

Используйте актуальную ветку Linux с уже исправленным `electron/main.cjs`.
Установите Tailscale отдельно и зарегистрируйте **новый** узел со своим именем:

```bash
sudo tailscale up --hostname=factory-detector-02
```

Не копируйте `/var/lib/tailscale` или состояние Ubuntu Pro с первого ПК.

## Применение профиля

Перенесите ZIP и `system-profile.py` на новый ПК. Сначала проверка без изменения
настроек:

```bash
sudo python3 deploy/linux/system-profile.py install "$HOME/factory-system-profile.zip" --check
```

Затем установка:

```bash
sudo python3 deploy/linux/system-profile.py install "$HOME/factory-system-profile.zip"
```

Установщик проверяет CPU, RT-ядро и необходимые файлы. Создаёт `kiosk` при
отсутствии, переносит службы, настраивает автологин, GRUB и постоянный governor,
восстанавливает права sandbox Electron. Имена и пути взяты из исходного профиля.
Старые файлы и сведения о состоянии автозапуска сохраняются в
`/var/backups/defect-detector/<дата>/`. Резервная копия не является автоматическим
откатом: для возврата нужно восстановить файлы, удалить созданные файлы из
`before.json`, вернуть состояния служб, выполнить daemon-reload и update-grub.

GDM, Tailscale и приложение во время установки не перезапускаются. Имя ПК,
пароли и сеть новой машины сохраняются. Дополнительные службы вне списка выше,
BIOS, параметры IRQ отдельных сетевых карт и измеренные задержки не клонируются.

Если в целевой машине уже есть иная изоляция в GRUB, установщик остановится:
сначала согласуйте её с профилем. Одинаковые параметры в разных drop-in могут
дублироваться; предпочтительно применять профиль к чистой машине.

После установки сохраните работу и перезагрузите **с локальным доступом**:

```bash
sudo reboot
```

Затем проверьте:

```bash
python3 deploy/linux/system-profile.py verify "$HOME/factory-system-profile.zip"
sudo journalctl -u defect-detector.service -b -n 100 --no-pager -o cat
```

Проверка CPU-профиля и `active` supervisor не подтверждают готовность всей линии.
На каждой машине нужны проверка реального оборудования и измерение задержек.

Проверки самого установщика (не меняют системные настройки):

```bash
python3 deploy/linux/test_system_profile.py
```
