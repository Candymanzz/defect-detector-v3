# cppAnalisSurface

C++ сервис анализа поверхности: сравнивает кадр с эталоном камеры внутри области контроля (ROI) и возвращает
вердикт **ГОДЕН / БРАК**, оценку аномалии `anomaly_score` (0..1) и heatmap с зонами отличий. Без машинного обучения —
классическое компьютерное зрение (OpenCV). Заменяет Python-сервис `analisSurface` для оркестратора
(`python_detector.backend: cpp`).

Что умеет: эталон на камеру, ROI-полигон, чувствительность, порог брака, допуск на микросдвиг, минимальная площадь
дефекта, масштаб анализа. Чего нет намеренно: дообучения, FP-зон, под-зон ROI, перспективной линии, профилей Simple/Pro.

## Диагностика времени

Отдельные подробные тайминги Java, камер, C++ и отправки вердикта в ПЛК пишутся в эту же папку `logs`.
Формат, включение и границы измерений: [PERFORMANCE_LOGGING.md](PERFORMANCE_LOGGING.md).

## Быстрый старт (Windows)

```powershell
cd cppAnalisSurface
.ebuild-and-run.cmd          # проверить/доустановить инструменты и библиотеки, собрать, запустить
.un.cmd                      # только запустить (без сборки)
```

`rebuild-and-run` (`.cmd` вызывает `.ps1`, поддерживается Windows PowerShell 5.1+):

| Шаг | Что проверяется | Если нет |
|---|---|---|
| 1 | Visual Studio C++ (cl.exe), через vswhere | скачивает Build Tools 2022 и ставит с нагрузкой C++ (нужны права администратора, будет запрос UAC; 3–6 ГБ) |
| 2 | CMake >= 3.25 | скачивает portable CMake в `.tools` |
| 3 | git (нужен только для vcpkg) | скачивает portable MinGit в `.tools` |
| 4 | vcpkg (`VCPKG_ROOT`, `D:vcpkg`, `C:vcpkg`, `%USERPROFILE%vcpkg`) | клонирует в `<диск проекта>vcpkg` и собирает `vcpkg.exe` |
| 5 | библиотеки opencv4, cpp-httplib, nlohmann-json в `buildvcpkg_installed` и актуальность `vcpkg.json` | доустанавливает через vcpkg (первый раз OpenCV собирается 10–40 мин, дальше берётся из кэша) |

Всё, что уже установлено, пропускается (`[skip]`). Перед сборкой скрипт останавливает уже запущенный `cpp_analis_surface.exe` из этой сборки.

Опции `rebuild-and-run`: `-CheckOnly` (только отчёт, ничего не ставит), `-NoRun` (собрать, не запускать), `-Clean` (стереть `build` и поставить библиотеки заново), `-Port`, `-Workers`, `-DataDir`, `-BindHost`, `-Config`.
Опции `run`: `-Port 8000`, `-Workers N` (по умолчанию число логических потоков), `-DataDir`, `-BindHost`. Если порт занят, `run` откажется стартовать. Если exe ещё нет, подскажет запустить `rebuild-and-run`.

## Сборка вручную

Зависимости: OpenCV (core, imgproc, imgcodecs), cpp-httplib, nlohmann-json. Менеджер — vcpkg (манифест `vcpkg.json`).

**Windows** (Visual Studio Build Tools 2022, vcpkg в `D:/vcpkg`, путь можно поменять в `CMakePresets.json`):

```powershell
cd cppAnalisSurface
cmake --preset windows
cmake --build build --config Release
# build\Release\cpp_analis_surface.exe
```

**Linux** (первая сборка собирает OpenCV через vcpkg, потом берётся из кэша):

```bash
export VCPKG_ROOT=~/vcpkg      # git clone https://github.com/microsoft/vcpkg && ./vcpkg/bootstrap-vcpkg.sh
cd cppAnalisSurface
cmake --preset linux
cmake --build build
# build/cpp_analis_surface
```

Без vcpkg на Debian/Ubuntu: `apt install libopencv-dev nlohmann-json3-dev libcpp-httplib-dev cmake ninja-build`, затем
`cmake -S . -B build -G Ninja -DCMAKE_BUILD_TYPE=Release && cmake --build build`.

## Запуск

```
cpp_analis_surface [--host 127.0.0.1] [--port 8000] [--workers N] [--data-dir data]
```

| Параметр | Значение |
|---|---|
| `--port` | порт HTTP (оркестратор подставляет/заменяет `--port N`) |
| `--workers` | сколько инспекций одновременно считается (по умолчанию = число потоков процессора; env `ANALIS_INSPECT_WORKERS`) |
| `--data-dir` | где хранятся настройки профилей (`settings.json`) |

Один процесс обслуживает все камеры: внутри пул потоков, OpenCV работает однопоточно в каждом запросе
(параллелизм — по камерам). Процессов можно поднять несколько на соседних портах (общий `--data-dir` безопасен:
файл настроек перечитывается при изменении), но обычно один процесс быстрее и экономнее по памяти.

## Подключение к оркестратору

`config/blocks/20-detectors.yaml`:

```yaml
python_detector:
  backend: cpp
  server_concurrency: 10      # одновременных запросов клиент -> сервис
  base_url: "http://127.0.0.1:8000"
```

`config/blocks/01-core.yaml` (см. `config.exemple`): `analis_surface_autostart.working_dir: cppAnalisSurface`,
`analis_surface_command_windows/linux` (путь к exe, `--port`, `--data-dir data`),
`python_parallelism: 10` (потоков стадии детектора = камер одновременно; НЕ 1), `python_server_pool_size: 1`, `python_request_parallelism: 10`.

## HTTP API

Все JSON-ответы содержат `detector_id`. Ошибки: `{"detail": "...", "error_code": "..."}`; 400 — неверные данные / нет
эталона (`no_reference`) / проблема с SHM-файлом, 404 — нет ROI, 500 — внутренняя ошибка или сбой экспорта картинок
в `/inspect-shm-visuals`.

| Метод и путь | Назначение |
|---|---|
| `GET /health`, `GET /detector/health` | живость |
| `POST /upload-ref-shm` | эталон из BGR-кадра в разделяемой памяти |
| `POST /upload-ref` | эталон из файла (multipart `product_type`, `file`) — для ручных тестов |
| `POST /roi-polygon`, `GET /roi-polygon/{product}` | ROI, точки **нормализованы 0..1**, минимум 3 |
| `POST /inspect-shm` | основной путь: вердикт, score, зоны, heatmap в SHM (если задан `heatmap_u8_output_path`) |
| `POST /inspect-shm-visuals` | то же + `aligned_image_u8/diff_map_u8/segmentation_mask_u8` в SHM |
| `POST /inspect-test-frame` | JPEG с диска (`cache_key`, `file_path`), временные настройки в `settings`, не сохраняются |
| `POST /inspect` | multipart `product_type`, `file` (+ необязательные настройки) — ручной тест без SHM |
| `POST /clear-inspection-context` | сбросить эталоны и ROI |
| `GET/PUT/DELETE /settings/{profile}`, `GET /settings/defaults` | настройки профиля (камеры) |

Ключ эталона и ROI — `product_type` вида `<изделие>#cam=<id>`; ключ настроек — `analysis_profile`
(по умолчанию `product_type`).

### Запрос `/inspect-shm`

```json
{
  "product_type": "bucket#cam=3",
  "analysis_profile": "bench-lan4",
  "shm_name": "iml_cam_3_frame", "width": 1224, "height": 1024, "stride": 3672, "shm_offset": 0,
  "roi_polygon_norm": [{"x":0.1,"y":0.1}, {"x":0.9,"y":0.1}, {"x":0.9,"y":0.9}],
  "heatmap_u8_output_path": "/dev/shm/iml_ui_heatmap_cam_3_frame_17", "heatmap_max_width": 512,
  "settings": {"threshold": 0.3}
}
```

`roi_polygon_norm` отправляется с каждым запросом и применяется перед инспекцией. `settings` — необязательные
разовые переопределения (также принимаются плоские поля `threshold`, `sensitivity`, …). Кадр читается из файла
`/dev/shm/<shm_name>` (Windows: `%LOCALAPPDATA%\iml_shm\<shm_name>`), BGR8, и копируется.

### Ответ

Поля контракта прежнего Python-сервиса сохранены (`status`, `anomaly_score`, `threshold`, `raw_anomaly_score`,
`main_roi_score`, `py_*_ms`, `heatmap_u8{path,width,height,stride,channels,dtype}`, …; неиспользуемые — пустые/нули).
Новые поля: `area_percent` (доля аномальной площади ROI, %), `reason` (`score_over_threshold` | `shift_exceeded`),
`shift` (найденный сдвиг кадра, px), `zones` (`[x,y,w,h]` в пикселях исходного кадра), `settings` (применённые настройки),
`frame_width/frame_height`.

`status` = `БРАК`, если `anomaly_score >= threshold` или кадр сдвинут сильнее допустимого (`shift_exceeded`).

## Настройки профиля

| Поле | Диапазон | По умолчанию | Смысл |
|---|---|---|---|
| `sensitivity` | 0..1 | 0.5 | выше — строже: порог разницы пикселей падает с 80 до 5 |
| `threshold` | (0..1] | 0.25 | порог брака по `anomaly_score` |
| `shift_tolerance` | 0..30 px | 3 | допуск на микросдвиг (сравнение с min/max-окрестностью эталона) |
| `min_blob_area` | 1..100000 px | 20 | меньшие отличия — шум |
| `inspect_scale` | 0.1..1 | 1.0 | во сколько раз уменьшать кадр перед анализом |

`anomaly_score = min(1, area_percent / 5)`: порог 0.25 ≈ 1.25% площади ROI.

### Контроль стыка этикетки (`seam`)

Вложенный объект настроек профиля. Измеряет прямоту тонкой светлой линии стыка вдоль края этикетки и **не сравнивает её
с эталоном** (положение изделия меняется от кадра к кадру). Линия стыка хранится **вместе с эталоном камеры**: оператор
рисует её при настройке эталона кнопкой «Линия шва» в редакторе ROI (рядом с «Линией перспективы»), она уходит в пакет
эталона как `seam_line_norm` (две точки {x,y} в 0..1), оркестратор передаёт её в каждом запросе инспекции и она
перекрывает `seam.p0/p1` из настроек (те остаются запасным вариантом). Включение и допуски — в карточке «Контроль стыка
этикетки» настроек анализа.

| Поле `seam.*` | Диапазон | По умолчанию | Смысл |
|---|---|---|---|
| `enabled` | bool | false | включить проверку |
| `p0`, `p1` | [x, y] в 0..1 | [0,0] | концы линии стыка (приблизительно) |
| `band` | 5..200 px | 30 | полоса поиска вокруг линии (запас на позу изделия) |
| `tol_mid` | 0.1..50 px | 1.0 | допуск отклонения линии от плавной кривой в середине стыка |
| `tol_edge` | 0.1..50 px | 5.0 | допуск на концах стыка |
| `edge_frac` | 0..0.5 | 0.12 | доля длины стыка с каждого конца, считающаяся «концом» |

На каждом сечении находятся левый и правый край линии (по половине высоты профиля), каждый край сравнивается с плавной
(парабола, учёт цилиндра) кривой, подогнанной по нему самому. Первые и последние 3% длины не оцениваются. Если любое
отклонение выше допуска в своей позиции — `status = БРАК`, `reason = seam_deviation` (если причина по эталону уже есть,
она остаётся основной), в `zones` добавляется рамка вокруг проблемных точек. Если линия не найдена (нет этикетки), стык
сам по себе брак не даёт. В ответе и в логах есть объект `seam` (`found`, `reject`, `ratio`, `max_dev`, `worst_t`,
`coverage`, `ms`). Временно значения можно переопределить полем `seam` в `settings` запроса (как остальные настройки).
Старая проверка шва в `java-geometry-service` (`joint*`) независима и не затронута.

## Алгоритм (кратко)

Серый кадр → уменьшение → размытие → оценка сдвига (`phaseCorrelate` по миниатюре ROI) и компенсация → выравнивание
яркости внутри ROI → разница с min/max-огибающей эталона (учёт микросдвига) → порог по чувствительности → морфология,
отсев мелких пятен → доля площади в ROI. Анализ ограничен прямоугольником ROI (+поля) — маленькая ROI считается быстрее.

## Производительность (Windows, 8 потоков, кадры 1224×1024)

10 одновременных запросов `/inspect-shm` (чтение SHM + анализ + запись heatmap), пачка целиком:
масштаб 1.0 — около 155–180 мс, масштаб 0.75 — около 115 мс. Бюджет — 500 мс.
