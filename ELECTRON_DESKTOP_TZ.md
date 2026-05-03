# ТЗ: Electron-приложение для Defect Detector

## 1. Что делаем

Нужно сделать desktop-приложение на Electron.

Electron нужен только как оболочка для frontend. Backend остается удаленным сервером. Внутрь Electron не переносим Python, модели, камеру и логику детекции.

Приложение должно:

- открываться отдельным desktop-окном;
- показывать frontend с тем функционалом, который уже есть сейчас;
- отправлять запросы на удаленный backend;
- получать от backend результаты проверки и изображения;
- давать оператору работать с эталоном, проверкой, ROI-контуром и FP-зонами;
- писать локальные логи работы desktop-приложения;
- позволять менять адрес backend без пересборки приложения.

Текущий дизайн не считаем финальным. На текущий frontend опираемся только как на технический пример: какие экраны есть, какие действия выполняются, как рисуются контуры, какие данные уходят на сервер и приходят обратно.

## 2. На что опираемся в текущем функционале

В текущем frontend уже есть следующие сценарии:

- выбор `product_type`;
- настройка `threshold`;
- выбор изображения из файла;
- загрузка эталона на сервер;
- получение эталона с сервера;
- запуск проверки изображения;
- запуск проверки через серверный сценарий камеры;
- отображение результата `ГОДЕН` / `БРАК`;
- отображение `anomaly_score`;
- отображение изображений: оригинал, карта разницы, heatmap;
- локальный журнал последних проверок;
- рисование ROI-контура по эталонному изображению;
- рисование технического контура изделия;
- расчет площади ROI и покрытия;
- рисование FP-зон по heatmap;
- сохранение, загрузка и удаление FP-зон;
- подсветка FP-зон, которые участвовали в последней перепроверке.

Новый Electron frontend должен сохранить эти технические возможности. Внешний вид может быть переделан позже.

## 3. Что не делаем

В рамках этого ТЗ не делаем:

- backend внутри Electron;
- локальный запуск моделей;
- локальную обработку изображений;
- camera server;
- новую backend-логику;
- финальный UI-дизайн;

## 4. Конфигурация приложения

В desktop-приложении должен быть настраиваемый адрес backend:

```json
{
  "apiBaseUrl": "https://defect-api.example.com"
}
```

Также желательно хранить локально:

- последний выбранный `product_type`;
- последний `threshold`;

Сейчас во frontend адрес backend задан жестко:

```js
const API_URL = "http://localhost:8000";
```

Нужно заменить это на конфиг:

- в web/dev режиме: из `.env`, например `VITE_API_URL`;
- в Electron: из desktop-конфига через preload;
- fallback на `localhost` допустим только для разработки.

## 5. Логи

Electron-приложение должно писать локальные логи.

Логировать:

- выбранный backend URL;
- ошибки загрузки frontend;
- ошибки запросов к backend;
- недоступность backend;
- таймауты;
- основные действия оператора:
  - загрузка эталона;
  - запуск проверки;
  - запуск проверки с камеры;
  - сохранение ROI-контура;
  - создание FP-зоны;
  - удаление FP-зоны.

Пример записи:

```json
{
  "ts": "2026-04-30T09:00:00.000Z",
  "level": "info",
  "event": "inspect.finished",
  "product_type": "bucket-default",
  "status": "ГОДЕН",
  "score": 0.123,
  "duration_ms": 1840
}
```

## 6. Контуры в UI

Контуры нужны оставить как техническое поведение.

### 6.1. Общий формат точки

Все точки хранятся в нормализованном виде:

```json
{ "x": 0.5, "y": 0.5 }
```

Правила:

- `x` и `y` от `0` до `1`;
- `0,0` - левый верхний угол изображения;
- `1,1` - правый нижний угол изображения;
- координаты считаются относительно самого изображения, а не всего блока на экране;
- если изображение показано через `object-contain`, нужно учитывать пустые поля вокруг изображения.

### 6.2. ROI-контур

ROI-контур рисуется поверх эталонного изображения.

Frontend:

- добавляет точки кликом по изображению;
- сохраняет контур только если точек 3 или больше;
- отправляет контур на backend;
- загружает сохраненный контур с backend;
- при смене `product_type` заново загружает эталон и ROI-контур.

### 6.3. Контур изделия

Контур изделия сейчас используется только на frontend.

Он нужен для технических расчетов площади и покрытия ROI. На backend он не отправляется.

### 6.4. FP-зоны

FP-зоны рисуются поверх heatmap после проверки.

Frontend:

- добавляет точки кликом по heatmap;
- сохраняет FP-зону только если точек 3 или больше;
- отправляет FP-зону на backend;
- загружает список FP-зон с backend;
- удаляет FP-зону через backend;
- подсвечивает зоны, ID которых пришли в `rechecked_zone_ids` после проверки.

## 7. Формат изображений

Backend возвращает изображения как base64-строки без prefix.

Frontend должен превращать их в:

```text
data:image/png;base64,{value}
```

Поля изображений:

- `reference_b64` - эталон;
- `original_image_b64` - оригинальное изображение;
- `aligned_image_b64` - выровненное изображение;
- `diff_map_b64` - карта разницы;
- `heatmap_b64` - heatmap;
- `segmentation_mask_b64` - маска сегментации.

## 8. Что frontend отправляет и получает

Базовый адрес: `${apiBaseUrl}`.

### 8.1. Проверка доступности backend

```http
GET /health
```

Ответ:

```json
{
  "status": "ok"
}
```

Нужно для отображения статуса подключения к серверу.

### 8.2. Загрузка эталона из файла

```http
POST /upload-ref
Content-Type: multipart/form-data
```

Frontend отправляет:

- `product_type`: string;
- `file`: image file.

Backend возвращает:

```json
{
  "message": "Reference uploaded",
  "product_type": "bucket-default"
}
```

### 8.3. Загрузка эталона через серверный сценарий камеры

```http
POST /upload-ref-from-camera
Content-Type: multipart/form-data
```

Frontend отправляет:

- `product_type`: string;
- `camera_server_url`: string.

Backend возвращает:

```json
{
  "message": "Reference uploaded from camera",
  "product_type": "bucket-default",
  "camera_source": "http://localhost:8080",
  "camera_duration_ms": 120.5,
  "reference_b64": "..."
}
```

Frontend показывает `reference_b64` как эталон.

### 8.4. Получение эталона

```http
GET /reference/{product_type}
```

Backend возвращает:

```json
{
  "product_type": "bucket-default",
  "reference_b64": "..."
}
```

### 8.5. Проверка изображения из файла

```http
POST /inspect
Content-Type: multipart/form-data
```

Frontend отправляет:

- `product_type`: string;
- `threshold`: number;
- `file`: image file.

Backend возвращает:

```json
{
  "product_type": "bucket-default",
  "status": "ГОДЕН",
  "anomaly_score": 0.123,
  "threshold": 0.25,
  "aligned_image_b64": "...",
  "diff_map_b64": "...",
  "heatmap_b64": "...",
  "segmentation_mask_b64": "...",
  "raw_anomaly_score": 0.14,
  "rechecked_zones_count": 1,
  "recheck_adjustment": 0.017,
  "rechecked_zone_ids": ["uuid"]
}
```

Frontend использует:

- `status` для результата `ГОДЕН` / `БРАК`;
- `anomaly_score` для отображения score;
- `diff_map_b64` для карты разницы;
- `heatmap_b64` для heatmap;
- `raw_anomaly_score`, `rechecked_zones_count`, `recheck_adjustment` для статистики перепроверки;
- `rechecked_zone_ids` для подсветки FP-зон.

### 8.6. Проверка через серверный сценарий камеры

```http
POST /inspect-from-camera
Content-Type: multipart/form-data
```

Frontend отправляет:

- `product_type`: string;
- `threshold`: number;
- `camera_server_url`: string.

Backend возвращает все поля из `/inspect` плюс:

```json
{
  "original_image_b64": "...",
  "camera_source": "http://localhost:8080",
  "camera_duration_ms": 120.5
}
```

Frontend показывает `original_image_b64` как оригинал.

### 8.7. Тестовый прогон

```http
POST /test-run
Content-Type: multipart/form-data
```

Frontend отправляет:

- `product_type`: string;
- `threshold`: number;
- `dataset`: string, `normal` или `brack`.

Backend возвращает:

```json
{
  "product_type": "bucket-default",
  "threshold": 0.25,
  "dataset": "normal",
  "logs": [
    {
      "filename": "image01.png",
      "score": 0.12,
      "status": "ГОДЕН",
      "duration_ms": 95.4
    }
  ],
  "summary": {
    "total": 10,
    "good_count": 9,
    "defect_count": 1,
    "defect_percent": 10.0
  }
}
```

### 8.8. Сохранение ROI-контура

```http
POST /roi-polygon
Content-Type: application/json
```

Frontend отправляет:

```json
{
  "product_type": "bucket-default",
  "points": [
    { "x": 0.1, "y": 0.2 },
    { "x": 0.8, "y": 0.2 },
    { "x": 0.8, "y": 0.7 }
  ]
}
```

Backend возвращает:

```json
{
  "product_type": "bucket-default",
  "points": [
    { "x": 0.1, "y": 0.2 },
    { "x": 0.8, "y": 0.2 },
    { "x": 0.8, "y": 0.7 }
  ]
}
```

### 8.9. Получение ROI-контура

```http
GET /roi-polygon/{product_type}
```

Backend возвращает:

```json
{
  "product_type": "bucket-default",
  "points": [
    { "x": 0.1, "y": 0.2 },
    { "x": 0.8, "y": 0.2 },
    { "x": 0.8, "y": 0.7 }
  ]
}
```

### 8.10. Сохранение FP-зоны

```http
POST /fp-zones
Content-Type: application/json
```

Frontend отправляет:

```json
{
  "product_type": "bucket-default",
  "points": [
    { "x": 0.1, "y": 0.2 },
    { "x": 0.2, "y": 0.2 },
    { "x": 0.2, "y": 0.3 }
  ],
  "heatmap_w": 640,
  "heatmap_h": 480,
  "note": ""
}
```

Backend возвращает:

```json
{
  "id": "uuid",
  "product_type": "bucket-default",
  "points_norm_heatmap": [
    { "x": 0.1, "y": 0.2 }
  ],
  "points_norm_ref": [
    { "x": 0.1, "y": 0.2 }
  ],
  "heatmap_w": 640,
  "heatmap_h": 480,
  "created_at": "2026-04-30T09:00:00.000000+00:00",
  "note": ""
}
```

### 8.11. Получение FP-зон

```http
GET /fp-zones/{product_type}
```

Backend возвращает:

```json
{
  "product_type": "bucket-default",
  "zones": [
    {
      "id": "uuid",
      "product_type": "bucket-default",
      "points_norm_heatmap": [
        { "x": 0.1, "y": 0.2 }
      ],
      "points_norm_ref": [
        { "x": 0.1, "y": 0.2 }
      ],
      "heatmap_w": 640,
      "heatmap_h": 480,
      "created_at": "2026-04-30T09:00:00.000000+00:00",
      "note": ""
    }
  ]
}
```

### 8.12. Удаление FP-зоны

```http
DELETE /fp-zones/{zone_id}
```

Backend возвращает:

```json
{
  "deleted": true,
  "zone_id": "uuid"
}
```

## 9. Ошибки

Frontend должен:

- показывать понятную ошибку, если backend недоступен;
- показывать `detail`, если backend вернул JSON с полем `detail`;
- блокировать повторное нажатие кнопок, пока запрос выполняется;
- не показывать сломанные изображения, если base64-поле пустое;
- писать ошибку в desktop-лог.

Типовые ошибки:

- `400` - неправильные данные, не задан эталон, ошибка обработки;
- `404` - эталон, ROI или FP-зона не найдены;
- `502` - сервер не смог получить изображение через camera-сценарий;
- network error / timeout - backend недоступен.

