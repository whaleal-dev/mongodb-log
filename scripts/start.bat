@echo off
setlocal
set "SCRIPT_DIR=%~dp0"
set "APP_DIR=%SCRIPT_DIR%.."
set "JAR_FILE=%APP_DIR%\mongodb-log-analyzer.jar"
if not exist "%JAR_FILE%" set "JAR_FILE=%APP_DIR%\target\mongodb-log-analyzer.jar"

where java >nul 2>nul || goto :no_java
for /f tokens^=2^ delims^=^" %%v in ('java -version 2^>^&1') do set "JAVA_VERSION_TEXT=%%v" & goto :version_found
:version_found
for /f "tokens=1 delims=." %%v in ("%JAVA_VERSION_TEXT%") do set "JAVA_VERSION=%%v"
if not defined JAVA_VERSION goto :bad_version
if %JAVA_VERSION% LSS 17 goto :bad_version
if not exist "%JAR_FILE%" goto :no_jar

cd /d "%APP_DIR%"
java -Xms128m -Xmx2g -jar "%JAR_FILE%"
if errorlevel 1 goto :runtime_error
exit /b 0

:no_java
echo 启动失败：未找到 Java，请安装 Java 17 或更高版本。
goto :pause_error
:bad_version
echo 启动失败：Java 版本必须为 17 或更高版本。
goto :pause_error
:no_jar
echo 启动失败：未找到 mongodb-log-analyzer.jar，请使用发布包或先执行 mvn package。
goto :pause_error
:runtime_error
echo 程序异常退出，请查看上方错误信息。
:pause_error
pause
exit /b 1
