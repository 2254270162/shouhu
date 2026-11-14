# 一键构建脚本：把 Java / Gradle / Android SDK 全部指向项目内 .toolchain，
# 不修改系统环境变量，也不需要管理员权限。
param(
    [string]$Task = "assembleDebug"
)

$root = $PSScriptRoot
$env:JAVA_HOME          = Join-Path $root ".toolchain\jdk\jdk-17.0.21+9"
$env:ANDROID_HOME       = Join-Path $root ".toolchain\android-sdk"
$env:ANDROID_SDK_ROOT   = $env:ANDROID_HOME
$env:ANDROID_USER_HOME  = Join-Path $root ".toolchain\android-home"
$env:GRADLE_USER_HOME   = Join-Path $root ".toolchain\gradle-home"
$env:JAVA_TOOL_OPTIONS  = ""

New-Item -ItemType Directory -Force -Path $env:ANDROID_USER_HOME | Out-Null

& (Join-Path $root ".toolchain\gradle\gradle-8.7\bin\gradle.bat") $Task --console=plain
exit $LASTEXITCODE
