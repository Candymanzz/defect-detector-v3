# MVS SDK на Linux

Установите SDK производителя в `/opt/MVS`.
Камеры используют JNI-библиотеку из `camera-worker/`, свет и DI/DO — JNA
внутри оркестратора. Библиотека SDK: `/opt/MVS/lib/64/libMvCameraControl.so`.

Каталог SDK при сборке JNI переопределяется переменной `MVS_ROOT`.
Windows DLL в репозитории не хранятся.
