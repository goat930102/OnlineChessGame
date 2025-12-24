@echo off
setlocal
setlocal EnableDelayedExpansion


rem Optional first argument sets the port; default to 8080.
set "PORT="
if /i "%~1"=="-Port" (
  set "PORT=%~2"
) else (
  set "PORT=%~1"
)
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

set "LIB_DIR=%SCRIPT_DIR%\lib"
set "SQLITE_JAR=%LIB_DIR%\\sqlite-jdbc.jar"
set "WS_JAR=%LIB_DIR%\\java-websocket.jar"
set "SLF4J_JAR=%LIB_DIR%\\slf4j-simple.jar"
set "SLF4J_API_JAR=%LIB_DIR%\\slf4j-api.jar"
if not exist "%LIB_DIR%" (
    mkdir "%LIB_DIR%" >nul
)
if not exist "%SQLITE_JAR%" (
    echo Downloading SQLite JDBC driver...
    powershell -Command "Invoke-WebRequest -Uri 'https://repo1.maven.org/maven2/org/xerial/sqlite-jdbc/3.45.1.0/sqlite-jdbc-3.45.1.0.jar' -OutFile '%SQLITE_JAR%'"
)
if not exist "%WS_JAR%" (
    echo Downloading WebSocket driver...
    powershell -Command "Invoke-WebRequest -Uri 'https://repo1.maven.org/maven2/org/java-websocket/Java-WebSocket/1.5.6/Java-WebSocket-1.5.6.jar' -OutFile '%WS_JAR%'"
)
if not exist "%SLF4J_JAR%" (
    echo Downloading SLF4J simple logger...
    powershell -Command "Invoke-WebRequest -Uri 'https://repo1.maven.org/maven2/org/slf4j/slf4j-simple/2.0.9/slf4j-simple-2.0.9.jar' -OutFile '%SLF4J_JAR%'"
)
if not exist "%SLF4J_API_JAR%" (
    echo Downloading SLF4J API...
    powershell -Command "Invoke-WebRequest -Uri 'https://repo1.maven.org/maven2/org/slf4j/slf4j-api/2.0.9/slf4j-api-2.0.9.jar' -OutFile '%SLF4J_API_JAR%'"
)

set "SOURCES_FILE=%OUT_DIR%\sources.lst"
> "%SOURCES_FILE%" (
  for /r "%SRC_DIR%" %%F in (*.java) do (
    set "F=%%~fF"
    call echo "!F:\=/!"
  )
)

set "JAVAC_CP=%SQLITE_JAR%;%WS_JAR%;%SLF4J_JAR%;%SLF4J_API_JAR%"
javac --add-modules jdk.httpserver -cp "%JAVAC_CP%" -d "%OUT_DIR%" @"%SOURCES_FILE%"
if errorlevel 1 (
    echo [ERROR] Compilation failed.
    exit /b 1
)

set "OCGP_PORT=%PORT%"
set "OCGP_STATIC_DIR=%STATIC_DIR%"

echo Server starting at http://localhost:%PORT%
echo Press Ctrl+C to stop.
echo.

java --add-modules jdk.httpserver -cp "%OUT_DIR%;%SQLITE_JAR%;%WS_JAR%;%SLF4J_JAR%;%SLF4J_API_JAR%" com.ocgp.server.Main

endlocal
