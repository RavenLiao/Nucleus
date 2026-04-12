@echo off
REM Compiles the Windows native implementation for nucleus_system_info.
REM Requires Visual Studio build tools (cl.exe) and JDK with JNI headers.

setlocal

set SCRIPT_DIR=%~dp0
set RESOURCE_DIR=%SCRIPT_DIR%..\..\resources\nucleus\native
set OUT_DIR_X64=%RESOURCE_DIR%\win32-x64
set OUT_DIR_ARM64=%RESOURCE_DIR%\win32-aarch64

if not defined JAVA_HOME (
    echo ERROR: JAVA_HOME not set. >&2
    exit /b 1
)

set JNI_INCLUDE=%JAVA_HOME%\include
set JNI_INCLUDE_WIN=%JAVA_HOME%\include\win32

if not exist "%OUT_DIR_X64%" mkdir "%OUT_DIR_X64%"
if not exist "%OUT_DIR_ARM64%" mkdir "%OUT_DIR_ARM64%"

REM Compile all C source files into a single DLL
cl /nologo /LD /O2 /W3 /D_CRT_SECURE_NO_WARNINGS ^
    /I"%JNI_INCLUDE%" /I"%JNI_INCLUDE_WIN%" ^
    "%SCRIPT_DIR%nucleus_system_info_os.c" ^
    "%SCRIPT_DIR%nucleus_system_info_memory.c" ^
    "%SCRIPT_DIR%nucleus_system_info_cpu.c" ^
    "%SCRIPT_DIR%nucleus_system_info_disk.c" ^
    "%SCRIPT_DIR%nucleus_system_info_component.c" ^
    "%SCRIPT_DIR%nucleus_system_info_network.c" ^
    "%SCRIPT_DIR%nucleus_system_info_process.c" ^
    "%SCRIPT_DIR%nucleus_system_info_user.c" ^
    "%SCRIPT_DIR%nucleus_system_info_hardware.c" ^
    "%SCRIPT_DIR%nucleus_system_info_gpu.c" ^
    /Fe"%OUT_DIR_X64%\nucleus_system_info.dll" ^
    /link /DLL ^
    kernel32.lib advapi32.lib psapi.lib iphlpapi.lib ole32.lib oleaut32.lib wbemuuid.lib netapi32.lib powrprof.lib ws2_32.lib dxgi.lib

if %ERRORLEVEL% NEQ 0 (
    echo ERROR: Compilation failed. >&2
    exit /b 1
)

echo Built Windows system-info DLL (x64)

REM Clean up intermediate files
del /q "%SCRIPT_DIR%*.obj" 2>nul
del /q "%OUT_DIR_X64%\nucleus_system_info.lib" 2>nul
del /q "%OUT_DIR_X64%\nucleus_system_info.exp" 2>nul

REM ARM64 cross-compilation requires the ARM64 cl.exe toolchain
REM For now, copy x64 as placeholder if ARM64 toolchain is not available
if not exist "%OUT_DIR_ARM64%\nucleus_system_info.dll" (
    copy "%OUT_DIR_X64%\nucleus_system_info.dll" "%OUT_DIR_ARM64%\nucleus_system_info.dll" >nul 2>&1
)

REM Clear NativeLibraryLoader cache
if exist "%USERPROFILE%\.cache\nucleus\native" (
    rd /s /q "%USERPROFILE%\.cache\nucleus\native" 2>nul
    echo Cleared NativeLibraryLoader cache.
)

endlocal
