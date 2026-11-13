# femboy36: установка конфигурации с единым ips.yaml — 2026-11-09

## Источник и границы версии

По запросу владельца на `femboy36:/home/o/Документы/core-dev` установлен
**`6d8cad99`** — вершина `gitlab/boobs+pasha`, которую владелец сообщил как
успешно прошедшую GitLab CI. Это проверено через `git ls-remote gitlab`;
сам статус приватного pipeline агентом через API не подтверждён (API без
авторизации возвращает 404). Более поздние локальные frontend-коммиты
`a120fe68`/`ce6a5fe2` в это развёртывание не включались.

Сервер исходно имел чистую ветку `boobs+pasha` на `832e4fc0`, активный
`defect-detector.service` с PID 2183 и WorkingDirectory этого checkout.
Исходники доставлены Git bundle и применены fast-forward без изменения истории.
Отдельная сборка точного `6d8cad99` сделана локально из `git archive`:
Maven offline package, Linux MVS JNI Release, C++ Release и frontend build.
Это сборка из того же исходника, а не скачанный артефакт CI: доступа к
приватным GitLab artifacts через API не было.

## Конфиги и сохранность привязок

Локальный `config/ips.yaml` загружен на сервер с правами 0600 и подтверждён
через `git check-ignore`. Перед установкой Python на сервере сравнил все
10 адресов из ips.yaml с текущим `config/blocks/10-cameras.yaml` и с
`tools/camera-ip-bindings.json`: **все совпали**, включая привязку к serial.
IP контроллеров света и PLC также совпали с исходными рабочими YAML.
Файл аппаратных привязок не редактировался; SHA256 после установки:
`dddbd337d71de4e2e15d66898dcd95b34e12f5a98a92275b4ef6193f3b88f2e6`.
Ключи `camera_0` … `camera_9` привязаны к ID, а не к позиции после сортировки.

Сохранён `frame.format: Bayer RG 12 Packed`; новый Java-загрузчик на сервере
подтвердил этот формат, все IP, 10 камер и 5 устройств света и создал внутренний
`config/.camera-worker.generated.json`. Физический захват новым runtime
до перезапуска не проверен. JSON ручной конфигурации и исторические YAML
обёртки перемещены в backup; `config/data/` осталась на месте.

## Backup и зависимости

Полная резервная копия конфигурации, данных настройки, файла serial-привязок,
рабочего JAR/JNI/C++ и UI:
`/home/o/Документы/defect-detector-backups/deploy-6d8cad99-20261109-124920/`.
В ней `application.tar.gz`, `ui.tar.gz`, `commit.txt`; прежние untracked
YAML/JSON отдельно в `old-config-files/`. Runtime-логи и crash dumps исключены.
На момент создания backup суммарно 18 613 734 байта (до добавления старых файлов).
Локальная сборка/архив находятся в `/tmp/kal-femboy36-deploy`, серверная staging
папка — `/tmp/kal-deploy-6d8cad99`; это временные файлы, не механизм отката.

Старый серверный набор OpenCV не содержал `opencv_video`, нужного текущему C++.
Новый бинарник собран с относительным RPATH `$ORIGIN/.runtime` и отдельным
набором библиотек в `cppAnalisSurface/build/.runtime` (138 библиотек,
177 021 096 байт). Системные пакеты/SDK и systemd environment не менялись.
`ldd -r` на сервере подтвердил отсутствие неразрешённых библиотек/символов
C++ и JNI, включая использование установленного MVS 4.8.1.2; локальная сборка
использовала SDK 4.9.0.3. Это ABI/link-проверка, а не аппаратная валидация.

## Установка и проверки

Контрольная сумма доставленного архива:
`fc2e559800574054629b172e5f2d8b61508213062f77c18963cc5df569b6f52c`.
Хеши установленных JAR/JNI/C++ совпали со staging. UI dist/electron и package.json
обновлены; package-lock.json на установленном киоске отсутствовал и не создавался.
Каталог `/opt/defect-detector-ui` принадлежит root, поэтому временная атомарная
запись package.json сначала получила PermissionError. Завершено через запись
существующего package.json, принадлежащего пользователю o; остальные файлы
были успешно установлены. Это не меняет права каталога.

Локально: C++ **12/12 CTest**, MVS **5/5 CTest**, Maven package и frontend build
успешны. GitLab CI отмечен успешным со слов владельца. Новые исходники и
локальные конфиги загружены, `git status --short` на сервере после установки пуст.

## Перезапуск и итоговая проверка

Первый автоматический restart не состоялся: sudo запросил пароль, pkexec
сообщил `No authentication agent found`. Прежний PID2183 сохранялся до
перезагрузки пользователем; затем владелец сообщил, что всё перезагрузил.
Проверка нового boot подтвердила:

- systemd active/running, **MainPID2111**, ActiveEnterTimestamp
  **2026-11-09 13:01:39 +03**; JVM оркестратора PID2274, C++ PID2319.
- Java `:8099/health` → `ok`, C++ `:8000/health` → status ok.
- `/api/cameras` содержит все ID0…9 и прежние analysis profiles.
- GET `/api/camera/{id}/settings`: **10/10 status ok**, MVS доступен,
  `pixel_format=BayerRG12Packed`, trigger line0, streaming false. Сохранённые
  ROI/tuning применены. Это подтверждает открытие камер и режим SDK;
  новый физический кадр/конвертацию и цикл инспекции этой проверкой не вызывали.
- GET `/api/orchestrator/light/bank`: HTTP200, native-mvs, configured5,
  ready5, initialized true, errors пусто.
- IO в журнале нового boot подключился session1; DI/DO scan продолжается.
- GET `/api/client/plc/status`: HTTP200/ok, enabled true,
  inspection_enabled false, inspection_in_flight false. Это проверка API
  состояния, не новый тест физической отбраковки/полного FINS-цикла.
- Electron киоска PID1971 запущен **13:01:37** из `/opt/defect-detector-ui`;
  renderer PID2201 также создан в новом boot. SHA256 index.html,
  подключённых JS/CSS и Electron main.cjs совпали с установленной сборкой.
  Поэтому новый процесс загружает установленный frontend `6d8cad99`;
  повторный restart киоска не требовался. Визуальная проверка экрана не выполнена.

Развёртывание **6d8cad99 завершено**. Более поздние локальные frontend-коммиты
по-прежнему не входят в эту версию; обновление frontend подтверждено относительно
установленного GitLab-коммита, а не относительно локального HEADce6a5fe2.
Полный производственный цикл/кадры/брак/срок500мс не проверялись, инспекция на
срезе выключена. После перезагрузки новых изменений runtime не вносилось.
