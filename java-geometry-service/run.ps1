# Запуск java-geometry-service (stdio IMLB, как у оркестратора).
#   .\java-geometry-service\run.ps1

$ErrorActionPreference = "Stop"
. (Join-Path (Split-Path -Parent $PSScriptRoot) "scripts\windows-env.ps1")

$Jar = Join-Path $PSScriptRoot "target\java-geometry-service-0.1.0-SNAPSHOT.jar"
if (-not (Test-Path $Jar)) {
    throw "Нет $Jar`nСначала: .\java-geometry-service\setup.ps1"
}

$jdkHome = Resolve-JdkHome
$env:JAVA_HOME = $jdkHome
$env:Path = "$(Join-Path $jdkHome 'bin');$env:Path"

Write-Host "java-geometry-service → $Jar (stdio)" -ForegroundColor Green
Set-Location (Split-Path -Parent $PSScriptRoot)
java -jar $Jar
