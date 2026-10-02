# Simple / Detailed analysis-settings — гайд для инженеров

Два упрощённых эндпоинта поверх полного набора из 18 полей алгоритма.  
Полный справочник полей: [ANALYSIS_SETTINGS.md](ANALYSIS_SETTINGS.md).  
Тексты для UI: [ANALYSIS_SETTINGS_UI.md](ANALYSIS_SETTINGS_UI.md).

Базовый URL (по умолчанию): `http://127.0.0.1:8000`

---

## Зачем

| Часть | Кому | Ручки | Идея |
|-------|------|-------|------|
| **simple** | быстрая калибровка | `threshold` + `sensitivity` (0–1) | единственная «главная» чувствительность |
| **detailed** | тонкая настройка по группам | 5 значений (0–200) | множитель чувствительности каждой группы |

Оба слоя **сохраняются вместе** по `product_type`. Simple задаёт порог и общую чувствительность; detailed задаёт проценты групп.

```text
PUT /.../simple   → threshold + sensitivity
PUT /.../detailed → 5 сил (без threshold/sensitivity)
        │
        ▼
 expand_merged(threshold, sensitivity, strengths…)
        │  stock × coeff(effective_group)
        ▼
 app/data/analysis_settings.json
        │
        ▼
 inspect / inspect-shm
```

---

## Эндпоинты

| Метод | Путь | Что делает |
|-------|------|------------|
| `PUT` | `/analysis-settings/{profile}/simple` | Сохранить порог + чувствительность, пересчитать overrides (с учётом сохранённых сил) |
| `GET` | `/analysis-settings/{profile}/simple` | Последние simple-knobs + эффективные settings |
| `GET` | `/analysis-settings/{profile}/strengths` | **Рекомендуется:** только силы, `saved` + defaults |
| `PUT` | `/analysis-settings/{profile}/strengths` | Сохранить силы групп |
| `GET` | `/analysis-settings/{profile}/detailed` | Силы + полные settings в ответе |
| `PUT` | `/analysis-settings/{profile}/detailed` | Alias для `/strengths` |

`{profile}` = `product_type` / `analysis_profile` (например `bench-lan1`).

### Важно

1. **Simple и detailed не взаимоисключающие** — оба могут быть заданы для одного профиля.
2. PUT simple **не сбрасывает** сохранённые силы detailed.
3. PUT detailed **не меняет** threshold/sensitivity — только силы групп.
4. В JSON persist: `simple_knobs` + `detailed_knobs`. Старый `pro_knobs` (0–1) мигрируется: `value × 100`.

---

## Модель «сток × коэффициент»

Сток = `AnalysisSettings.defaults()`.  
**Сток = effective группы 50%**. При стандартных detailed 100% он достигается с `sensitivity = 0.5`.

### Simple

Одна чувствительность масштабирует все группы одинаково (значения групп = 100% по умолчанию).

### Detailed — силы групп

Для каждой группы общая чувствительность умножается на сохранённое значение:

```text
effective_group_percent = sensitivity × strength  # API: sensitivity 0–1, strength 0–200
result_field           = stock × coeff(effective_group_percent)
```

| strength | поведение группы |
|----------|------------------|
| `0` | итоговая чувствительность группы 0% |
| `50` | половина общей чувствительности |
| `100` | вся общая чувствительность |
| `200` | вдвое больше общей чувствительности; итог до 200% |

Например, при общей чувствительности 80% и значении группы 90% итог группы равен 72%; при значении группы 200% — 160%.
Для участка 100–200% параметры алгоритма продолжают изменяться до отдельных допустимых пределов. Такой режим повышает риск ложного брака.

---

## Simple

```http
PUT /analysis-settings/{profile}/simple
Content-Type: application/json

{ "threshold": 0.25, "sensitivity": 0.5 }
```

| Поле | Диапазон | Смысл |
|------|----------|--------|
| `threshold` | `(0, 1]` | Порог ГОДЕН/БРАК |
| `sensitivity` | `[0, 1]` | Общая чувствительность; `0.5` = сток |

| значение | поведение |
|----------|-----------|
| `0` | грубо (COARSE-край) |
| `0.5` | сток |
| `1` | 100% для групп со значением 100; с усилением группы возможен итог до 200% |

---

## Detailed (только силы)

```http
PUT /analysis-settings/{profile}/detailed
Content-Type: application/json

{
  "noise_tolerance": 100,
  "scratch_sensitivity": 100,
  "edge_suppression": 100,
  "text_handling": 100,
  "preprocess_strength": 100
}
```

Все ручки ∈ `[0, 200]`. Значения выше 100 усиливают отдельную группу относительно общей чувствительности. **Нет** `threshold` и `sensitivity` — они только в simple.

### Маппинг сил → поля

| Ручка (сила) | Поля группы |
|--------------|-------------|
| `noise_tolerance` | `min_diff_signal`, `min_defect_area`, `diff_percentile` |
| `scratch_sensitivity` | `min_scratch_aspect`, `scratch_score_floor`, `scratch_aspect_floor` |
| `edge_suppression` | `edge_suppress_factor` |
| `text_handling` | `text_min_contrast`, `text_structure_threshold`, `contrast_loss_*` |
| `preprocess_strength` | `enable_clahe`, `clahe_clip_limit` |

---

## Опорные значения (simple, threshold=0.25, группы=100)

| поле | s=0 | s=0.5 | s=1 |
|------|-----|-------|-----|
| `min_diff_signal` | 40 | 12 | 4 |
| `min_defect_area` | 50 | 6 | 3 |
| `diff_percentile` | 99.5 | 98 | 95 |
| `min_scratch_aspect` | 5 | 3 | 2 |
| `edge_suppress_factor` | 0.05 | 0.2 | 0.5 |
| `text_min_contrast` | 90 | 55 | 30 |
| `clahe_clip_limit` | 1.0 | 1.2 | 2.0 |

Полная матрица — в тестах:

```bash
cd backend
python3 -m pytest tests/test_analysis_settings_preset_matrix.py -s -k table
```

---

## Связанный код

| Что | Где |
|-----|-----|
| HTTP | `backend/app/api/analysis_settings_preset_routes.py` |
| expand | `backend/app/services/analysis_settings_presets.py` |
| persist | `InspectionService.apply_simple_settings` / `apply_detailed_settings` |
| лаборатория | `analisSurface/test-analysis.html` |
