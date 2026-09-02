# Analysis settings: стыковка оркестратора и Python

Контракт между **analisSurface** (FastAPI), **orchestrator-java** (прокси) и **front-end** (React).

Техническая модель simple/strengths: [ANALYSIS_SETTINGS_SIMPLE_PRO.md](ANALYSIS_SETTINGS_SIMPLE_PRO.md).  
Тексты для UI: [ANALYSIS_SETTINGS_UI.md](ANALYSIS_SETTINGS_UI.md).  
Гайд для React: [ANALYSIS_SETTINGS_FRONTEND.md](ANALYSIS_SETTINGS_FRONTEND.md).

---

## 1. Что хранится на диске

Файл: `analisSurface/backend/app/data/analysis_settings.json`

Массив записей по `analysis_profile` (= `product_type` без суффикса `#cam=`):

```json
[
  {
    "analysis_profile": "bucket",
    "overrides": {
      "default_threshold": 0.25,
      "min_diff_signal": 12.0,
      "...": "..."
    },
    "simple_knobs": {
      "threshold": 0.25,
      "sensitivity": 0.5
    },
    "detailed_knobs": {
      "noise_tolerance": 50,
      "scratch_sensitivity": 80,
      "edge_suppression": 50,
      "text_handling": 50,
      "preprocess_strength": 100
    }
  }
]
```

| Блок | Смысл |
|------|--------|
| `overrides` | Развёрнутые 18 полей алгоритма (результат expand) |
| `simple_knobs` | Порог + общая чувствительность (0–1) |
| `detailed_knobs` | **Силы групп** (0–100), не вторая чувствительность |

При первом `PUT /simple` без сохранённых сил в JSON пишутся defaults (`50` для всех групп).

Старый ключ `pro_knobs` (0–1) при загрузке мигрируется в `detailed_knobs` (`× 100`).

---

## 2. Прокси оркестратора

Java не хранит настройки — только проксирует в Python:

```text
GET/PUT /api/orchestrator/analysis-settings/{product_type}/...
     → GET/PUT {analisSurface}/analysis-settings/{product_type}/...
```

Камера → профиль:

```text
GET/PUT /api/orchestrator/analysis-settings/camera/{cameraId}/strengths
     → /analysis-settings/{analysis_profile}/strengths
```

`analysis_profile` берётся из конфига камеры (`analysisProfileByCamera`).

Допустимые суффиксы: `/simple`, `/strengths`, `/detailed` (alias `/strengths`), legacy `/pro` на Python **не** поддерживается — использовать `/detailed` или `/strengths`.

---

## 3. Эндпоинты Python (кратко)

| Метод | Python | Назначение |
|-------|--------|------------|
| `GET/PUT` | `/analysis-settings/{profile}/simple` | `threshold` + `sensitivity` |
| `GET/PUT` | `/analysis-settings/{profile}/strengths` | **только 5 сил** (рекомендуется для UI) |
| `GET/PUT` | `/analysis-settings/{profile}/detailed` | силы + полный `settings` в ответе |
| `GET` | `/analysis-settings/{profile}` | эффективные settings + `simple_knobs` + `strength_knobs` |
| `PUT` | `/analysis-settings/{profile}` | прямое редактирование полей алгоритма (**сбрасывает** knobs) |
| `DELETE` | `/analysis-settings/{profile}` | сброс всего профиля |

### Поведение при сохранении

1. `PUT /simple` — сохраняет чувствительность, пересчитывает `overrides` с **уже сохранёнными** силами.
2. `PUT /strengths` — сохраняет силы, пересчитывает `overrides` с **текущей** simple-чувствительностью.
3. Оба слоя **не затирают** друг друга.

Инспекция (`/inspect-shm`) читает только `overrides` из JSON — после любого PUT knobs пересчёт уже в файле.

---

## 4. Ответ `GET /strengths`

```json
{
  "analysis_profile": "bucket",
  "saved": true,
  "strengths": {
    "noise_tolerance": 50,
    "scratch_sensitivity": 80,
    "edge_suppression": 50,
    "text_handling": 50,
    "preprocess_strength": 100
  }
}
```

| Поле | Тип | Описание |
|------|-----|----------|
| `saved` | bool | `false` — в JSON ещё нет `detailed_knobs`, отданы defaults |
| `strengths.*` | 0–100 | Сила отклика группы на движение `sensitivity` |

### Тело `PUT /strengths`

Только 5 полей (без `threshold` / `sensitivity`):

```json
{
  "noise_tolerance": 50,
  "scratch_sensitivity": 80,
  "edge_suppression": 50,
  "text_handling": 50,
  "preprocess_strength": 100
}
```

---

## 5. Миграция с `/pro`

| Было (pro) | Стало |
|------------|--------|
| `PUT .../pro` с 6–7 ручками включая threshold | `PUT .../simple` + `PUT .../strengths` |
| `noise_tolerance` 0–1 | `noise_tolerance` 0–100 (сила, не чувствительность) |
| Отдельная pro-чувствительность | **Нет** — только `sensitivity` в simple |

Фронт пока может звать `/pro` через оркестратор — нужно перейти на `/strengths` и убрать threshold из pro-панели.

---

## 6. Тестовый кадр (`/inspect-test-frame`)

Без записи в JSON. Тело:

```json
{
  "simple": { "threshold": 0.25, "sensitivity": 0.6 },
  "detailed": {
    "noise_tolerance": 0,
    "scratch_sensitivity": 50,
    "edge_suppression": 50,
    "text_handling": 50,
    "preprocess_strength": 50
  }
}
```

`simple` обязателен; `detailed` опционален (силы для preview, defaults 50).

---

## 7. Ошибки

| Код | Когда |
|-----|--------|
| `422` | Значение вне диапазона |
| `400` | Ошибка expand / неизвестное поле в полном API |
| `503` | Оркестратор: не настроен `python_detector.base_url` |
| `404` | Оркестратор: камера без `analysis_profile` |

---

## 8. Связанный код

| Компонент | Файл |
|-----------|------|
| HTTP preset | `backend/app/api/analysis_settings_preset_routes.py` |
| Persist | `backend/app/services/inspection_service.py` |
| Expand | `backend/app/services/analysis_settings_presets.py` |
| Прокси Java | `orchestrator-java/.../OrchestratorAnalysisSettingsHttpController.java` |
| Лаборатория | `analisSurface/test-analysis.html` |
