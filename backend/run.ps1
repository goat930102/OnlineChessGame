param(
    [int]$Port = 8080
)

$ErrorActionPreference = "Stop"

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

$libDir = Join-Path $PSScriptRoot "lib"
if (-not (Test-Path $libDir)) {
    New-Item -ItemType Directory -Path $libDir | Out-Null
}
$sqliteJar = Join-Path $libDir "sqlite-jdbc.jar"
$wsJar = Join-Path $libDir "java-websocket.jar"
$slf4jJar = Join-Path $libDir "slf4j-simple.jar"
$slf4jApiJar = Join-Path $libDir "slf4j-api.jar"
if (-not (Test-Path $sqliteJar)) {
    Write-Host "Downloading SQLite JDBC driver..."
    Invoke-WebRequest -Uri "https://repo1.maven.org/maven2/org/xerial/sqlite-jdbc/3.45.1.0/sqlite-jdbc-3.45.1.0.jar" -OutFile $sqliteJar
}
if (-not (Test-Path $wsJar)) {
    Write-Host "Downloading WebSocket driver..."
    Invoke-WebRequest -Uri "https://repo1.maven.org/maven2/org/java-websocket/Java-WebSocket/1.5.6/Java-WebSocket-1.5.6.jar" -OutFile $wsJar
}
if (-not (Test-Path $slf4jJar)) {
    Write-Host "Downloading SLF4J simple logger..."
    Invoke-WebRequest -Uri "https://repo1.maven.org/maven2/org/slf4j/slf4j-simple/2.0.9/slf4j-simple-2.0.9.jar" -OutFile $slf4jJar
}
if (-not (Test-Path $slf4jApiJar)) {
    Write-Host "Downloading SLF4J API..."
    Invoke-WebRequest -Uri "https://repo1.maven.org/maven2/org/slf4j/slf4j-api/2.0.9/slf4j-api-2.0.9.jar" -OutFile $slf4jApiJar
}

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

$classpath = "$outDir;$sqliteJar;$wsJar;$slf4jJar;$slf4jApiJar"
& java --add-modules jdk.httpserver -cp $classpath com.ocgp.server.Main
