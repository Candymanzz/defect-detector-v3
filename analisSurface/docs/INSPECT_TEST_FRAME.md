# POST `/inspect-test-frame` — гайд для Java и frontend

Эндпоинт для **проверки настроек на выбранном кадре** без записи `analysis_settings` на диск и без HTTP-fetch картинки из Python.

Базовый URL (по умолчанию): `http://127.0.0.1:8000`  
Реализация: `analisSurface/backend/app/api/inspection_routes.py`  
Схема: `TestFrameInspectRequest` / `ShmVisualsResponse` в `schemas.py`

Связанные документы:

- ручки simple/pro: [ANALYSIS_SETTINGS_SIMPLE_PRO.md](ANALYSIS_SETTINGS_SIMPLE_PRO.md)
- полный пайплайн инспекции: [GUIDE.md](GUIDE.md)

---

## Зачем

| Было (плохо для «Проверить») | Стало |
|------------------------------|--------|
| PUT настроек на диск → inspect читает YAML/JSON | knobs только в теле запроса |
| SHM / повторный decode одного и того же JPEG | кэш BGR по `cache_key` + путь |
| Python тянет произвольный URL | **только локальный** `file_path` (без SSRF) |
| отдельный `/inspect-shm` + `/inspect-shm-visuals` | один вызов: score + опциональный heatmap |

**Не путать** с `PUT /analysis-settings/.../simple|pro` — тот **сохраняет** knobs.  
`/inspect-test-frame` knobs **не сохраняет**.

---

## Контракт

### Request

`POST /inspect-test-frame`  
`Content-Type: application/json`

| Поле | Тип | Обязательно | Описание |
|------|-----|-------------|----------|
| `cache_key` | string | да | Идентичность кадра, обычно `"{cameraId}:{frameId}"` |
| `file_path` | string | да | **Абсолютный** путь к JPEG на диске (архив / pin), readable для процесса Python |
| `image_url` | string \| null | нет | Логический URL UI (`/api/frame-archive/.../frame.jpg`). Участвует в ключе кэша, **не скачивается** |
| `product_type` | string | да | Ключ эталона/ROI в Python (часто scoped: `product#cam=N`) |
| `analysis_profile` | string \| null | нет | Профиль для подписей; knobs всё равно из тела |
| `detector_id` | string \| null | нет | Пробрасывается в ответ |
| `alignment_h_ref_to_cur` | `number[]` или `number[][]` | нет | 3×3 гомография ref→cur от java-geometry (9 float или 3×3) |
| `simple` | object | один из двух | Быстрые ручки (см. ниже) |
| `pro` | object | один из двух | Расширенные ручки |
| `heatmap_u8_output_path` | string \| null | нет | Куда писать gray heatmap u8 (SHM/файл). Если задан — пишется |
| `heatmap_max_width` | int \| null | нет | Ужать heatmap по ширине перед записью |
| `aligned_image_u8_output_path` | string \| null | нет | Опциональный визуал |
| `diff_map_u8_output_path` | string \| null | нет | Опциональный визуал |
| `segmentation_mask_u8_output_path` | string \| null | нет | Опциональный визуал |

**Правила knobs**

- нужен **ровно один** блок: либо `simple`, либо `pro`;
- оба сразу → `400`;
- ни одного → `400`.

#### `simple`

```json
{ "threshold": 0.25, "sensitivity": 0.5 }
```

| Поле | Диапазон |
|------|----------|
| `threshold` | `(0, 1]` |
| `sensitivity` | `[0, 1]` |

#### `pro`

```json
{
  "threshold": 0.25,
  "noise_tolerance": 0.5,
  "scratch_sensitivity": 0.5,
  "edge_suppression": 0.5,
  "text_handling": 0.5,
  "preprocess_strength": 0.5
}
```

Все поля ∈ `[0, 1]`, кроме `threshold` ∈ `(0, 1]`.

### Response

Тот же формат, что у `/inspect-shm-visuals` (`ShmVisualsResponse`):

- вердикт: `status`, `anomaly_score`, `threshold`, `product_type`, `detector_id`, зональные score, …
- визуалы (если пути были в запросе и запись удалась):

```json
"heatmap_u8": {
  "path": "...",
  "width": 512,
  "height": 384,
  "stride": 512,
  "channels": 1,
  "dtype": "uint8"
}
```

Аналогично `aligned_image_u8`, `diff_map_u8`, `segmentation_mask_u8`.

Ошибка записи визуала **не** отменяет вердикт: поля визуалов просто пустые, в лог warning.

### Ошибки

| Код | Когда |
|-----|--------|
| `400` | нет/оба knobs; файл не найден; не декодируется JPEG; нет эталона для `product_type`; прочие `ValueError`/`OSError` инспекции |
| `422` | валидация Pydantic (типы/диапазоны) |

`learning review` **не** пишется (`store_learning_review=False`).

---

## Кэш BGR (важно для Java)

Ключ кэша:

```text
(cache_key.strip(), resolve(file_path), (image_url or "").strip())
```

- повтор с тем же ключом → файл **не** читается снова;
- смена `cache_key` / пути / `image_url` → новый decode;
- лимит слотов: 4 (при переполнении кэш очищается).

Рекомендация оркестратора: стабильный `cache_key = cameraId + ":" + frameId` и тот же `file_path`/`image_url`, пока оператор не сменил кадр.

---

## Пример запроса

```http
POST /inspect-test-frame HTTP/1.1
Content-Type: application/json

{
  "cache_key": "0:42",
  "file_path": "D:/iml_data/frame-archive/camera_0/42/frame.jpg",
  "image_url": "/api/frame-archive/cameras/0/frames/42/frame.jpg",
  "product_type": "bench#cam=0",
  "analysis_profile": "bench-lan1",
  "detector_id": "v1",
  "alignment_h_ref_to_cur": [1, 0, 0, 0, 1, 0, 0, 0, 1],
  "simple": { "threshold": 0.25, "sensitivity": 0.5 },
  "heatmap_u8_output_path": "D:/iml_shm/iml_ui_heatmap_cam_0_test",
  "heatmap_max_width": 512
}
```

---

## Для Java (оркестратор)

### Роль

1. UI шлёт test-analyze с выбранным кадром + knobs.
2. Java резолвит `http_path` архива → **локальный** `frame.jpg` (тот же хост, что SHM).
3. Geometry (при необходимости) → `alignment_h_ref_to_cur`.
4. `POST /inspect-test-frame` вместо пары `inspect-shm` + `inspect-shm-visuals` для теста.
5. В WS/UI отдавать **исходный** archive `http_path`, не перекодированный JPEG.
6. Архивный `frame.jpg` **не** перезаписывать (`saveImmediately` для test-analyze не вызывать).

### Что класть в body

| Поле | Откуда |
|------|--------|
| `cache_key` | `cameraId + ":" + frameId` |
| `file_path` | `FrameArchiveService.resolveArtifact(cam, frame, "frame.jpg")` (absolute) |
| `image_url` | `frameArtifactHttpPath(...)` — тот же путь, что видит UI |
| `product_type` | scoped product (как в `/inspect-shm`) |
| `analysis_profile` | YAML профиль камеры |
| `alignment_h_ref_to_cur` | из ответа geometry (`homographyRefToCurrent`) |
| `simple` / `pro` | knobs с UI **как есть** (не писать в runtime/disk) |
| `heatmap_u8_output_path` | путь в `iml_shm` (как для UI heatmap) |

### Чего не делать

- не HTTP-fetch URL из Python;
- не вызывать `PUT` analysis-settings перед «Проверить»;
- не гонять второй `/inspect-shm-visuals`, если heatmap уже в ответе;
- не подменять `http_path` кадра артефактом sidecar.

### Псевдокод

```java
Path jpeg = frameArchive.resolveArtifact(cameraId, frameId, "frame.jpg").orElseThrow();
Map<String, Object> body = new LinkedHashMap<>();
body.put("cache_key", cameraId + ":" + frameId);
body.put("file_path", jpeg.toAbsolutePath().toString());
body.put("image_url", frameArchive.frameArtifactHttpPath(cameraId, frameId, "frame.jpg"));
body.put("product_type", scopedProductType);
body.put("analysis_profile", analysisProfile);
body.put("alignment_h_ref_to_cur", homographyFromGeometry); // optional
body.put("simple", simpleKnobs); // XOR pro
body.put("heatmap_u8_output_path", heatmapShmPath.toString());
body.put("heatmap_max_width", 512);
// POST /inspect-test-frame → score + heatmap_u8.path/width/height
```

Опционально на стороне Java: кэш SHM/geometry по `(cameraId, frameId)` + geometry-knobs, чтобы не гонять geometry зря при кручении только analysis-sliders.

---

## Для frontend

### Роль UI

| Действие | Поведение |
|----------|-----------|
| **Проверить** / debounce слайдеров | только inspect: knobs + кадр, **без** PUT настроек |
| **Сохранить** | PUT simple/pro (и geometry runtime), как сейчас |
| Выбор кадра в истории | `frameId` / `httpPath` синхронизировать с выбраннымinspect |

### Что отправлять на оркестратор (типичный test-analyze)

Оркестратор потом соберёт Python-body. С фронта достаточно:

```ts
{
  cameraId: number;
  frameId: string | number;
  httpPath: `/api/frame-archive/cameras/${cameraId}/frames/${frameId}/frame.jpg`;
  source: "archive"; // или pin, если ещё используете pin
  // analysis knobs с панелей:
  simple?: { threshold: number; sensitivity: number };
  // XOR
  pro?: {
    threshold: number;
    noise_tolerance: number;
    scratch_sensitivity: number;
    edge_suppression: number;
    text_handling: number;
    preprocess_strength: number;
  };
  // geometry — только в header geometry-RPC, не в Python:
  geometry?: {
    max_shift_mm: number;
    joint_seam_segmentation_sensitivity: number;
  };
}
```

Имена полей knobs — **snake_case**, как в Python API (`noise_tolerance`, не `noiseTolerance`).

### Картинка в модалке

- показывать archive / pin URL выбранного кадра;
- после test-analyze **не** подменять кадр на `current.jpg` или re-encoded artifact;
- heatmap брать из ответа (через WS/оркестратор), второй полный inspect с фронта не нужен.

### Разделение Save / Check

```text
Слайдер изменился → debounce → test-analyze (knobs в body)
«Проверить»       → test-analyze (knobs в body)
«Сохранить»       → PUT analysis-settings + (опционально) geometry patch
```

---

## Отличия от `/inspect-shm` и `/inspect-shm-visuals`

| | `/inspect-shm*` | `/inspect-test-frame` |
|--|-----------------|------------------------|
| Источник кадра | BGR в SHM | JPEG с диска + кэш |
| Settings | диск / `analysis_test_settings` merge | только `simple`/`pro` в теле |
| Пишет analysis_settings | нет (temporary) / да (если до этого PUT) | **никогда** |
| Learning review | зависит от флагов | всегда off |
| Heatmap | отдельный visuals-путь | в том же запросе |

Для **лайна** по-прежнему `/inspect-shm`.  
Для **операторского «Проверить»** — `/inspect-test-frame`.

---

## Чеклист интеграции

**Java**

- [ ] Резолв archive → absolute `file_path`
- [ ] Стабильный `cache_key`
- [ ] Knobs ephemeral, без PUT
- [ ] Homography в `alignment_h_ref_to_cur`
- [ ] Heatmap path в запросе; второй visuals не звать
- [ ] WS: исходный archive `http_path`; архив не overwrite

**Frontend**

- [ ] `testFrameId` / `httpPath` = выбранный кадр
- [ ] Check/sliders → inspect only
- [ ] Save → persist
- [ ] Knobs snake_case, simple XOR pro
- [ ] Кадр в UI не прыгает на live/current
