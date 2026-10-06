# Бенчмарки analisSurface

Скрипты для проверки скорости инспекции и того, что оптимизации **не меняют результат**.
Запускать из `analisSurface/backend` (нужен тот же venv, что у сервиса):

```
.venv\Scripts\python.exe benchmarks\synthetic.py bench
```

> Скрипты не трогают реальные данные сервиса: ROI, FP-зоны, learned normals и настройки
> создаются во временной папке. Единственное побочное действие — может измениться
> `app/data/fp_zones.generation`; перед коммитом: `git checkout app/data/fp_zones.generation`.

## 1. Скорость на синтетике — `synthetic.py`

Кадры 1224×1024 с текстом, царапиной, пятном и сдвигом света; ROI-полигон.

| Команда | Что делает |
|---|---|
| `synthetic.py bench` | медиана/p95 времени одного inspect (с прогревом, 48 замеров) |
| `synthetic.py record [файл]` | записать результаты 24 прогонов (3 набора настроек × 4 кадра × 2 масштаба) |
| `synthetic.py check [файл]` | прогнать снова и сравнить побитово: статус, score, карта отличий, маска |
| `synthetic.py profile` | cProfile — где уходит время (по `tottime`) |

## 2. Скорость и регрессия на реальных кадрах — `real_frames.py`

Нужен эталон и папка кадров (после позиционирования, того же размера, что эталон).

```
python benchmarks\real_frames.py bench  --reference ref.png --frames D:\frames --scale 0.75
python benchmarks\real_frames.py record --reference ref.png --frames D:\frames --scale 0.75 --out before.json
python benchmarks\real_frames.py check  --reference ref.png --frames D:\frames --scale 0.75 --out before.json
```

Параметры: `--roi "0.05,0.05;0.95,0.05;0.95,0.95;0.05,0.95"` (полигон 0..1),
`--sens 0.5`, `--threshold 0.45`, `--max-frames 200`, `--rounds 3`.
Берите те же `--scale`/`--roi`/`--sens`, что на производстве.

## 3. Статистика по стадиям из логов — `log_stats.py`

```
python benchmarks\log_stats.py "logs\*\responses.log"
```

Среднее, медиана и p95 для `py_diff_ms`, `py_anomaly_ms`, `py_fp_recheck_ms`,
`py_heatmap_ms`, `py_total_ms` на реальном потоке. Нужен `python_detector.file_logging: true`.

## Как сравнить «до» и «после» на рабочем компьютере

1. Взять старую версию кода: `git stash` (или `git checkout <старый коммит>`).
2. `real_frames.py record ... --out before.json` и `real_frames.py bench ...` — запомнить время.
3. Вернуть новый код (`git stash pop`).
4. `real_frames.py check ... --out before.json` — должно быть **ИДЕНТИЧНО**, затем `bench` — новое время.
5. Для боевого потока: сохранить вывод `log_stats.py` до и после обновления.

Если `check` показывает РАЗЛИЧИЯ — это изменение результата инспекции, а не скорости; сообщите об этом.
Сравнивать баз-файлы стоит только на одной машине и одной версии OpenCV/NumPy.
