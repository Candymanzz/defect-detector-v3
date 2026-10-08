# Analysis settings: гайд для фронтенда

Как React ходит в оркестратор. Контракт Python и JSON — в
[ANALYSIS_SETTINGS_INTEGRATION.md](ANALYSIS_SETTINGS_INTEGRATION.md).

Фронт **не** ходит в Python напрямую. Базовый префикс:

```text
/api/orchestrator/analysis-settings
```

Клиент: `orchestratorApi` в `front-end/src/shared/api/orchestratorApi.ts`.

---

## Две вкладки UI

| Вкладка | API | Поля |
|---------|-----|------|
| Быстрая | `GET/PUT .../simple` | `threshold`, `sensitivity` (0–1) |
| Детальная | `GET/PUT .../strengths` | 5 сил (0–100), **без** threshold/sensitivity |

Чувствительность всегда одна — на вкладке «Быстрая».  
Детальная вкладка только задаёт, **насколько сильно** каждая группа следует за ней.

---

## Загрузка настроек камеры

```ts
// Порог + чувствительность
const simple = await orchestratorApi.getCameraSimpleAnalysisSettings(cameraId);
const knobs = simple.knobs ?? {
  threshold: simple.settings.default_threshold,
  sensitivity: 0.5,
};

// Силы групп (новый эндпоинт)
const strengths = await fetch(
  `/api/orchestrator/analysis-settings/camera/${cameraId}/strengths`
).then((r) => r.json());

// strengths.saved === false → все силы 50, в UI можно показать «стандарт»
// strengths.strengths.noise_tolerance и т.д. — слайдеры 0–100
```

По `product_type` (без камеры):

```ts
await orchestratorApi.getSimpleAnalysisSettings(productType);
// strengths: GET /api/orchestrator/analysis-settings/{productType}/strengths
```

Полный снимок (settings + knobs):

```ts
const full = await orchestratorApi.getCameraAnalysisSettings(cameraId);
// full.simple_knobs, full.strength_knobs — могут быть null
```

---

## Сохранение

### Быстрая настройка (оператор)

```ts
await orchestratorApi.setCameraSimpleAnalysisSettings(cameraId, {
  threshold: 0.25,
  sensitivity: 0.6,
});
```

### Силы групп (инженер, код 3333)

```ts
await fetch(
  `/api/orchestrator/analysis-settings/camera/${cameraId}/strengths`,
  {
    method: "PUT",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      noise_tolerance: 0,
      scratch_sensitivity: 80,
      edge_suppression: 50,
      text_handling: 50,
      preprocess_strength: 100,
    }),
  },
);
```

После сохранения сил движение слайдера **чувствительности** на простой вкладке будет по-другому менять алгоритм.

Рекомендуемый порядок при «Сохранить всё»:

1. `PUT /simple` — если меняли порог/чувствительность  
2. `PUT /strengths` — если меняли силы  

---

## Миграция с `pro`

Сейчас в коде ещё есть `getProAnalysisSettings` → `/pro`. **Заменить на `/strengths`:**

| Старое | Новое |
|--------|--------|
| `getProAnalysisSettings` | `GET .../strengths` |
| `setProAnalysisSettings` | `PUT .../strengths` |
| `ProAnalysisKnobs.threshold` | убрать — только в `SimpleAnalysisKnobs` |
| значения 0–1 на pro-слайдерах | 0–100 (силы) |

Тип для фронта:

```ts
type StrengthKnobs = {
  noise_tolerance: number;
  scratch_sensitivity: number;
  edge_suppression: number;
  text_handling: number;
  preprocess_strength: number;
};

type StrengthKnobsResponse = {
  analysis_profile: string;
  saved: boolean;
  strengths: StrengthKnobs;
};
```

Добавить в `orchestratorApi.ts` методы `getStrengthKnobs` / `setStrengthKnobs` по аналогии с simple.

---

## Тестовый кадр (модалка MainOverview)

`POST /api/orchestrator/inspect-test-frame`:

```ts
{
  simple: { threshold: 0.25, sensitivity: 0.6 },
  detailed: {
    noise_tolerance: 50,
    scratch_sensitivity: 50,
    edge_suppression: 50,
    text_handling: 50,
    preprocess_strength: 50,
  },
}
```

`detailed` здесь — те же силы, что в `PUT /strengths`. В диск **не** пишется.

---

## Подписи слайдеров

См. [ANALYSIS_SETTINGS_UI.md](ANALYSIS_SETTINGS_UI.md).

| API | UI |
|-----|-----|
| `noise_tolerance` | Сила · шум |
| `scratch_sensitivity` | Сила · царапины |
| `edge_suppression` | Сила · края |
| `text_handling` | Сила · текст |
| `preprocess_strength` | Сила · предобработка |

---

## Событие обновления

После сохранения диспатчить `analysis-settings-changed` (как для simple), чтобы другие панели перечитали профиль.

---

## Ошибки для оператора

| HTTP | Текст |
|------|--------|
| `422` | «Проверьте диапазоны слайдеров» |
| `503` | «Сервис анализа недоступен» |
| `404` | «Камера не настроена» |
