# Defect Detector launcher (GUI splash)

Сборка: `powershell -File launcher\build-exe.ps1`  
Результат: `DefectDetector.exe` в корне репозитория (WinForms, без консольного окна).

Запуск (двойной клик или консоль):

```text
.\DefectDetector.exe
.\DefectDetector.exe --no-frontend
.\DefectDetector.exe --config config\config.yaml
```

Окно лаунчера показывает:

- бренд и общий progress;
- список сервисов (окружение, оркестратор, analisSurface, LightServer, IoInputMonitor, Frontend, WS, рабочие процессы) и их статусы;
- после готовности критичных сервисов открывает UI (`http://localhost:5173`);
- кнопка **Остановить систему** / закрытие окна гасит стек (как раньше Ctrl+C).

Лаунчер проверяет артефакты, чистит порты, стартует `java -jar orchestrator-…`, следит за health/TCP и логами.  
Перед этим нужны собранные JAR/DLL/worker/venv — см. `rebuild-and-run.ps1`.
