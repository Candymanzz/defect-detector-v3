# IoInputMonitor — краткий гайд

Консольная утилита для **Digital Input (DI)** платы Hikrobot **MV IO Box**. Читает замыкание/размыкание входов через SDK (`MvIOInterfaceBox.dll`) и опционально шлёт состояние по **UDP** в оркестратор (триггер инспекции).

---

## Что делает программа

1. Открывает COM-порт IO box (не путать с COM подсветки MV-LE).
2. Подписывается на **edge callback** SDK — события при смене уровня DI (без polling).
3. Логирует фронты в консоль: `RISING` = замыкание (LOW→HIGH), `FALLING` = размыкание.
4. При `publish.udp.enabled` — отправляет `1` (замкнуто) / `0` (разомкнуто) на `host:port`.

**Семантика:** `HIGH` / `value=1` = вход **замкнут** (контакт замкнут к общему).

---

## Запуск

```powershell
cd IoInputMonitor
dotnet run
```

Из корня репозитория (подхватит `config/blocks/52-io-input.yaml`):

```powershell
dotnet run --project IoInputMonitor
```

### Полезные режимы

| Команда | Назначение |
|---------|------------|
| `dotnet run -- --list` | COM-порты Windows |
| `dotnet run -- --probe` | Найти COM, на котором реально IO box с DI |
| `dotnet run -- --scan --com COM3` | Однократно показать уровни DI1..DI8 |
| `dotnet run -- --com COM3 --input 3` | Монитор только DI3 (переопределяет конфиг) |
| `dotnet run -- --help` | Справка |

**Сборка exe:**

```powershell
dotnet publish -c Release -r win-x64 --self-contained false
```

Рядом с exe должны лежать `MvIOInterfaceBox.dll`, `MvSerial.dll` (копируются из `ThirdParty/MVS/IO/win64/`).

---

## Структура проекта

```
IoInputMonitor/
├── Program.cs                 # CLI + monitor loop
├── IoDiEdgeTracker.cs         # thread-safe DI pressed + refractory
├── IoCaptureGate.cs           # DI2 arm / DI3 fire / DI1 disarm
├── IoCapturePulseScheduler.cs # один in-flight DO pulse
├── IoBoxSession.cs            # SDK: open/close, DI, edge callback, DO
├── MvIoNative.cs              # P/Invoke MvIOInterfaceBox.dll
├── IoBoxProbe.cs              # сканирование COM
├── IoInputConfigLoader.cs     # config/blocks/52-io-input.yaml
├── IoInputUdpPublisher.cs     # UDP → оркестратор
├── ThirdParty/MVS/IO/win64/   # нативные DLL SDK
└── docs/GUIDE.md
```

Конфиг по умолчанию: `config/blocks/52-io-input.yaml` (ищется вверх от cwd и от папки exe).

---

## Конфигурация (`io_input` в YAML)

```yaml
io_input:
  com_port: COM3
  inputs: [3, 4]           # DI 1..8
  edge: both               # rising | falling | both
  debounce_ms: 50
  configure_sdk: true      # false — не трогать SetInput, как настроено в MVS
  publish:
    udp:
      enabled: true
      host: 127.0.0.1
      port: 9100           # = inspection_trigger.udp.bind_port оркестратора
      format: json         # {"di":3,"value":1}
      inputs: [3, 4]
      send_initial_state: false
```

Переопределение пути: env `IO_INPUT_CONFIG` или `--io-config=path/to.yaml`.

### Параметры edge

| Значение | Поведение |
|----------|-----------|
| `rising` | Только замыкание (LOW→HIGH) |
| `falling` | Только размыкание (HIGH→LOW) |
| `both` | Оба фронта; SDK умеет один фильтр на порт → после события **перевооружаем** противоположный фронт |

### Форматы UDP (`format`)

| format | Пример payload | Заметка |
|--------|----------------|---------|
| `json` | `{"di":3,"value":1}` | Рекомендуется, оркестратор `format: json` |
| `text_di` | `3:1` | |
| `byte_di` | 2 байта: `[di, value]` | |
| `byte` | `0x01` | Legacy, без номера DI |
| `text` | `"1"` | Legacy |

---

## Связь с оркестратором

```
DI замкнулся → IoInputMonitor UDP → оркестратор :9100
    → inspection_trigger (external) → запуск инспекции
```

В `config/blocks/01-core.yaml`: `inspection_trigger.udp.bind_port: 9100`, `format: json` (или `discrete` для legacy).

`value=1` — триггер «нажато/замкнуто», `value=0` — «отпущено».

### DO с IoInputMonitor

На шину бокса уходит **только DO5** (импульс Line0 камер после DI3↑).
Код reject/DO1–4/DO6 удалён. Брак / ready / fault — **только FINS**.

---

## Типичные проблемы

| Симптом | Решение |
|---------|---------|
| `MvIOInterfaceBox.dll не найдена` | DLL в `ThirdParty/.../win64`, пересобрать проект |
| COM открылся, но не IO box | Это MV-LE (подсветка), не DI — `--probe` |
| Порт занят `0x80000004` | Закрыть второй IoInputMonitor, MVS Client, LightServer |
| Нет событий при `edge: both` | Проверить `configure_sdk` и debounce; смотреть начальный уровень DI в логе |
| Оркестратор не реагирует | Совпадают ли port/format; `publish.udp.enabled: true` |
| Сигналы «не прекращаются» | См. ниже |

### Почему сигналы продолжают присылаться

1. **`direction_latch: true` без снятия** — после первого DI2=1 каждый DI3↑ шлёт DO/UDP до рестарта. Сейчас: `disarm_on_work_low: true` + DI1↓, или `POST http://127.0.0.1:9101/capture-disarm`.
2. **`debounce_ms: 0`** — bounce контакта → лавина UDP и очередь DO. Ставь 20–50 мс.
3. **UDP не режется capture-gate** — gate блокирует только DO5; DI1/DI2/DI3 всё равно уходят в оркестратор на каждый фронт (`edge: both` = rise+fall).
4. **Несколько `Task.Run` на bounce** — перекрывающиеся импульсы; теперь один in-flight (`IoCapturePulseScheduler`).

---

## SDK или «просто GPIO»?

**Нужен официальный Hikrobot MVS IO SDK** (`MvIOInterfaceBox.dll` + `MvSerial.dll`). Это не Linux/Windows GPIO и не сырой RS-232 протокол:

| Вариант | Статус |
|---------|--------|
| Official MV IO SDK over COM | **Единственный рабочий путь** в этом проекте |
| Raw GPIO / `System.Device.Gpio` | Нет — плата не экспонирует GPIO чип хосту |
| Прямой serial без SDK | Нет — протокол закрыт в DLL |
| Edge callback без polling | Только через `MV_IO_RegisterEdgeDetectionCallBack` |

Железо — **MV IO Box** на COM. «GPIO» здесь = дискретные DI/DO **внутри** бокса, доступные только через SDK.

---

## Поток данных (кратко)

```mermaid
flowchart LR
    A[MV IO Box DI] -->|COM + SDK callback| B[IoBoxSession]
    B --> C[IoDiEdgeTracker + IoCaptureGate]
    C --> D[Console log]
    C --> E[IoInputUdpPublisher]
    C -->|FireDo + scheduler| F[DO5 Line0]
    E -->|UDP| G[Оркестратор :9100]
```
