# Встроенный IO: события DI через JNA

Аппаратная цепочка **In3 → Timer5 → DO5 → Line0 камер** настроена в MVS.
Java не запускает Timer5 и не меняет аппаратные связи камер.
Брак запускается через Timer6/Timer7 на DO6/DO7 из `io_reject`.
Отдельный IO-процесс не используется.

## Backend событий

`backend: mv_io` подключается напрямую к IO SDK и регистрирует
`MV_IO_RegisterEdgeDetectionCallBack`. В этом режиме DI не опрашиваются
периодически. Проверка связи обращается к версии прошивки, а не к уровню DI.
Callback SDK только добавляет уведомление во внутреннюю очередь; отдельный
поток передаёт его обработчику DI и перевооружает вход. Обработка инспекции
не выполняется в callback SDK.

SDK хранит один фронт на каждом входе. Для DI2 с `edge: both` Java выбирает
противоположный фронт после каждого события; DI3 с `edge: rising` снова
вооружается на rising. Каждый полученный rising DI3 передаётся дальше,
включая повторные rising без falling. Фильтров debounce/refractory и
задержки в SDK нет (`Glitch=0`, `DelayTime=0`). Перевооружение занимает время;
потери на конкретном устройстве проверяются на линии, тест SDK-имитации
не подтверждает аппаратную частоту.

`configure_inputs: true` сохраняет параметры обнаружения фронтов DI,
настраивает их для выбранных входов и восстанавливает при закрытии.
Таймеры и выходы не затрагиваются. При `false` Java только регистрирует
callback: входы должны быть заранее настроены, и их повторное срабатывание
должно обеспечиваться устройством. DO SDK имеет API чтения профиля выхода,
но это не фактический уровень DO: `outputs` для `mv_io` должен быть `{}`.
Состояния DO5 в этом backend не измеряются.

## Конфигурация

Настройки DI/DO вынесены в `config.exemple/blocks/52-io.yaml`, который
подключён через `config.exemple/config.yaml`. Там находятся SDK и подписки
DI, правила получения кадров, аппаратный режим Line0 и DI-подсветка.
Параметры подключения находятся в `integration.inspection_trigger.mvs_io`.
Следующий пример относится к альтернативному backend событий на Windows
(для него отключите `io_reject` или выберите `mode: level`). Текущий шаблон
с аппаратными таймерами использует MvCameraControl, описанный ниже:

```yaml
mvs_io:
  enabled: true
  backend: mv_io
  input_mode: events
  library_windows: 'orchestrator-java/native/mvs-io/win64/MvIOInterfaceBox.dll'
  port_windows: 'COM3'
  library_linux: ''
  port_linux: ''
  serial: ''
  dependencies: []
  configure_inputs: true
  health_check_ms: 1000
  reconnect_ms: 1000
  shutdown_timeout_ms: 5000
  outputs: {}
  inputs:
    2: {port: 2, edge: both, invert: false}
    3: {port: 3, edge: rising, invert: false}
io_input:
  work_port: 1
  direction_port: 2
  trigger_port: 3
  shutdown_port: 0
  di3_only: true
  trigger_edge: rising
  require_direction: true
  require_work: false
  direction_latch: false
  direction_latch_on_work: false
  external_hardware_capture: true
  capture_delay_ms: 0
```

Ключ `inputs` — логический DI в правилах Java; `port` — физический вход SDK
1..8. Это позволяет менять подключение без изменения кода. `edge`:
`rising`, `falling`, `both`; `invert` инвертирует переданное Java состояние.
Для изменения ролей используйте `io_input.direction_port`, `trigger_port`,
`work_port`, `shutdown_port`. Получение кадров разрешено при активном
настроенном входе направления (по умолчанию DI2=1). При выборе только DI2/DI3
DI1 не контролирует работу, а DI4 не выключает систему. Чтобы вернуть эти
функции, добавьте соответствующие входы с `edge: both` и включите их роли.

Общие `library`/`port` можно использовать вместо параметров с суффиксом ОС.
Суффиксы `_windows`/`_linux` имеют приоритет. Относительные пути библиотек
отсчитываются от рабочего каталога оркестратора; удобнее использовать
абсолютные пути. `dependencies` — дополнительные библиотеки для предварительной
загрузки. Нужны 64-битная JVM и совместимые нативные библиотеки SDK.

DLL и MvSerial.dll перенесены в `orchestrator-java/native/mvs-io/win64`;
каталог старого сервиса больше не требуется. Windows DLL не работает на Linux.
В установленном `/opt/MVS` текущей Linux-машины совместимая IO SDK `.so`
не найдена. Для Linux нужно получить её у поставщика, указать путь и
подтвердить ABI на оборудовании. Заголовки/структуры Java соответствуют
существующему Windows P/Invoke, совместимость иной Linux SDK не проверена.
Без библиотеки или порта будет явная ошибка; перехода на опрос нет.
В шаблоне порты оставлены пустыми, чтобы не открывать случайный COM.

У камер: `capture_source: hik`, `capture_trigger_mode: line0`.
В `integration.simultaneous_line_capture`: `enabled: true`,
`hardware_line_trigger: true`. Camera-worker очищает накопленные кадры SDK
перед `wait_frame` и ждёт новый аппаратный кадр.

## Брак Omron через аппаратные таймеры

Текущий шаблон `blocks/52-io.yaml` выбирает `backend: mv_camera`, `input_mode: poll`
и `io_reject.mode: timer`. Одна связь MvCameraControl читает DI2/DI3 и запускает
таймеры брака. У имеющейся MvIOInterfaceBox.dll нет экспорта запуска таймеров;
её handle нельзя передавать в MvCameraControl. В режиме событий `mv_io`
доступен только прежний `io_reject.mode: level`, с прямой записью уровня DO.
Автоматического переключения backend нет. При опросе короткий импульс DI может
пройти между чтениями; `poll_ms` задаёт паузу, а не длительность обмена по serial.

```yaml
io_reject:
  enabled: true
  mode: timer
  ack_timeout_ms: 3000
  queue_size: 64
  shutdown_timeout_ms: 5000
  protected_outputs: [5]
  groups:
    0: {port: 6, timer: Timer6, line: Out6}
    1: {port: 7, timer: Timer7, line: Out7}
```

В MVS настройте Timer6/7: `TimerTriggerSource=Software`, режим с доступной
командой `TriggerSoftware`; Out6: `LineSource=Timer6`, Out7: `LineSource=Timer7`.
Длительность, задержка, количество импульсов и полярность задаются в MVS.
Java проверяет источник, маршрут и доступность команды, затем выполняет
`TimerSelector=TimerN → TriggerSoftware` и восстанавливает селекторы.
Она не меняет параметры таймера, маршруты, длительность или уровни DO.
Timer5 и Out5 запрещены для программного запуска брака и ручного теста.

Каждый результат FAIL запускает соответствующий таймер один раз. PASS не
посылает команду, завершение импульса обеспечивает контроллер. Ранний брак
пластика запускает по одному таймеру для каждой группы; итог того же sequence
не запускает второй импульс. Удержания до PASS
в режиме timer нет. При повторном запуске работающего таймера поведение
определяется настройками и прошивкой MVS, а не очередью Java.

При включённом `io_reject` брак исключён из записей FINS, включая ручные;
ready/fault, режим ручки и остальные регистры продолжают работать через FINS.
При ошибке таймера переключения на FINS нет. С выключенным `io_reject`
сохраняется прежний маршрут брака через FINS.

## Ручной тест лампочки на Timer6

Это отдельная программа: Maven-тесты не обращаются к оборудованию.
Остановите оркестратор и MVS-клиент, занимающие тот же serial-порт.
Из корня репозитория после сборки можно выполнить `./orchestrator-java/test-timer6.sh`
(подключение и группа 0 берутся из конфига). Прямой вызов с явным портом:

```bash
tools/java/bin/java -Diml.log.dir=/tmp/mvs-timer-smoke \
  -cp orchestrator-java/target/orchestrator-0.1.0-SNAPSHOT.jar \
  com.example.iml.orchestrator.integration.io.mvs.MvsTimerSmokeMain \
  --port 'COM_Port#ttyS4' --timer Timer6 --line Out6
```

Порт заменить на имя SDK своей машины (`MvsIoDiagnosticMain --list`).
`--library` задаёт путь SDK; по умолчанию Linux `/opt/MVS/lib/64/libMvCameraControl.so`,
Windows `MvCameraControl`. Можно вместо этих параметров использовать
`--config config.exemple/config.yaml --group 0`: подключение и Timer6/Out6
берутся из конфига. Программа запускает один импульс. Сообщение `ONE pulse accepted`
означает успешную команду SDK; физическое загорание проверяется по лампочке.
При неверном маршруте или источнике команда не отправляется, настройки не исправляются.

Журнал IO находится в `logs/io-module.log` (или каталоге `-Diml.log.dir`).
Запуск содержит `timer_trigger_accepted`, выдача брака — `reject_timer`;
ошибки доставки записываются с группой, sequence и таймером. SDK-вызовы
с кодом результата и временем доступны в том же файле на DEBUG.
