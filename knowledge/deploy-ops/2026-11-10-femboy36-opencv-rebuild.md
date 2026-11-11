## 2026-11-10 — Проверка после daemon-reload

Повторная SSH-проверка подтвердила `NeedDaemonReload=no`, backend active/running,
MainPID25707 сохранён. Java/C++ health и learning API HTTP200; камеры10/10 status ok,
BayerRG12Packed, свет ready5/5. Новый C++ и 12 UI-файлов совпадают со сборкой,
Electron продолжает работать. Инспекция выключена; производственный цикл не проверен.
Предупреждение systemd устранено; упоминания ожидающего daemon-reload ниже исторические.

## 2026-11-10 — Перезапуск после сборки подтверждён

Пользователь выполнил finish.sh с sudo. Backend active/running с 15:28:20 +03,
MainPID25707; Java PID25792, C++ PID25838. SHA256 `/proc/25838/exe` совпал с новой
сборкой C++. Java/C++ health и learning API HTTP200; список норм пуст после
штатного bootstrap reset. Все10 камер `/settings`: status ok, BayerRG12Packed,
streaming false. Свет initialized/ready5 из5, errors пусто. PLC API ok/enabled,
inspection_enabled=false, inspection_in_flight=false. Производственный цикл
и свежие кадры этой проверкой не запускались.

Новый Electron PID26145/renderer26200 работает из /opt/defect-detector-ui;
12 опубликованных UI-файлов, включая package.json, совпали со staging по SHA256.
Таким образом, установка и перезапуск подтверждены. Старые упоминания ниже
«перезапуск ожидает sudo» описывают состояние до выполнения finish.sh.

Осталось административное предупреждение `NeedDaemonReload=yes`: systemd просит
перечитать unit/drop-ins через `sudo systemctl daemon-reload`. Загруженные пути
запуска и MVS environment соответствуют показанным файлам на диске; предупреждение
не помешало успешному запуску. У текущей SSH-сессии sudo по-прежнему требует пароль,
поэтому daemon-reload агентом не выполнен. Результат проверок:
`.verification/rebuild-20261110-152204/runtime-check.json`.

# Пересборка femboy36 и OpenCV Video — 2026-11-10

Checkout: `/home/o/Документы/core-dev`, `boobs+pasha`, исходный HEAD `ebdf115b`.
До работы отслеживаемые файлы были чистыми; `deployed-version.txt` уже был untracked.

## Причина и решение

Системные `libopencv-video-dev` и `libopencv-video406t64` версии
`4.6.0+dfsg-13.1ubuntu1` уже установлены. Заголовок
`/usr/include/opencv4/opencv2/video/tracking.hpp` существует, но CMake cache
указывает на локальный `tools/cpp-runtime-ubuntu24/opencv-components`.
Его OpenCVConfig.cmake объявлял только core/imgproc/imgcodecs и задавал
единственный include directory приватного runtime без video.
Поэтому повторная установка системного пакета не исправляла сборку.

Исправлен только локальный игнорируемый OpenCVConfig.cmake: добавлены системный
include directory `/usr/include/opencv4` и импортированная цель `opencv_video`
из `/usr/lib/x86_64-linux-gnu/libopencv_video.so.4.6.0`.
Прежние три компонента и прочие зависимости сохранены в приватном runtime.
Полный переход на системный OpenCV не выполнялся: на машине отсутствуют часть
системных development-пакетов и штатный OpenCVConfig.cmake. Исходники приложения
и CMakeLists.txt не менялись; переносимость этого локального исправления на другие
машины не подтверждена.

## Сборка и проверки

`rebuild-and-run-all.sh --build-only` выполнен с временным FRONTEND_INSTALL_DIR.
Java Maven package, C++ Release, camera_jni Release и frontend TypeScript/Vite
успешно собраны. Java-тесты сборщиком пропущены. C++ CTest: 13/13 прошли.
`ldd -r` опубликованных C++ и JNI не обнаружил отсутствующих библиотек или
неразрешённых символов. SHA256 всех существовавших YAML в config/ не изменились.

## Runtime и оставшийся шаг

JAR/JNI/C++ опубликованы штатным сборщиком в checkout, но работающие процессы
не перезапускались. Новый UI подготовлен в staging и ещё не установлен в /opt.
Сервис на входе active, MainPID 20733. `sudo -n true` требует пароль;
`pkexec --disable-internal-agent /usr/bin/true` вернул No authentication agent found.
Следовательно, обновление runtime пока не завершено и сборку нельзя считать
подтверждением работы нового приложения на оборудовании.

Для завершения подготовлен `finish.sh`: резервная копия установленного UI,
копирование нового интерфейса, restart backend, ожидание трёх HTTP API и restart
киоска. Требует запуска пользователем с sudo в интерактивном терминале. При полном
запуске приложения дообученные нормы сбрасываются штатной логикой; производственный
цикл, новые кадры, вердикт и срок 500 мс не проверялись.

Диагностика и резервные копии:
`.verification/rebuild-20261110-152204/` — build.log, ctest.log, ldd-отчёты,
OpenCVConfig.before.cmake, before/ (предыдущие бинарники), config-hashes-before.json,
ui/ и finish.sh. Скрипт finish.sh ещё не запускался. Коммит/push не выполнялись.
