# Simple / Pro analysis-settings — гайд для инженеров

Два упрощённых эндпоинта поверх полного набора из 18 полей алгоритма.  
Полный справочник полей: [ANALYSIS_SETTINGS.md](ANALYSIS_SETTINGS.md).  
Тексты для UI: [ANALYSIS_SETTINGS_UI.md](ANALYSIS_SETTINGS_UI.md).

Базовый URL (по умолчанию): `http://127.0.0.1:8000`

---

## Зачем

| Режим | Кому | Сколько ручек | Идея |
|-------|------|---------------|------|
| **simple** | быстрая калибровка / «для тупых» | 2 | `threshold` + общий `sensitivity` |
| **pro** | тонкая настройка без лезания в 18 полей | 6 | `threshold` + 5 независимых групп |

Оба режима **не** обходят пайплайн инспекции: они разворачивают knobs → полный `AnalysisSettings` и пишут через тот же `InspectionService`, что и `PUT /analysis-settings/{profile}`.

```text
PUT /.../simple|pro
        │
        ▼
 analysis_settings_presets.expand_*()
        │  полный dict из 18 полей
        ▼
 InspectionService.apply_*_settings()
        │
        ▼
 app/data/analysis_settings.json
        │
        ▼
 inspect / inspect-shm  (читают эффективные settings)
```

---

## Эндпоинты

| Метод | Путь | Что делает |
|-------|------|------------|
| `PUT` | `/analysis-settings/{profile}/simple` | Развернуть knobs, **полностью заменить** overrides профиля |
| `GET` | `/analysis-settings/{profile}/simple` | Вернуть последние simple-knobs + эффективные settings |
| `PUT` | `/analysis-settings/{profile}/pro` | То же для pro |
| `GET` | `/analysis-settings/{profile}/pro` | То же для pro |

`{profile}` = `product_type` / `analysis_profile` (например `bench-lan1`).

### Важно для инженеров

1. **PUT simple/pro перезаписывает все algorithm-поля**, не мержит «одну ручку» с прошлым полным API.
2. После PUT simple knobs pro сбрасываются (и наоборот).
3. Если потом сделать обычный `PUT /analysis-settings/{profile}` или `DELETE` — abstract-knobs станут `null` (иначе GET врал бы).
4. Инспекция по-прежнему читает только плоские settings; knobs — только для UI/истории режима.

---

## Simple

### Запрос

```http
PUT /analysis-settings/{profile}/simple
Content-Type: application/json

{
  "threshold": 0.25,
  "sensitivity": 0.5
}
```

| Поле | Диапазон | Смысл |
|------|----------|--------|
| `threshold` | `(0, 1]` | Порог ГОДЕН/БРАК → `default_threshold` |
| `sensitivity` | `[0, 1]` | Пресет **всех остальных** полей |

Шкала `sensitivity`:

| значение | поведение |
|----------|-----------|
| `0` | грубо: меньше ложных браков, сильнее отсев шума |
| `0.5` | заводские defaults |
| `1` | максимально чутко |
| любое `x` ∈ `[0,1]` | линейная интерполяция между якорями (см. ниже) |

### Пример

```bash
curl -s -X PUT "http://127.0.0.1:8000/analysis-settings/bench-lan1/simple" \
  -H "Content-Type: application/json" \
  -d '{"threshold": 0.25, "sensitivity": 0.2}'
```

### Ответ (фрагмент)

```json
{
  "analysis_profile": "bench-lan1",
  "knobs": { "threshold": 0.25, "sensitivity": 0.2 },
  "settings": { "default_threshold": 0.25, "min_diff_signal": 28.8, "...": "..." },
  "defaults": { "...": "заводские" },
  "overrides": { "...": "что реально записано" },
  "detector_id": "..."
}
```

`settings` — то, что увидит инспекция. Смотри сюда, если нужно понять «что реально выставилось».

---

## Pro

### Запрос

```http
PUT /analysis-settings/{profile}/pro
Content-Type: application/json

{
  "threshold": 0.25,
  "noise_tolerance": 0.5,
  "scratch_sensitivity": 0.5,
  "edge_suppression": 0.5,
  "text_handling": 0.5,
  "preprocess_strength": 0.5
}
```

Все ручки кроме `threshold` ∈ `[0, 1]`.  
Единая шкала группы: **`0` = грубее для этой группы, `0.5` = defaults, `1` = чувствительнее**.

### Маппинг групп → поля

| Ручка | Внутренние поля |
|-------|-----------------|
| `noise_tolerance` | `min_diff_signal`, `min_defect_area`, `diff_percentile` |
| `scratch_sensitivity` | `min_scratch_aspect`, `scratch_score_floor`, `scratch_aspect_floor` |
| `edge_suppression` | `edge_suppress_factor` |
| `text_handling` | `text_min_contrast`, `text_structure_threshold`, `contrast_loss_boost`, `contrast_loss_ref_grad`, `contrast_loss_cur_grad` |
| `preprocess_strength` | `enable_clahe`, `clahe_clip_limit` |

Без отдельных ручек (всегда defaults):

- `fp_recheck_enabled = true`
- `fp_trigger_diff_q90 = 22.0`

Ручки **независимы**: можно поднять только `scratch_sensitivity`, оставив noise на `0.5`.

### Пример

```bash
curl -s -X PUT "http://127.0.0.1:8000/analysis-settings/bench-lan1/pro" \
  -H "Content-Type: application/json" \
  -d '{
    "threshold": 0.25,
    "noise_tolerance": 0.2,
    "scratch_sensitivity": 0.7,
    "edge_suppression": 0.5,
    "text_handling": 0.5,
    "preprocess_strength": 0.5
  }'
```

---

## Как считается интерполяция

Якоря в коде: `backend/app/services/analysis_settings_presets.py`  
(`_COARSE` / defaults / `_SENSITIVE`).

Для ручки `t ∈ [0, 1]`:

```text
если t ≤ 0.5:
    local = t / 0.5
    value = lerp(COARSE, DEFAULT, local)
иначе:
    local = (t - 0.5) / 0.5
    value = lerp(DEFAULT, SENSITIVE, local)
```

- числа — обычный lerp; `int` округляется;
- `bool` (`enable_clahe`): переключается на значение правого якоря при `local ≥ 0.5`  
  → для simple `enable_clahe=True` уже с `sensitivity ≥ 0.25`.

Пример: `sensitivity = 0.2` → `local = 0.4` на отрезке COARSE→DEFAULT  
→ `min_diff_signal ≈ 28.8`, `min_defect_area = 32`, `enable_clahe = false`.

---

## Опорные значения (simple, threshold=0.25)

| поле | s=0 | s=0.5 | s=1 |
|------|-----|-------|-----|
| `min_diff_signal` | 40 | 12 | 4 |
| `min_defect_area` | 50 | 6 | 3 |
| `diff_percentile` | 99.5 | 98 | 95 |
| `min_scratch_aspect` | 5 | 3 | 2 |
| `edge_suppress_factor` | 0.05 | 0.2 | 0.5 |
| `text_min_contrast` | 90 | 55 | 30 |
| `enable_clahe` | false | true | true |
| `clahe_clip_limit` | 1.0 | 1.2 | 2.0 |

Полная матрица (в т.ч. `0.25` / `0.75`) — в тестах:

```bash
cd backend
python3 -m pytest tests/test_analysis_settings_preset_matrix.py -s -k table
```

---

## Ошибки

| Ситуация | Код |
|----------|-----|
| `threshold` вне `(0, 1]` или ручка вне `[0, 1]` | `422` (Pydantic) |
| внутренняя валидация expand | `400` |

---

## Связанный код

| Что | Где |
|-----|-----|
| HTTP | `backend/app/api/analysis_settings_preset_routes.py` |
| expand simple/pro | `backend/app/services/analysis_settings_presets.py` |
| persist + knobs | `InspectionService.apply_simple_settings` / `apply_pro_settings` |
| полный API (18 полей) | `PUT /analysis-settings/{profile}` |
| матрица значений | `backend/tests/test_analysis_settings_preset_matrix.py` |
