# Камеры внутри оркестратора через JNI

Оркестратор загружает `camera_jni` и открывает каждую камеру внутри JVM.
Отдельные процессы `camera_worker` для рабочего конвейера больше не запускаются.
Захват MVS/Aravis, GigE-настройки, аппаратный Line0, настройки камеры и поток
используют существующий C-код. Кадры BGR8 сохраняются в прежнем кольце SHM
из восьми слотов; сервисы анализа продолжают читать его по имени и смещению.

## Сборка

Нужны 64-битный JDK 17+, CMake и C-компилятор. Для Hikvision нужен MVS SDK.
`JAVA_HOME` должен указывать на JDK с каталогом `include`, `MVS_ROOT` — на SDK.

```bash
cmake -S camera-worker -B camera-worker/build -DCMAKE_BUILD_TYPE=Release -DCAMERA_BUILD_JNI=ON
cmake --build camera-worker/build --config Release --target camera_jni
```

Linux: `camera-worker/build/libcamera_jni.so`, Windows с Visual Studio:
`camera-worker/build/Release/camera_jni.dll`. Windows DLL MVS копируются рядом.
Linux использует SDK в `/opt/MVS` или заданном `MVS_ROOT`.
`rebuild-and-run.sh` собирает JNI и атомарно заменяет библиотеку в `camera-worker/build`.
Для загрузки новой библиотеки требуется перезапуск JVM.

Расположение можно задать через `integration.camera_native_library` в YAML
или `-Diml.camera.native.library=/absolute/path/to/library`. По умолчанию
ищутся стандартные каталоги CMake `build`, `build/Release`, `build/RelWithDebInfo`,
`build/Debug`, `build-linux`. JSON камер по-прежнему задаёт
`integration.worker_config_json` (по умолчанию `config/config.json`).
`worker_command_timeout_ms` и `worker_startup_stagger_ms` сохраняют назначение.
Старые `worker_ipc_mode` и `worker_named_pipe_*` не влияют на рабочий путь JNI.

## Владение ресурсами

`NativeCameraClient` владеет нативным состоянием одной камеры. Команды
последовательны внутри камеры; разные камеры захватывают параллельно.
Открытие и перечисление устройств используют общий `MvsSdkLease.ENUMERATION_LOCK`.
Срок жизни SDK разделяется через `MvsSdkLease` с IO и светом по пути реально
загруженной библиотеки MVS. Закрытие одной камеры не финализирует общий SDK,
пока другие устройства держат lease.

При закрытии или переподключении сначала устанавливается нативный флаг отмены.
Ожидание кадра MVS проверяет его между вызовами SDK с таймаутом 20 мс.
После завершения текущего вызова останавливается поток и освобождаются
камера, SHM и SDK lease. Аппаратное окно захвата проверяется только для
`wait_frame`; обычный видеопоток не зависит от токена последней инспекции.

Команды `inject_*` доступны только отдельному диагностическому executable;
JNI возвращает для них `unknown_op`. Бинарник можно отдельно собрать целью
`camera_worker`; для сборки без JDK укажите `-DCAMERA_BUILD_JNI=OFF`.
Сбой нативной библиотеки теперь относится ко всей JVM; её перезапуск остаётся
за внешним supervisor. Ошибки команд и переподключение отдельной камеры
обрабатываются существующим `WorkerProcessSupervisor` через JNI.

## Проверка без оборудования

`run-all-tests.sh` при наличии CMake сначала собирает библиотеку без MVS/Aravis,
затем передаёт её Java-тестам. Для отдельного прогона:

```bash
cmake -S camera-worker -B camera-worker/build-test -DCMAKE_BUILD_TYPE=Debug \
  -DCAMERA_ENABLE_MVS=OFF -DCAMERA_ENABLE_ARAVIS=OFF
cmake --build camera-worker/build-test --target camera_jni cw_config_tests
ctest --test-dir camera-worker/build-test --output-on-failure
cd orchestrator-java
mvn -Diml.camera.native.library="$PWD/../camera-worker/build-test/libcamera_jni.so" \
  -Dtest=NativeCameraClientTest test
```

Тесты проверяют JNI-вызовы, содержимое SHM и переход кольца через восемь кадров,
настройки, поток, параллельные камеры, закрытие/повторное открытие,
перезапуск supervisor и bootstrap без worker executable.
Реальные аппаратные триггеры, камеры и задержки требуют проверки на целевом ПК.
