@echo off
setlocal
call "C:\Program Files\Microsoft Visual Studio\18\Community\VC\Auxiliary\Build\vcvars64.bat"
if errorlevel 1 exit /b 1
cd /d "%~dp0native"
cl.exe /O2 /LD /W3 /arch:AVX2 /I"C:\Program Files\Java\jdk-26.0.2.1\include" /I"C:\Program Files\Java\jdk-26.0.2.1\include\win32" pzo_native.c /Fe:pzo_native64.dll /link /MACHINE:X64 winmm.lib avrt.lib
if errorlevel 1 exit /b 1
echo [PZO Native Build] Successfully compiled pzo_native64.dll

if not exist "%~dp0dist" mkdir "%~dp0dist"
copy /y "%~dp0native\pzo_native64.dll" "%~dp0dist\pzo_native64.dll" >nul
echo [PZO Native Build] Successfully deployed to dist\pzo_native64.dll
