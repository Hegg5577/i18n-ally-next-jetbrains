@echo off
setlocal

set "JAVA_HOME=D:\IntelliJ IDEA 2026.1.4\jbr"

if not exist "%JAVA_HOME%\bin\java.exe" (
    echo [ERROR] Java not found: %JAVA_HOME%\bin\java.exe
    echo.
    echo Please modify JAVA_HOME in this script or install JetBrains IDE
    echo.
    pause
    exit /b 1
)

echo [OK] JAVA_HOME: %JAVA_HOME%
echo.
echo Running: gradlew.bat %*
echo.

call "%~dp0gradlew.bat" %*

endlocal
exit /b %errorlevel%
