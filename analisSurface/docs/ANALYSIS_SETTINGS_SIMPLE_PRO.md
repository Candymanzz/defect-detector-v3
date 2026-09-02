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
| **detailed** | тонкая настройка по группам | 5 сил (0–100) | **насколько сильно** каждая группа следует за `sensitivity` |

Оба слоя **сохраняются вместе** по `product_type`. Simple задаёт порог и чувствительность; detailed задаёт только коэффициенты отклика групп.

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
**Центр = без изменений** (коэффициент 1.0): `sensitivity = 0.5` (или effective группы = 50).

### Simple

Одна чувствительность двигает все поля одинаково (силы = 50 по умолчанию).

### Detailed — силы групп

Сила **не заменяет** чувствительность. Она задаёт, **насколько группа реагирует** на главную чувствительность:

```text
effective_group = 50 + (sensitivity×100 − 50) × (strength / 50)
result_field    = stock × coeff(effective_group)
```

| strength | поведение группы |
|----------|------------------|
| `0` | всегда на стоке, чувствительность не влияет |
| `50` | стандартный отклик (как в simple) |
| `100` | удвоенный отклик на то же движение sensitivity |

При `sensitivity = 0.5` все группы на стоке **независимо от сил**.

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
| `1` | максимально чутко (SENSITIVE-край) |

---

## Detailed (только силы)

```http
PUT /analysis-settings/{profile}/detailed
Content-Type: application/json

{
  "noise_tolerance": 50,
  "scratch_sensitivity": 50,
  "edge_suppression": 50,
  "text_handling": 50,
  "preprocess_strength": 50
}
```

Все ручки ∈ `[0, 100]`. **Нет** `threshold` и `sensitivity` — они только в simple.

### Маппинг сил → поля

| Ручка (сила) | Поля группы |
|--------------|-------------|
| `noise_tolerance` | `min_diff_signal`, `min_defect_area`, `diff_percentile` |
| `scratch_sensitivity` | `min_scratch_aspect`, `scratch_score_floor`, `scratch_aspect_floor` |
| `edge_suppression` | `edge_suppress_factor` |
| `text_handling` | `text_min_contrast`, `text_structure_threshold`, `contrast_loss_*` |
| `preprocess_strength` | `enable_clahe`, `clahe_clip_limit` |

---

## Опорные значения (simple, threshold=0.25, силы=50)

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
