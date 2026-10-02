# Simple / Detailed analysis-settings — гайд для инженеров

Два упрощённых эндпоинта поверх полного набора полей алгоритма.
Полный справочник полей: [ANALYSIS_SETTINGS.md](ANALYSIS_SETTINGS.md).  
Тексты для UI: [ANALYSIS_SETTINGS_UI.md](ANALYSIS_SETTINGS_UI.md).

Базовый URL (по умолчанию): `http://127.0.0.1:8000`

---

## Зачем

| Часть | Кому | Ручки | Идея |
|-------|------|-------|------|
| **simple** | быстрая калибровка | `threshold` + `sensitivity` (0–1) | единственная «главная» чувствительность |
| **detailed** | тонкая настройка по группам | 5 значений (0–100) | 75 — прежняя нейтральная настройка |

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
4. В JSON persist: `simple_knobs` + `detailed_knobs`. Старые значения групп 0–200 мигрируются по формуле `min(100, 25 + old / 2)`; формат отмечается `strength_scale: "centered_minus50_150_v2"`.

---

## Модель «сток × коэффициент»

Сток = `AnalysisSettings.defaults()`.  
**Сток = effective группы 50%**. При стандартных detailed 75% он достигается с `sensitivity = 0.5`.

### Simple

Одна чувствительность масштабирует все группы одинаково (значения групп = 75% по умолчанию).

### Detailed — силы групп

Для каждой группы общая чувствительность умножается на сохранённое значение:

```text
effective_group_percent = sensitivity × (2 × strength − 50)
result_field           = stock × coeff(effective_group_percent)
```

| strength | поведение группы |
|----------|------------------|
| `0` | при общей 100% внутренняя позиция −50 |
| `25` | прежний нулевой множитель; позиция 0 |
| `50` | при общей 100% внутренняя позиция 50 |
| `75` | прежний обычный множитель; позиция 100 |
| `100` | усиление до 150 при общей 100% |

Внутренняя шкала −50…150 — условные координаты алгоритма, а не вероятность дефекта. При общей 0% отбраковка отключена и результат всегда «ГОДЕН». Высокие значения повышают риск ложного брака.

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
| `0` | отбраковка отключена, всегда «ГОДЕН» |
| `0.5` | сток |
| `1` | 100% при прежних обычных значениях групп 75%; расширенные ручки могут усилить или ослабить группы |

---

## Detailed (только силы)

```http
PUT /analysis-settings/{profile}/detailed
Content-Type: application/json

{
  "noise_tolerance": 75,
  "scratch_sensitivity": 75,
  "edge_suppression": 75,
  "text_handling": 75,
  "preprocess_strength": 75
}
```

Все ручки ∈ `[0, 100]`. Ниже 75 ослабляют отдельную группу относительно прежней обычной настройки, выше 75 усиливают. **Нет** `threshold` и `sensitivity` — они только в simple.

### Маппинг сил → поля

| Ручка (сила) | Поля группы |
|--------------|-------------|
| `noise_tolerance` | `min_diff_signal`, `min_defect_area`, `diff_percentile` |
| `scratch_sensitivity` | `min_scratch_aspect`, `scratch_score_floor`, `scratch_aspect_floor` |
| `edge_suppression` | `edge_suppress_factor` |
| `text_handling` | `text_min_contrast`, `text_structure_threshold`, `contrast_loss_*` |
| `preprocess_strength` | `enable_clahe`, `clahe_clip_limit` |

---

## Опорные значения (simple, threshold=0.25, группы=75)

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

При включённом файловом логировании изменения и применение настроек пишутся в
`backend/logs/<дата_время>/analysis_settings.log` — по одной JSON-записи на событие.
Копия события остаётся в `analysis.log` со `stage=analysis_settings`.
События `saved_simple`, `saved_detailed`, `saved_direct`, `reset` фиксируют сохранение;
`applied_inspection` — настройки, реально применённые к кадру. Запись содержит
профиль, источник настроек, порог, признак `inspection_enabled`, полные параметры
алгоритма, а для сохранённых ползунков — общую чувствительность, значения групп
и их рассчитанные эффективные значения. При 0% дополнительно пишется событие
`inspection disabled; verdict forced to ГОДЕН`.

| Что | Где |
|-----|-----|
| HTTP | `backend/app/api/analysis_settings_preset_routes.py` |
| expand | `backend/app/services/analysis_settings_presets.py` |
| persist | `InspectionService.apply_simple_settings` / `apply_detailed_settings` |
| лаборатория | `analisSurface/test-analysis.html` |
