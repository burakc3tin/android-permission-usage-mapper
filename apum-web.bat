@echo off
setlocal
cd /d "%~dp0"
if "%~1"=="" (
  call gradlew.bat --quiet --console=plain run --args="--serve"
) else (
  call gradlew.bat --quiet --console=plain run --args="--serve --port %~1"
)
endlocal
