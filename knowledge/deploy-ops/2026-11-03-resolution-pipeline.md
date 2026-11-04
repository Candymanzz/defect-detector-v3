# Разрешение и качество кадров — проверка 2026-11-03

По запросу пользователя сопоставлены рабочие JSON/YAML, их imports, код JNI/Java/C++,
настройки камер и детектора из API, размеры реальных JPEG и metadata архива.
Сервис, настройки камер/детектора, эталоны и выходы не менялись.

## Фактический результат

На всех десяти камерах `inspect_scale=1.0` в сохранённых C++-настройках профиля.
Глобальный downscale захваченного кадра отключён. Текущий кадр инспекции
передаётся в C++ как несжатый BGR8 SHM с размерами аппаратного ROI камеры.
Архивный JPEG сохраняет те же размеры, но имеет JPEG-сжатие quality=90.
Основное UI-превью quality=45, карточка до 384 px quality=30 — отдельные копии.

Снимки проверки: `.verification/resolution-pipeline-20261103/runtime.json`,
`archive.json`, `hardware-monitor.json` (локальные, игнорируются Git).

## Импорты и две разные настройки inspect_scale

`config/config.yaml` импортирует `cpp-linux-femboy36-20261021.yaml`, который
импортирует `python-before-cpp-20261021.yaml` и затем накладывает свой блок C++.
Тот агрегатор подключает `blocks/01-core.yaml`, `20-detectors.yaml`,
`30-ui-and-client.yaml` и другие блоки. Native capture отдельно читает config.json.

В старом общем `python_detector` блоке до сих пор написаны inspect_scale=0.7
и inspect_scale_by_camera: {2:0.2,7:0.2}. `InspectPythonExecutor` может поставить
этот legacy ключ в заголовок, но текущий `CppAnalisSurfaceHttpSupervisor`
не переносит отдельный inspect_scale из заголовка в C++ body.settings:
`shmFrameBody` передаёт `ephemeralSettings`, принимающий только settings,
analysis_test_settings.settings либо simple threshold/sensitivity.

Поэтому эти YAML-числа не описывают рабочий масштаб C++ этой сессии.
`GET /api/orchestrator/detector-settings/camera/{0..9}` подтвердил inspect_scale=1.0
для всех профилей. В проверенных архивных diagnostics камер 0/6 есть work_scale=1.0;
на остальных нет соответствующих learned diagnostics, масштаб подтверждён API/кодом.
Временная тестовая настройка внутри settings может отдельно изменить масштаб одного запроса.

## Камера, геометрия и Bayer

В JSON и `01-core.yaml` базовая геометрия 1224×1024, Bayer8, binning=2×2 Sum.
При старте JNI логирует успешную команду SetEnumValue binning=2×2, затем mismatch
текущего ROI с целевыми 1224×1024 и `binning not effective, fallback to ROI`.
Код проверяет только Width/Height, не состояние binning. Этот лог **не доказывает**,
что binning выключен: старый ROI мог сохранить Width/Height после применения binning.
Обратный комментарий «разрешение уже уменьшено binning» тоже не является проверкой.

Фактические BinningHorizontal/Vertical и SensorWidth/Height не удалось прочитать:
открытие вторым SDK-клиентом в read-only Monitor отклонено 0x80000203 на всех камерах.
Управляющий доступ JVM не отбирался, захват не останавливался, настроек не писали.
Поэтому физическая матрица → binning здесь остаётся непроверенным участком.
Достоверно установлены рабочая граница 1224×1024 и реальные аппаратные ROI ниже.

После базовой настройки native применяет сохранённый ROI из
`config/data/camera_runtime_settings.json`. Это обрезка области с координатами
OffsetX/OffsetY/Width/Height, без программного ресайза оставшихся пикселей.
SDK передаёт BayerRG8, один байт на пиксель; восстановление BGR8 через
MV_CC_ConvertPixelTypeEx оставляет Width/Height прежними, создаёт три цветовых канала.
Цвета Bayer интерполируются, это не JPEG. Гамма влияет на яркость, не размеры.

| Камера | Offset X,Y | Кадр API/SHM и архив JPEG | Масштаб C++ |
|---|---|---|---:|
| 0 | 252,36 | 780×588 | 100% |
| 1 | 320,104 | 740×520 | 100% |
| 2 | 432,156 | 656×656 | 100% |
| 3 | 176,136 | 812×560 | 100% |
| 4 | 216,104 | 844×548 | 100% |
| 5 | 232,36 | 756×548 | 100% |
| 6 | 284,44 | 812×592 | 100% |
| 7 | 248,20 | 888×888 | 100% |
| 8 | 88,64 | 868×572 | 100% |
| 9 | 300,164 | 748×528 | 100% |

## Перед анализом и внутри C++

В `01-core.yaml`: capture_frame_downscale.enabled=false и scale=1.0.
Флаги apply_to_inspection_capture/reference/client_reference_bundle при enabled=false
его не включают. Сохранение `save_captures` в testimage выключено; его quality=0.92
не относится к действующему frame_archive.

Обычная живая инспекция читает BGR8 из SHM без JPEG. C++ преобразует его в gray,
при inspect_scale=1.0 не уменьшает размеры, затем всегда применяет GaussianBlur 5×5.
Это сглаживание для анализа: размер сохраняется, самые тонкие детали смягчаются.
Эталон проходит аналогичную подготовку.

Для быстрого поиска смещения X/Y используется отдельная уменьшенная копия ROI:
длинная сторона не более 256 px. Найденный сдвиг переносится в рабочие координаты;
основное сравнение дефектов не выполняется на этой миниатюре. Внутренний ROI/маска
этикетки ограничивает область сравнения без снижения плотности пикселей.
При компенсации сдвига используется bilinear warpAffine на рабочем размере.

Изменение inspect_scale, например 1.0→0.5, уменьшило бы обе стороны вдвое и число
пикселей вчетверо. Сейчас этого уменьшения в обычных профилях нет.

## Ветки UI, видео и архива

Настройки UI — `config/blocks/30-ui-and-client.yaml`. Производные изображения
создаются после решения отдельно от исходного кадра анализа.

| Назначение | Ограничение размеров | JPEG quality |
|---|---|---:|
| Основное превью инспекции | Ширина до 960, пропорции сохраняются | 45 |
| Карточка StatusCard | Ширина до 384, пропорции сохраняются | 30 |
| Live preview / MJPEG stream | client_preview_max_width=0: без уменьшения | 58 |
| frame_archive/frame.jpg | archive maxWidth=0: исходный размер SHM | 90, default кода |

Ширина всех текущих кадров меньше 960 px: основное UI-превью сейчас сохраняет размеры ROI,
но JPEG45 уменьшает качество. Карточки уменьшаются. Например, камера0:
исходный/архив/основное UI 780×588, карточка 384×289. Quality — параметр JPEG-кодека,
не процент сохранённых пикселей и не точный процент сохранённого качества.

Heatmap строится C++ с max_width=512 из эффективного C++ preset и дополнительно
уменьшается Java до ui_http.heatmap_preview_max_width=384. Архив camera0 подтвердил
384×290 (из-за округления после двух ресайзов), camera7 — 384×384.
Heatmap — карта аномалий, это не исходное фото; её масштаб не влияет на вердикт.

`UiArtifactsSidecar` → `UiHttpServer.writeInspectionJpegsFromBgrShm` создаёт
архив из того же исходного BGR, а не растягивает маленькое UI-превью обратно.
Размеры десяти реально сохранённых frame.jpg прочитаны PIL и совпали с таблицей.
Архивное «Проверить» идёт через JPEG /inspect-test-frame; в отличие от живого
кадра это уже изображение с потерями JPEG90. История эталонов/загрузка оператором
может иметь свой исходный JPEG; её качество этой проверкой не установлено.

## Источники кода

- `camera-worker/src/main.c`: hik_apply_frame_geometry, apply_capture_roi, Bayer→BGR.
- `InspectPythonExecutor`, `CppAnalisSurfaceHttpSupervisor`: legacy scale и body.settings.
- `cppAnalisSurface/src/core/reference.cpp`, `inspector.cpp`: gray/blur/scale/миниатюра.
- `UiArtifactsSidecar`, `UiHttpServer`, `HeatmapU8PreviewScaler`: производные UI/архив.
- `CameraStreamService`, `LivePreviewPublisher`: клиентское видео/preview.

Тестовые наборы не запускались: код и параметры не менялись; проверка read-only.

## Повторная трассировка конфигурации и вызовов

Повторно проверено на HEAD `0b6165e8` (`boobs+pasha`). Реальный
`YamlFileConfigLoader` из собранного JAR прочитал `config/config.yaml` со всеми
imports. `IntegrationFeatureConfig.parseCaptureFrameDownscale` вернул enabled=false,
scale=1.0 и **все три apply-флага false**. `ChildProcessStartupService` при этом
не создаёт `CaptureFrameDownscaleService` (передаёт null), а
`WorkerCaptureCoordinator` возвращает исходные SHM-дескрипторы. Наличие true
в исходном YAML у apply-флагов само по себе ничего не уменьшает.
Проба загрузчика: `/tmp/TraceCoreDownscale.java`, без запуска приложения/камер.

Тот же загрузчик подтвердил backend=cpp, legacy inspect_scale 0.7 для камер
0/1/3/4/5/6/8/9 и 0.2 для 2/7, heatmap detector=512 и UI=384.
Цепочка legacy scale: `InspectionScaleConfig.resolve` →
`InspectPythonExecutor` → top-level header.inspect_scale →
`CppAnalisSurfaceHttpSupervisor.shmFrameBody`/`ephemeralSettings` (ключ не копируется).
У `PythonInspectDownscaleSupport` нет вызовов в production Java-коде — само
наличие класса не доказывает работу дополнительного ресайза SHM.
Повторный GET настроек 10/10 камер и профилей подтвердил прежнюю таблицу ROI,
BayerRG8 и settings.inspect_scale=1.0. Снимок:
`/tmp/core-dev-downscale-runtime-current.json`. Запросы только GET.

Legacy scale при этом не полностью неиспользуемый: `ClientWsConfig` читает
общее 0.7; `ReferenceBundleWsHandler` и `FpZonesUpdateWsHandler` умножают на него
размеры **пространства координат heatmap/FP-зон**. Они не ресайзят пиксели frame
и не учитывают inspect_scale_by_camera. Поэтому нельзя ни считать этот ключ
рабочим масштабом C++-кадра, ни объявлять его безопасно удаляемым. Правильность
координат FP-зон этой проверкой не испытывалась.

У аппаратной цепочки есть ещё два существенных условия:

- Native читает frame из `config/config.json` (путь по умолчанию в
  `CameraWorkerPaths`), затем `hik_apply_frame_geometry` **пропускает
  установку binning**, если Width/Height уже совпали с целью 1224×1024.
- При несовпадении вызывается setter binning; переход к center ROI после него
  **не возвращает BinningHorizontal/Vertical в 1**. Лог `binning not effective`
  проверяет лишь несовпадение текущего Width/Height с целью. В сохранённом
  runtime-логе команды binning=2×2 приняты SDK, после них виден fallback ROI.
  Это не readback binning и не подтверждение его отключения.

После init_hik_mvs → init_roi_limits → apply_capture_roi native выставляет
сохранённые OffsetX/OffsetY/Width/Height: это аппаратная обрезка кадра.
Достоверного readback binning по-прежнему нет; повторно открывать камеры,
останавливать линию или отбирать доступ не пытались.

Дополнительные cv::resize, не являющиеся глобальным уменьшением живого кадра:

- `Service::loadTestFrame`: если тестовый файл отличается по размерам от эталона,
  подгоняет его к размеру эталона INTER_LINEAR; возможно уменьшение или увеличение.
- `Service::addLearnedRegion`: переводит bbox из пространства исходного кадра
  в пространство эталона и подгоняет crop к этому bbox INTER_LINEAR.
- `learning_matcher.cpp` и legacy-ветка inspector: приводят сохранённый crop
  дообучения к рабочему bbox с учётом inspect_scale, INTER_AREA; при scale=1
  и совпадающих размерах это не дополнительное уменьшение.

Изменены только заметки знаний; рабочие конфиги, код и сервис не менялись.
Тестовые наборы не запускались: выполнены чтение конфигурации производственным
загрузчиком, трассировка исходников и GET текущих настроек.
