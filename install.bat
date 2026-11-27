@echo off
setlocal DisableDelayedExpansion
if exist "%~dp0PZO-Setup.exe" (
    start "" "%~dp0PZO-Setup.exe" %*
    exit /b 0
)
if exist "%~dp0dist\PZO-Setup.exe" (
    start "" "%~dp0dist\PZO-Setup.exe" %*
    exit /b 0
)
echo PZO-Setup.exe is missing. Extract the Windows installer archive.
if "%~1"=="" pause
exit /b 1
