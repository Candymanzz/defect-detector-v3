# Логи приложения

Каждый запуск создаёт сессию в `logs/`. Общий каталог передаётся через
`IML_LOG_SESSION_DIR` оркестратору, C++-анализатору и Electron.
Java сохраняет журналы запросов, ответов, конвейера, оборудования и supervisor;
Electron — HTTP/WebSocket, решения и ошибки интерфейса.
Изображения и бинарные тела запросов в HTTP-логи не записываются.

Подробные тайминги C++, камер, Java и выдачи решения в ПЛК описаны в
[PERFORMANCE_LOGGING.md](cppAnalisSurface/PERFORMANCE_LOGGING.md).

Старые каталоги сессий автоматически не удаляются. Логи и результаты сборки
исключены из Git.

Диагностика уже работающей Linux-системы:

```bash
bash ./run-rt.sh -r
bash ./rt-logs.sh --rt-interval 2
```

Сборщик создаёт отдельную сессию с `rt-samples.jsonl`, `rt-configuration.jsonl`,
`rt-threads.jsonl`, `rt-kernel.jsonl`, `rt-events.jsonl` и `rt-monitor.log`.
Он не запускает и не останавливает сервисы. Ctrl+C останавливает сбор метрик.
Подробности: [RT-LINUX.md](RT-LINUX.md).
