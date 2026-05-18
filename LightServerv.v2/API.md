# LightServer API

REST API для управления контроллером подсветки **MV-LE** (Hikvision MVS SDK).

- **Базовый URL:** `http://localhost:5080` (см. `Properties/launchSettings.json`)
- **Префикс маршрутов:** `/api`
- **Swagger UI:** `http://localhost:5080/swagger`
- **Content-Type** для POST: `application/json`

Имена полей в JSON — **camelCase** (`deviceIndex`, `lightControllerSource` и т.д.).

---

## GET `/api/devices`

Список устройств, обнаруженных MVS SDK. Нужен, чтобы выбрать `deviceIndex` для контроллера MV-LE.

### Запрос

Тело не требуется.

```http
GET /api/devices
```

### Ответ `200 OK`

```json
{
  "count": 2,
  "devices": [
    {
      "index": 0,
      "tLayerType": "MvGigEDevice",
      "modelName": "MV-CA016-10UC",
      "serialNumber": "DA1234567"
    },
    {
      "index": 1,
      "tLayerType": "MvGigEDevice",
      "modelName": "MV-LE200-xxx",
      "serialNumber": "LE9876543"
    }
  ]
}
```

| Поле | Тип | Описание |
|------|-----|----------|
| `count` | `int` | Количество устройств |
| `devices` | массив | Список устройств |
| `devices[].index` | `int` | Индекс для `deviceIndex` в POST `/api/light` |
| `devices[].tLayerType` | `string` | Тип транспортного слоя MVS |
| `devices[].modelName` | `string` | Модель |
| `devices[].serialNumber` | `string` | Серийный номер |

### Ответ `400 Bad Request`

Ошибка перечисления устройств (SDK).

```json
{
  "success": false,
  "error": "EnumDevices failed: 0x80000001"
}
```

---

## POST `/api/light`

Включить или выключить подсветку на выбранных каналах и задать яркость (`LightBrightness`, 0–255).

Устройство открывается на время запроса, настраиваются каналы, затем закрывается.

### Запрос

```http
POST /api/light
Content-Type: application/json
```

```json
{
  "deviceIndex": 1,
  "lightControllerSource": "On",
  "channels": [1, 2, 3, 4],
  "brightness": [50, 100, 150, 255]
}
```

| Поле | Тип | Обязательное | По умолчанию | Описание |
|------|-----|--------------|--------------|----------|
| `deviceIndex` | `int` | нет | `1` | Индекс из `GET /api/devices` |
| `lightControllerSource` | `string` | нет | `"On"` | Режим: `"On"` / `"Off"`. Также принимаются: `on`, `off`, `1`, `0`, `true`, `false` (регистр не важен) |
| `channels` | `int[]` | нет | `[1, 2, 3, 4]` | Каналы **1–4** |
| `brightness` | `int[]` | нет | — | Яркость **0–255** для каждого канала; порядок совпадает с `channels`. Если `lightControllerSource` = `"On"` и массив не передан — на все каналы ставится **255**. При `"Off"` без `brightness` яркость не меняется (только source) |

**Правила:**

- Длина `brightness` должна совпадать с длиной `channels`, иначе `400`.
- Каждый элемент `channels` — от **1** до **4**.
- Значения `brightness` ограничиваются диапазоном 0–255.
- Устройство по индексу должно поддерживать GenICam-узел `LightControllerSelector` (контроллер подсветки).

### Ответ `200 OK`

```json
{
  "success": true,
  "message": "Channels [1, 2, 3, 4] -> On, brightness [50, 100, 150, 255].",
  "error": null,
  "deviceIndex": 1,
  "lightControllerSource": "On",
  "channels": [1, 2, 3, 4],
  "brightness": [50, 100, 150, 255]
}
```

| Поле | Тип | Описание |
|------|-----|----------|
| `success` | `bool` | `true` при успехе |
| `message` | `string` | Текст о применённых каналах и яркости |
| `error` | `string?` | `null` при успехе |
| `deviceIndex` | `int` | Эхо из запроса |
| `lightControllerSource` | `string` | Эхо из запроса |
| `channels` | `int[]` | Эхо из запроса |
| `brightness` | `int[]?` | Эхо из запроса (может быть `null`) |

### Ответ `400 Bad Request`

```json
{
  "success": false,
  "message": null,
  "error": "Invalid channel 5. Use 1–4.",
  "deviceIndex": 1,
  "lightControllerSource": "On",
  "channels": [1, 5],
  "brightness": null
}
```

Типичные тексты `error`:

| Сообщение | Причина |
|-----------|---------|
| `brightness length (N) must match channels length (M).` | Несовпадение длин массивов |
| `Invalid device index: N (count M).` | Нет устройства с таким индексом |
| `EnumDevices: 0x...` / `Open: 0x...` | Ошибка MVS SDK |
| `Device [N] is not a light controller (no LightControllerSelector).` | Устройство не MV-LE |
| `Invalid channel N. Use 1–4.` | Недопустимый номер канала |
| `Failed channel N, source On, brightness 255.` | Не удалось записать параметры на канал |

---

## Примеры

### Свет включён, разная яркость по каналам

```http
POST /api/light
Content-Type: application/json

{
  "deviceIndex": 1,
  "lightControllerSource": "On",
  "channels": [1, 2, 3, 4],
  "brightness": [50, 100, 150, 255]
}
```

### Свет выключен

```http
POST /api/light
Content-Type: application/json

{
  "deviceIndex": 1,
  "lightControllerSource": "Off",
  "channels": [1, 2, 3, 4]
}
```

### Включить каналы 1 и 2 на полную яркость (без `brightness`)

```http
POST /api/light
Content-Type: application/json

{
  "deviceIndex": 1,
  "lightControllerSource": "On",
  "channels": [1, 2]
}
```

На каналах 1 и 2 будет яркость **255**.

---

## Общие коды HTTP

| Код | Когда |
|-----|--------|
| `200` | Успешное выполнение |
| `400` | Ошибка SDK, неверные параметры или устройство не подходит |

Дополнительные эндпоинты в проекте не объявлены; вся логика — в `LightController` (`GET /api/devices`, `POST /api/light`).
