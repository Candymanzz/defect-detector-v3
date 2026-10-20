# Helper for run.ps1 / rebuild-and-run.ps1: which surface detector does the config select?
# python_detector.backend: cpp  -> cppAnalisSurface (C++, no Python needed)
# anything else / missing       -> Python analisSurface (previous behaviour)
# The config may be an aggregator (config.yaml with "imports:"), so the imported blocks are searched too.

function Get-ConfigFiles([string]$ConfigPath) {
    $files = @($ConfigPath)
    if (-not (Test-Path $ConfigPath)) { return @() }
    $dir = Split-Path -Parent $ConfigPath
    $inImports = $false
    foreach ($line in Get-Content -Path $ConfigPath) {
        if ($line -match '^imports:\s*$') { $inImports = $true; continue }
        if ($inImports) {
            if ($line -match '^\s*-\s*["'']?([^"''#\s]+)["'']?') {
                $block = Join-Path $dir $Matches[1]
                if (Test-Path $block) { $files += $block }
            } elseif ($line -match '^\S') { $inImports = $false }
        }
    }
    return $files
}

function Test-CppDetectorBackend([string]$ConfigPath) {
    foreach ($file in (Get-ConfigFiles $ConfigPath)) {
        $inBlock = $false
        foreach ($line in Get-Content -Path $file) {
            if ($line -match '^python_detector:\s*(#.*)?$') { $inBlock = $true; continue }
            if ($inBlock) {
                if ($line -match '^\S' -and $line -notmatch '^\s*#') { $inBlock = $false; continue }
                if ($line -match '^\s+backend:\s*["'']?cpp["'']?\s*(#.*)?$') { return $true }
            }
        }
    }
    return $false
}
