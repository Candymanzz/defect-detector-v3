# Defect Detector launcher (GUI splash)

Сборка: `powershell -File launcher\build-exe.ps1`  
Результат: `DefectDetector.exe` в корне репозитория (WinForms, без консольного окна).

Запуск (двойной клик или консоль):

```text
.\DefectDetector.exe
.\DefectDetector.exe --no-frontend
.\DefectDetector.exe --no-supervisor
.\DefectDetector.exe --config config\config.yaml
```

После того как критичные сервисы поднялись, exe **отдельно** запускает crash-recovery supervisor
(`StackSupervisorMain --attach-pid …`). До запуска exe supervisor не работает и на систему не влияет.
При закрытии окна / «Остановить систему» supervisor останавливается вместе со стеком.

После **3 неудачных recovery** (health orchestrator + Python не восстанавливается) supervisor
планирует **перезагрузку Windows** (`shutdown /r`, задержка 90 с). Отключить:
`DefectDetector.exe --no-supervisor` или env `IML_SUPERVISOR_REBOOT_ENABLED=false`.
Для reboot нужны права администратора.

Окно лаунчера показывает:

- бренд и общий progress;
- список сервисов (окружение, оркестратор, analisSurface, LightServer, IoInputMonitor, Frontend, WS, рабочие процессы) и их статусы;
- после готовности критичных сервисов Electron/UI уже поднят оркестратором — браузер лаунчер не открывает;
- кнопка **Остановить систему** / закрытие окна гасит стек (как раньше Ctrl+C).

Лаунчер проверяет артефакты, чистит порты, стартует `java -jar orchestrator-…`, следит за health/TCP и логами.  
Перед этим нужны собранные JAR/DLL/worker/venv — см. `rebuild-and-run.ps1`.
