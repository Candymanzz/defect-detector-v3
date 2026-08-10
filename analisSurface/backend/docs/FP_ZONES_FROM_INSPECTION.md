# FP-зоны из прошлых инспекций

## Что сохранить с инспекции

Из ответа `/inspect-shm` или `/inspect-shm-visuals` возьмите:

- `fp_zone_context.product_type`
- `fp_zone_context.heatmap_w`
- `fp_zone_context.heatmap_h`
- путь к кадру/heatmap в архиве (`http_path` / archive path)
- heatmap той инспекции (для UI-разметки)

## Как создать зону позже

1. Откройте heatmap нужной инспекции.
2. Нарисуйте полигон ложняка (норм. координаты `0..1`).
3. `POST /fp-zones`:

```json
{
  "product_type": "bench",
  "heatmap_w": 1280,
  "heatmap_h": 720,
  "points": [
    {"x": 0.12, "y": 0.20},
    {"x": 0.28, "y": 0.20},
    {"x": 0.28, "y": 0.35},
    {"x": 0.12, "y": 0.35}
  ],
  "source_frame_path": "/api/frame-archive/0/frames/42/heatmap",
  "note": "шум текста"
}
```

## Важно

- Рисовать по **heatmap** (или overlay heatmap на aligned), не по сырому кадру камеры.
- Обязательно: `product_type` + `points` + `heatmap_w/h`.
- Опционально: `source_frame_path`, `note`.
- `source_frame_path` сервер **не открывает** — только сохраняет как ссылку на источник разметки.
