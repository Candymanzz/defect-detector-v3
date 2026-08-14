# Настройки алгоритма инспекции (Analysis Settings)

Параметры хранятся **отдельно для каждого `product_type`**, применяются при каждой инспекции (`/inspect`, `/inspect-shm`, `/inspect-shm-visuals`), если в запросе не передан свой `threshold`.

Базовый URL API (по умолчанию): `http://127.0.0.1:8000`

---

## Эндпоинты

| Метод | Путь | Описание |
|--------|------|----------|
| `GET` | `/analysis-settings/defaults` | Значения по умолчанию (без overrides) |
| `GET` | `/analysis-settings/{product_type}` | Эффективные настройки + список переопределённых полей |
| `PUT` | `/analysis-settings/{product_type}` | Частичное обновление (можно передать только нужные поля) |
| `DELETE` | `/analysis-settings/{product_type}` | Сброс overrides для продукта |
| `GET` | `/analysis-settings/{product_type}/simple` | Последние simple-knobs + эффективные settings |
| `PUT` | `/analysis-settings/{product_type}/simple` | Быстрая настройка: `threshold` + `sensitivity` |
| `GET` | `/analysis-settings/{product_type}/pro` | Последние pro-knobs + эффективные settings |
| `PUT` | `/analysis-settings/{product_type}/pro` | Pro-настройка: `threshold` + 5 абстрактных ручек |

После `PUT` / `DELETE` настройки сохраняются в файл:

`backend/app/data/analysis_settings.json`

Документация для инженеров по `/simple` и `/pro`: **[ANALYSIS_SETTINGS_SIMPLE_PRO.md](ANALYSIS_SETTINGS_SIMPLE_PRO.md)**.  
Подписи и пояснения для UI: **[ANALYSIS_SETTINGS_UI.md](ANALYSIS_SETTINGS_UI.md)**.

---

## Формат ответа

```json
{
  "product_type": "your-product",
  "settings": { "...": "эффективные значения (defaults + overrides)" },
  "defaults": { "...": "заводские значения" },
  "overrides": { "...": "только изменённые поля" },
  "detector_id": "..."
}
```

Поле `detector_id` добавляется middleware приложения ко всем JSON-ответам.

---

## Пример: полный запрос со всеми параметрами

`PUT` принимает **любое подмножество** полей. Ниже — тело со **всеми** доступными ключами и дефолтными значениями:

```bash
curl -s -X PUT "http://127.0.0.1:8000/analysis-settings/your-product" \
  -H "Content-Type: application/json" \
  -d '{
    "default_threshold": 0.25,
    "min_defect_area": 6,
    "min_scratch_aspect": 3.0,
    "min_diff_signal": 12.0,
    "diff_percentile": 98.0,
    "scratch_score_floor": 0.35,
    "scratch_aspect_floor": 4.5,
    "edge_suppress_factor": 0.2,
    "text_min_contrast": 55,
    "text_structure_threshold": 30,
    "contrast_loss_boost": 2.0,
    "contrast_loss_ref_grad": 40.0,
    "contrast_loss_cur_grad": 15.0,
    "enable_clahe": true,
    "clahe_clip_limit": 1.2,
    "fp_recheck_enabled": true,
    "fp_trigger_diff_q90": 22.0
  }'
```

### Другие примеры

Получить дефолты:

```bash
curl -s "http://127.0.0.1:8000/analysis-settings/defaults"
```

Получить настройки продукта:

```bash
curl -s "http://127.0.0.1:8000/analysis-settings/your-product"
```

Сбросить overrides:

```bash
curl -s -X DELETE "http://127.0.0.1:8000/analysis-settings/your-product"
```

Частичное обновление (остальные поля не меняются):

```bash
curl -s -X PUT "http://127.0.0.1:8000/analysis-settings/your-product" \
  -H "Content-Type: application/json" \
  -d '{"default_threshold": 0.3, "fp_recheck_enabled": false}'
```

---

## Справочник параметров

### Порог и итоговый статус

| Поле | Тип | Диапазон | По умолчанию | Назначение |
|------|-----|----------|--------------|------------|
| `default_threshold` | float | `(0, 1]` | `0.25` | Порог **ГОДЕН / БРАК** для основной ROI и подзон (если у подзоны нет своего `threshold`). Сравнивается с `anomaly_score` (0…1). **Выше** → меньше ложных браков, **ниже** → чувствительнее. |

Переопределение на один кадр: поле `threshold` в `POST /inspect-shm` или form-field в `POST /inspect`.

---

### Детекция дефектов (diff map и маска)

| Поле | Тип | Диапазон | По умолчанию | Назначение |
|------|-----|----------|--------------|------------|
| `min_diff_signal` | float | `≥ 0` | `12.0` | Минимальный пик яркости на diff map. Если максимум слабее — считается «нет сигнала», score `0`. **Выше** → игнор слабых отличий. |
| `diff_percentile` | float | `[50, 100]` | `98.0` | Перцентиль для бинаризации diff map (ограничен кодом диапазоном 10…35 в абсолютных уровнях gray). **Выше** → меньше пикселей попадает в маску. |
| `min_defect_area` | int | `≥ 1` | `6` | Минимальная площадь связной компоненты (пиксели), чтобы попасть в маску дефекта. **Выше** → отсекается мелкий шум. |
| `min_scratch_aspect` | float | `≥ 1` | `3.0` | Соотношение сторон компоненты: тонкие царапины с площадью `> 3` сохраняются при aspect **больше** этого порога. **Выше** → только более вытянутые линии. |
| `scratch_aspect_floor` | float | `≥ 1` | `4.5` | Если max aspect маски **выше** порога — эвристический score не ниже `scratch_score_floor`. |
| `scratch_score_floor` | float | `[0, 1]` | `0.35` | Нижняя граница score при длинных тонких дефектах. **Выше** → царапины чаще дают БРАК. |
---

### Предобработка diff map (`_compute_advanced_difference`)

| Поле | Тип | Диапазон | По умолчанию | Назначение |
|------|-----|----------|--------------|------------|
| `enable_clahe` | bool | — | `true` | Локальное выравнивание контраста (CLAHE) для ref/current gray, если std кадра > 5. **false** → меньше усиления текстуры на гладких поверхностях. |
| `clahe_clip_limit` | float | `> 0` | `1.2` | Лимит контраста CLAHE. **Выше** → сильнее подчёркиваются локальные различия (риск шума). |
| `edge_suppress_factor` | float | `[0, 1]` | `0.2` | Множитель diff у статических границ эталона (Canny по ref). **Ниже** → сильнее глушится реакция на кромки/рамку. |
| `text_structure_threshold` | int | `[0, 255]` | `30` | Порог Sobel на эталоне: зона «похожа на текст/структуру». | 
| `text_min_contrast` | int | `[0, 255]` | `55` | В текстовых зонах оставлять только пиксели diff **не ниже** порога; слабый отклик обнуляется. **Выше** → меньше ложных срабатываний на тексте. |
| `contrast_loss_ref_grad` | float | `≥ 0` | `40.0` | Порог градиента на **эталоне** для зоны «пропал контраст/текст». |
| `contrast_loss_cur_grad` | float | `≥ 0` | `15.0` | Порог градиента на **текущем** кадре в той же зоне (ниже ref → подозрение на стирание/выцветание). |
| `contrast_loss_boost` | float | `≥ 1` | `2.0` | Усиление diff в зонах contrast loss. **Выше** → сильнее ловятся стёртые надписи. |

---

### FP-зоны (false positive / подавление ложных срабатываний)

| Поле | Тип | Диапазон | По умолчанию | Назначение |
|------|-----|----------|--------------|------------|
| `fp_recheck_enabled` | bool | — | `true` | Пересчёт score с подавлением активированных FP-зон. **false** → зоны FP не влияют на маску и score. |
| `fp_trigger_diff_q90` | float | `≥ 0` | `22.0` | 90-й перцентиль diff внутри FP-зоны: при превышении зона считается «активной» (вместе с пересечением маски). **Выше** → реже срабатывает пересчёт. |

FP-зоны задаются отдельно: `POST /fp-zones`, не через analysis-settings.

---

## Ошибки валидации

При недопустимых значениях `PUT` вернёт `400` с текстом, например:

- `default_threshold must be in (0, 1]`
- `diff_percentile must be in [50, 100]`
- `At least one setting must be provided` — если тело `{}` или все поля `null`

---

## Связь с `algorithm_params` в других запросах

В схемах `ShmFrameRequest` / `RoiPolygonRequest` есть поле `algorithm_params`, но **текущий backend analisSurface его не читает**. Настройки алгоритма для инспекции задаются только через **`/analysis-settings/{product_type}`** (или дефолты).

Orchestrator может прокидывать плоские ключи в HTTP-тело; чтобы они работали у вас, их нужно либо маппить в `update_analysis_settings`, либо обрабатывать в `inspect_frame` — сейчас этого нет.

---

## Swagger

Интерактивная схема: `http://127.0.0.1:8000/docs` → раздел **analysis-settings**.
