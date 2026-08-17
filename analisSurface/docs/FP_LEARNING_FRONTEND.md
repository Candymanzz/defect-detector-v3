# Дообучение на ложных срабатываниях: гайд для фронтенда

Как React ходит в оркестратор. Алгоритм Python и мост UUID — в
`FP_LEARNING_INTEGRATION.md`. Здесь только кнопки.

Фронт **не** ходит в Python. UUID review знать не нужно: оркестратор сам
находит кадр по `cameraId + frameId`.

Клиент уже есть: `orchestratorApi` в `front-end/src/shared/api/orchestratorApi.ts`.

---

## Что взять с карточки

История камеры уже хранит `InspectionHistoryItem.inspectResult`.
Клик по «20-й инспекции» — это найти эту карточку в UI. В API уходит не `20`.

```ts
const frame = item.inspectResult;

frame.frame_id                  // "1042" — номер кадра камеры
frame.camera_id                 // 2
frame.detector.product_type     // "bucket", без #cam=
frame.learned_review_id         // UUID; только чтобы включить кнопки
frame.inspection_id             // Java-цикл "17" — в learning не слать
```

`resolveInspectionId(...)` для этих кнопок не использовать.

| Слать | Не слать |
|---|---|
| `frame.frame_id` | позиция в ленте (`20`) |
| `frame.camera_id` | Java `inspection_id` |
| `frame.detector.product_type` | `#cam=` вручную |

Нет `learned_review_id` → кадр уже не в сессии обучения (рестарт Python,
вытеснен FIFO, архив). Обе кнопки disabled.

---

## Кнопка «Дообучить этот БРАК»

На кадре, который оператор считает ложным браком. Запоминает все контуры
этого кадра. Уже показанный вердикт и PLC **не** меняются.

Показывать, если статус БРАК и есть `learned_review_id`. После успешного
обучения — disabled, пока оператор не нажмёт сброс на этом кадре.

```ts
const result = await orchestratorApi.acceptLearnedNormals({
  frameId: frame.frame_id,
  cameraId: frame.camera_id,
  productType: frame.detector.product_type,
  note: "",
});
```

Это `POST /api/client/learning/accept-all-as-normal`.

Ответ:

```json
{
  "accepted_count": 3,
  "accepted_case_ids": ["ea81f37c-...", "..."],
  "inspection_id": "8e87869d-...",
  "affects_original_pipeline_decision": false
}
```

`accepted_case_ids` сохранить на этой карточке в локальном стейте.
Они нужны второй кнопке. UUID из ответа в следующие запросы класть не обязательно.

| HTTP | Что сказать |
|---|---|
| `200` | кадр помечен; следующие изделия этой камеры уже без этого ложняка |
| `404` | «Кадр уже не в сессии. Отметьте на свежем БРАКе.» |
| `409` | «Нечего учить» или «Уже дообучено» |

---

## Кнопка «Сбросить дообучение на этом кадре»

Снимает с памяти только то, что выучили **с этой карточки**.
Другие камеры и другие кадры не трогает. Историю в ленте не удаляет.
Вердикт этого изделия не переигрывает.

Ид норм — из ответа кнопки «дообучить» (`accepted_case_ids`).
Если стейт потеряли (перезагрузка страницы) — взять список и отфильтровать
по `source_inspection_id === frame.learned_review_id`.

```ts
const productType = frame.detector.product_type;
const cameraId = frame.camera_id;

let caseIds = item.acceptedCaseIds;
if (!caseIds?.length) {
  const query = new URLSearchParams({
    productType,
    cameraId: String(cameraId),
  });
  const payload = await orchestratorApi.clientProxyJson<{
    cases: Array<{ id: string; source_inspection_id?: string }>;
  }>(`/api/client/learning/accepted-cases?${query}`);
  caseIds = payload.cases
    .filter((item) => item.source_inspection_id === frame.learned_review_id)
    .map((item) => item.id);
}

await Promise.all(
  caseIds.map((caseId) =>
    orchestratorApi.clientProxyJson(`/api/client/learning/accepted-cases/${caseId}`, {
      method: "DELETE",
    }),
  ),
);
```

После успеха кнопку «дообучить» снова включить, `acceptedCaseIds` очистить.

`404` на одном id — норма уже удалена, идти дальше.

Не вызывать здесь `clearLearnedNormals()`: это снос **всей** сессии, всех камер.

---

## Кнопка «Сбросить все ложняки»

Не на карточке кадра. Нужна при смене видео / эталона / «начать смену заново».

```ts
await orchestratorApi.clearLearnedNormals();
```

`DELETE /api/client/learning/accepted-cases` — все камеры текущей сессии.
История кадров на экране остаётся.

---

## Чего не делать

- Не ходить в `/learning/...` на Python напрямую.
- Не подменять Java `inspection_id` UUID-ом.
- Не слать порядковый номер в ленте.
- Не трогать `client.fp_zones_update` — это старые полигоны, другой инструмент.
- Не ждать, что после «дообучить» текущая плашка станет ГОДЕН. Изменится
  только следующее изделие.
