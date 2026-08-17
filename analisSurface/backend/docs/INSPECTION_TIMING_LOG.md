# Журнал времени инспекции

Backend записывает одну JSON-строку на каждый запрос `POST /inspect-shm` и
`POST /inspect-shm-visuals`. Отсчёт начинается при получении запроса с кадром в
shared memory и заканчивается после формирования вердикта `ГОДЕН` или `БРАК`.
Фронтенд для этого не нужен.

По умолчанию файл находится здесь:

```text
analisSurface/backend/app/data/inspection_timing.jsonl
```

Путь можно изменить переменной окружения `ANALIS_INSPECTION_TIMING_LOG`.
Файл ротируется после 20 MiB, сохраняются три предыдущих файла.

Пример строки:

```json
{"event":"inspection_timing","endpoint":"/inspect-shm","started_at":"...","duration_ms":42.317,"product_type":"bucket#cam=2","camera":"2","width":1920,"height":1080,"status":"ГОДЕН","anomaly_score":0.04,"inspection_id":"..."}
```

Для анализа на компьютере скопируйте `inspection_timing.jsonl` со станка и
посчитайте, например, медиану и p95 по камере:

```powershell
Get-Content .\inspection_timing.jsonl | ForEach-Object { $_ | ConvertFrom-Json } |
  Group-Object camera | Select-Object Name,Count
```

Измерение начинается с момента получения кадра backend, а не с аппаратного
момента экспозиции сенсора. Если позже producer начнёт передавать timestamp
съёмки, его можно будет добавить отдельным полем без изменения расчёта
`duration_ms`.
