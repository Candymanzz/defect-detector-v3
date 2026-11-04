# События камеры Line0 и ExposureStart — 2026-11-04

На femboy36 добавлено диагностическое логирование через существующий handle
камеры 0 в camera-worker JNI. Подписки MV_CC_RegisterEventCallBackEx на
Line0RisingEdge и ExposureStart приняты установленным SDK 4.8.1.2 (оба 0x0).
Новый MVS не устанавливался. EventSelector перечисляет оба события.

Физические события уже получены от станка. Срез наблюдения:
```json
{
  "counts": {
    "Line0RisingEdge": 23,
    "ExposureStart": 23
  },
  "pairs": 23,
  "line_to_exposure_us_min": 1.22,
  "line_to_exposure_us_max": 1.24,
  "queue_dropped_max": 0
}
```

Это интервал по часам камеры между фронтом её Line0 и началом экспозиции,
не интервал от исходного DI3. Между DI3 и камерой остаются Timer5/DO5 и проводка.
Потери SDK/сети данным счётчиком не измеряются: queue_dropped_max относится
только к нашей очереди. Частота device_timestamp_ticks — 100000000 Гц,
host_epoch_ns и host_monotonic_ns фиксируются при входе в callback;
шкалы камеры и ПК не объявляются синхронизированными. BlockId в наблюдении 0,
по нему связывать события и кадры нельзя.

Журнал: config/logs/camera-events-cam-0.jsonl относительно корня checkout.
Callback копирует данные в ограниченную очередь 256 записей через trylock;
запись и flush выполняет отдельный поток. Переполнение/занятость учитываются
в dropped_total. При закрытии восстанавливаются исходные EventNotification
и EventSelector; контекст освобождается после CloseDevice.

По умолчанию включена только камера 0. IML_CAMERA_EVENT_CAMERA=N выбирает
одну камеру при следующем запуске, -1 отключает диагностику. Настройки
TriggerSource/TriggerMode, Timer5, DO5, экспозиция и PixelFormat этим кодом
не изменяются. Если EventSelector/событие недоступны, ошибка диагностическая,
без отказа запуска захвата. События пока используются только для журнала.

Проверки: camera_jni собрана на станке. Синтетическая проверка очереди
подтвердила оба события, сборку 64-битных ticks, часы ПК, неблокирующий
callback при занятой очереди, учёт переполнения и выгрузку JSONL.
Новый тест подключён к CTest как cw_hik_events_tests.

Развёртывание: старая JNI сохранена в
/tmp/mvs-event-audit-20261104/libcamera_jni.before-events.so. Новая библиотека
опубликована заменой inode в camera-worker/build/libcamera_jni.so.
systemctl/pkexec оказался недоступен без аутентификации; Java backend
завершён SIGTERM, существующий StackSupervisorMain автоматически запустил
его заново (95746 -> 123272). Java /health и C++ /health вернули ok.
Подписки и реальные callbacks подтверждены после перезапуска.
Полный цикл отбраковки и время от DI3 этой проверкой не измерялись.
