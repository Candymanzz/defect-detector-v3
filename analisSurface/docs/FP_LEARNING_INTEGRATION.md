# Стыковка дообучения на ложных срабатываниях с основной программой

Гайд для оркестратора (Java) и контракт Python. Кнопки React — отдельно:
`docs/FP_LEARNING_FRONTEND.md`.


Эталон поведения в Python: страницы `/learning-review` и `/local-inspection-test`.

---

## 1. Что уже работает без основной программы

На каждом `POST /inspect-shm` и `POST /inspect-shm-visuals` Python сам:

1. считает обычный diff vs эталон;
2. гасит знакомые ложняки этой камеры и этого эталона (каскад кропа);
3. кладёт кадр в сессионную историю (`ГОДЕН` и `БРАК`);
4. возвращает UUID кадра в поле `inspection_id`.

Кнопка «дообучить» **не переигрывает** уже отправленный на PLC вердикт.
Она влияет только на **следующие** инспекции этой же сессии Python.

Старые FP-зоны (полигон оператора) **не удалять**. Это отдельный запасной
инструмент: hard exclude области. Learned normals живут рядом.

---

## 2. Главный блокер: два разных `inspection_id`

| Кто | Что это | Пример | Куда уходит сейчас |
|---|---|---|---|
| Java / оркестратор | цикл конвейера (`long`), один на все камеры изделия | `"17"` | WS `server.inspect_result.inspection_id` |
| Python | UUID кадра в истории обучения | `"8e87869d-5c5a-…"` | JSON ответа `/inspect-shm`, дальше **теряется** |

Фронт берёт id так:

```ts
// front-end/src/components/MainOverview/MainController.ts
export function resolveInspectionId(inspectResult) {
  return inspectResult.inspection_id ?? inspectResult.frame_id;
}
```

В WS оркестратор кладёт Java-id:

```java
// WsOutboundMessenger.buildInspectResultJson
payload.put("inspection_id", Long.toString(inspectionId));
```

А Python UUID оркестратор **не прокидывает**: `inspectJsonToStdioHeader`
копирует `status`, `anomaly_score`, `rechecked_zone_ids` — и не копирует
`inspection_id` Python.

Без моста кнопка «дообучить» в основном UI **невозможна**: фронт шлёт `"17"`,
Python отвечает `404`.

**Правило:** Java `inspection_id` не трогать. Добавить отдельное поле.

Рекомендуемые имена (любое одно, дальше в гайде — `learned_review_id`):

- `learned_review_id`
- `python_inspection_id`

Не класть UUID Python в существующее `inspection_id`: сломается архив кадров,
история изделия и PLC-телеметрия.

---

## 3. Минимальный контур (MVP)

Достаточно трёх изменений. Историю всех кадров и список норм можно добавить
вторым шагом.

```text
камера → оркестратор → Python /inspect-shm
                              ↓
                     JSON.inspection_id (UUID)
                              ↓
         оркестратор помнит UUID по cameraId+frameId
         и кладёт learned_review_id в WS
                              ↓
              оператор кликает карточку в истории
                              ↓
     POST /api/client/learning/accept-all-as-normal
          { frameId, productType, cameraId }
                              ↓
              следующие кадры этой камеры уже тише
```

Оркестраторский мост **уже есть** (`LearnedReviewIndex`, WS `learned_review_id`,
`POST /api/client/learning/accept-all-as-normal`). Фронту UUID знать не нужно.
Прокси: `/api/client/learning/...` → Python `/learning/...`.

Кнопки «дообучить» и «сбросить на этом кадре»: `docs/FP_LEARNING_FRONTEND.md`.

---

## 4. Полный контур (как на `/learning-review`)

После MVP:

| Элемент UI | Откуда данные | Фильтр |
|---|---|---|
| Лента кадров этой камеры | `GET /learning/reviews?product_type=` | `"{product}#cam={N}"` |
| Карточка кадра (aligned / heatmap / diff / mask) | `GET /learning/reviews/{id}/image/{kind}` | UUID Python |
| Кнопка «дообучить» | `POST /api/client/learning/accept-all-as-normal` | `frameId` + `cameraId` + `productType` с карточки |
| Список выученных ложняков | `GET /learning/accepted-cases?product_type=` | тот же `#cam=N` |
| Удалить один ложняк | `DELETE /learning/accepted-cases/{case_id}` | — |
| Сбросить все ложняки | `DELETE /learning/accepted-cases` | все камеры сессии |

Картинки **не** брать с камеры и **не** собирать на фронте из heatmap SHM.
Review-картинки — снимок того inspect, по которому учили. Камера уже ушла дальше.

Не путать с Java frame-archive (`/api/orchestrator/frame-archive/...`):
это другой архив, другие id, переживает рестарт. Learning-история — сессионная.

---

## 5. Контракт Python, который нельзя ломать

### Поля ответа inspect

Уже есть в `/inspect`, `/inspect-shm`, `/inspect-shm-visuals`:

| Поле | Тип | Смысл |
|---|---|---|
| `inspection_id` | `string \| null` | UUID review. Есть и у ГОДЕН, и у БРАК |
| `learned_normal_matches_count` | `int` | сколько областей погасили на этом кадре |
| `learned_normal_adjustment` | `number` | насколько упал score после погашения |
| `matched_accepted_case_ids` | `string[]` | какие сохранённые нормы сработали |

Их полезно показать оператору («этот БРАК уже частично погашен»), но для кнопки
достаточно `learned_review_id`.

### Кнопка: один запрос

```http
POST /learning/reviews/{inspection_id}/accept-all-as-normal
```

Тело опционально: `{ "note": "блик этикетки" }`.

Успех `200`:

```json
{
  "inspection_id": "8e87869d-...",
  "accepted_count": 3,
  "accepted_case_ids": ["ea81f37c-...", "..."],
  "affects_original_pipeline_decision": false
}
```

`affects_original_pipeline_decision=false` — контракт. Оркестратор **не** должен
после этого слать новый вердикт в PLC / менять `overall_pass` уже ушедшего кадра.

Ошибки:

| HTTP | Когда | Что показать оператору |
|---|---|---|
| `404` | кадр вытеснен FIFO (лимит 50) или Python перезапустили | «Кадр уже не в сессии. Отметьте на свежем БРАКе.» |
| `409` | уже принято **или** на кадре нет контуров (ГОДЕН / пустая маска) | «Нечего учить» / «Уже дообучено» |
| `503` | Python недоступен | как обычно для детектора |

Точечный accept одного контура (`POST .../defects/{defect_id}/accept-as-normal`)
для прод-кнопки не нужен. Это запасной advanced-режим.

### Удаление норм

Два DELETE. Прокси те же: `/api/orchestrator/learning/accepted-cases...`.

Один ложняк — если оператор ошибся на конкретном куске:

```http
DELETE /learning/accepted-cases/{case_id}
```

Успех `200`:

```json
{"deleted": true, "case_id": "ea81f37c-..."}
```

`404` — нормы уже нет (удалили раньше или Python перезапустили).

Все ложняки сразу — смена видео, эталона или «начать смену заново».
Без фильтра по камере: чистит **всю** сессию Python.

```http
DELETE /learning/accepted-cases
```

Успех `200`:

```json
{"deleted": true, "cases_count": 3}
```

Пустой список тоже `200`, `cases_count: 0`.

Что происходит:

- файлы в `accepted_normals/` удаляются;
- история кадров **не** стирается, с них только снимается пометка «уже принято»;
- следующие инспекции снова считают эти следы браком;
- PLC-вердикт уже прошедших изделий не меняется.

`POST /clear-inspection-context` эти нормы **не** трогает. Для смены видео
нужен именно `DELETE /learning/accepted-cases`, не удаление по одной.

`case_id` берётся из `GET /learning/accepted-cases` или из
`accepted_case_ids` ответа `accept-all-as-normal`. Это не Java `inspection_id`
и не `learned_review_id`.

### Ключ применения норм

Норма живёт в паре:

- `product_type` — в проде оркестратор уже скоупит как `{product}#cam={N}`
  (`AnalisSurfaceHttpBinaryRpcSupervisor.scopedProductType`);
- `reference_hash` — от текущего эталона этой камеры.

Поэтому:

- ложняк камеры 2 не гасит камеру 3;
- смена эталона сама отключает старые нормы;
- список `accepted-cases` на фронте фильтровать тем же `"bucket#cam=2"`,
  который Python реально видел. Клиенту оркестратор сейчас возвращает
  **original** `product_type` без `#cam=`. Для learning-запросов нужен scoped.

Как получить scoped строку на фронте:

```text
`${productType}#cam=${cameraId}`
```

Точно так же, как Java: `product + "#cam=" + cameraId`.
Не выдумывать другой разделитель.

### Сессия

| Слой | Путь на диске Python | После рестарта Python |
|---|---|---|
| История кадров для кнопки | `backend/app/data/learning_reviews/` | пусто (wipe) |
| Выученные ложняки | `backend/app/data/accepted_normals/` | пусто (wipe) |

Лимит истории: 50 кадров на процесс, FIFO. Переменная
`ANALIS_LEARNING_REVIEW_LIMIT`.

Это **не баг**. После рестарта свет / эталон / геометрия другие, вчерашние
куски вредны. Не складывать learning-историю в Java frame-archive и не
обещать оператору, что ложняки переживут перезапуск детектора.

---

## 6. Куда класть код в основной программе

### Оркестратор

Уже сделано в клиентском API:

| Задача | Где |
|---|---|
| Карта `cameraId+frameId` → UUID | `LearnedReviewIndex` |
| WS `learned_review_id` | `WsOutboundMessenger.buildInspectResultJson` |
| Фронт шлёт `frameId`, не UUID | `POST /api/client/learning/accept-all-as-normal` |
| Прокси остальных learning URL | `/api/client/learning/**` и `/api/orchestrator/learning/**` |

### React

Кнопки и поля с карточки: `docs/FP_LEARNING_FRONTEND.md`.

Старый поток `client.fp_zones_update` не трогать.

---

## 7. Пример WS после моста

Фрагмент `server.inspect_result.payload` (новые поля помечены):

```json
{
  "camera_id": 2,
  "frame_id": "1042",
  "inspection_id": "17",
  "learned_review_id": "8e87869d-5c5a-43da-a4d1-b9920fcacdcb",
  "python_status": "БРАК",
  "overall_pass": false,
  "learned_normal_matches_count": 1,
  "learned_normal_adjustment": 0.14
}
```

- `inspection_id` — как сейчас, Java cycle. В learning API не слать.
- `frame_id` — то, что уходит в `acceptLearnedNormals({ frameId })`.
- `learned_review_id` — сигнал «этот кадр ещё можно дообучить». В тело POST
  класть не обязательно: оркестратор сам найдёт UUID по `frame_id`.

---

## 8. Порядок работ

1. ~~Мост UUID и клиентский POST~~ **сделано** (`LearnedReviewIndex`,
   `POST /api/client/learning/accept-all-as-normal`).
2. Кнопки на карточке БРАКа: `docs/FP_LEARNING_FRONTEND.md`.

---

## 9. Проверка на стенде

1. Запустить Python и основную программу как обычно.
2. Прогнать изделие в БРАК на одной камере.
3. В WS-сообщении есть `learned_review_id` в формате UUID, а `inspection_id`
   по-прежнему число.
4. Кнопка на **выбранной карточке** истории шлёт `frameId` этой карточки
   (не `20` и не Java `inspection_id`), отвечает `200`,
   `affects_original_pipeline_decision=false`. Уже показанный вердикт на экране
   и на PLC не меняется.
5. Следующее такое же изделие на **той же** камере: `learned_normal_matches_count > 0`
   или статус стал ГОДЕН, если ложняк был единственной причиной.
6. Соседняя камера **не** изменилась.
7. Перезапуск только Python: кнопка по старому UUID даёт `404`, новые ложняки
   нужно отметить заново.
8. Полигон FP-зоны по-прежнему вырезает область.
9. `DELETE /learning/accepted-cases` отвечает `200`, список норм пустой,
   повтор того же изделия снова даёт БРАК.

Локально без основной программы тот же сценарий уже есть на
`http://127.0.0.1:8000/local-inspection-test`.
