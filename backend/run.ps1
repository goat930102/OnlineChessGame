$ErrorActionPreference = "Stop"

param(
    [int]$Port = 8080
)

$projectRoot = Split-Path $PSScriptRoot -Parent
$staticDir = Join-Path $projectRoot "frontend"
if (-not (Test-Path $staticDir)) {
    Write-Error "Static assets directory not found: $staticDir"
}

$outDir = Join-Path $PSScriptRoot "out"
if (Test-Path $outDir) {
    Remove-Item $outDir -Recurse -Force
}
New-Item -ItemType Directory -Path $outDir | Out-Null

$sourceDir = Join-Path $PSScriptRoot "src"
$sources = Get-ChildItem -Path $sourceDir -Recurse -Filter *.java | ForEach-Object { $_.FullName }

if (-not $sources) {
    Write-Error "No Java source files found under $sourceDir"
}

& javac --add-modules jdk.httpserver -d $outDir $sources
if ($LASTEXITCODE -ne 0) {
    Write-Error "Compilation failed"
}

$env:OCGP_PORT = $Port
$env:OCGP_STATIC_DIR = $staticDir

Write-Host "Server starting at http://localhost:$Port"
Write-Host "Press Ctrl+C to stop."

& java --add-modules jdk.httpserver -cp $outDir com.ocgp.server.Main
