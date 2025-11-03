@echo off
setlocal

rem Optional first argument sets the port; default to 8080.
set "PORT=%~1"
if "%PORT%"=="" set "PORT=8080"

set "SCRIPT_DIR=%~dp0"
if "%SCRIPT_DIR:~-1%"=="\" set "SCRIPT_DIR=%SCRIPT_DIR:~0,-1%"

set "PROJECT_ROOT=%SCRIPT_DIR%\.."
set "STATIC_DIR=%PROJECT_ROOT%\frontend"
if not exist "%STATIC_DIR%" (
    echo [ERROR] Static assets directory not found: %STATIC_DIR%
    exit /b 1
)

set "SRC_DIR=%SCRIPT_DIR%\src"
if not exist "%SRC_DIR%" (
    echo [ERROR] Source directory not found: %SRC_DIR%
    exit /b 1
)

set "OUT_DIR=%SCRIPT_DIR%\out"
if exist "%OUT_DIR%" (
    rmdir /s /q "%OUT_DIR%"
)
mkdir "%OUT_DIR%" >nul

set "SOURCES_FILE=%OUT_DIR%\sources.lst"
dir /b /s "%SRC_DIR%\*.java" > "%SOURCES_FILE%"
for %%A in ("%SOURCES_FILE%") do if %%~zA==0 (
    echo [ERROR] No Java source files found under %SRC_DIR%
    exit /b 1
)

javac --add-modules jdk.httpserver -d "%OUT_DIR%" @"%SOURCES_FILE%"
if errorlevel 1 (
    echo [ERROR] Compilation failed.
    exit /b 1
)

set "OCGP_PORT=%PORT%"
set "OCGP_STATIC_DIR=%STATIC_DIR%"

echo Server starting at http://localhost:%PORT%
echo Press Ctrl+C to stop.
echo.

java --add-modules jdk.httpserver -cp "%OUT_DIR%" com.ocgp.server.Main

endlocal
