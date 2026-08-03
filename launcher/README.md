# Defect Detector launcher

Сборка: `powershell -File launcher\build-exe.ps1`  
Результат: `DefectDetector.exe` в корне репозитория.

Запуск (двойной клик или консоль):

```text
.\DefectDetector.exe
.\DefectDetector.exe --no-frontend
.\DefectDetector.exe --config config\config.yaml
```

Лаунчер проверяет артефакты, чистит порты, стартует `java -jar orchestrator-…`, а по Ctrl+C / выходу гасит стек.
Перед этим нужны собранные JAR/DLL/worker/venv — см. `rebuild-and-run.ps1`.
