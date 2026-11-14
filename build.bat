@echo off
REM 一键构建：把 JDK / Android SDK / Gradle 全部指向项目内 .toolchain，
REM 不需要管理员权限，也不修改系统环境变量。
setlocal
set "ROOT=%~dp0"
set "JAVA_HOME=%ROOT%.toolchain\jdk\jdk-17.0.21+9"
set "ANDROID_HOME=%ROOT%.toolchain\android-sdk"
set "ANDROID_SDK_ROOT=%ROOT%.toolchain\android-sdk"
set "ANDROID_USER_HOME=%ROOT%.toolchain\android-home"
set "GRADLE_USER_HOME=%ROOT%.toolchain\gradle-home"

if "%~1"=="" (
  set "TASK=:app:assembleDebug"
) else (
  set "TASK=%~1"
)

call "%ROOT%.toolchain\gradle\gradle-8.7\bin\gradle.bat" %TASK% --console=plain
exit /b %ERRORLEVEL%
