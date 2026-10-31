# Run xrime's Gradle wrapper with tools, user caches and temporary files on D:.
$ErrorActionPreference = 'Stop'
$toolsRoot = 'D:\xrime-tools'
$env:JAVA_HOME = Join-Path $toolsRoot 'jdk-17'
$env:ANDROID_HOME = Join-Path $toolsRoot 'android-sdk'
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
$pythonRoot = Join-Path $toolsRoot 'python-3.13'
$env:PATH = "$pythonRoot;$(Join-Path $env:ANDROID_HOME 'cmake\3.31.6\bin');$(Join-Path $env:ANDROID_HOME 'platform-tools');$env:PATH"
$env:GRADLE_USER_HOME = Join-Path $toolsRoot 'gradle'
$env:ANDROID_USER_HOME = Join-Path $toolsRoot 'android-user'
$env:ANDROID_AVD_HOME = Join-Path $toolsRoot 'avd'
$env:LOCALAPPDATA = Join-Path $toolsRoot 'localappdata'
$env:APPDATA = Join-Path $toolsRoot 'appdata'
$env:TRIME_BOOST_ARCHIVE = Join-Path $toolsRoot 'boost-1.92.0.tar.xz'
$env:TRIME_BOOST_TREE = Join-Path $toolsRoot 'boost-1.92.0'
if (Test-Path -LiteralPath (Join-Path $pythonRoot 'python.exe')) {
    $env:TRIME_OPENCC_PYTHON = Join-Path $pythonRoot 'python.exe'
}
$zigRoot = Join-Path $toolsRoot 'zig-fast\zig-x86_64-windows-0.15.2'
if (Test-Path -LiteralPath (Join-Path $zigRoot 'zig.exe')) {
    $env:TRIME_OPENCC_ZIG_ROOT = $zigRoot
    $env:ZIG_GLOBAL_CACHE_DIR = Join-Path $toolsRoot 'zig-cache'
    $env:ZIG_LOCAL_CACHE_DIR = Join-Path $toolsRoot 'zig-local'
}
$toolUserHome = Join-Path $toolsRoot 'home'
$env:TEMP = Join-Path $toolsRoot 'temp'
$env:TMP = $env:TEMP
$env:PYTHONUTF8 = '1'
if (!$env:CMAKE_BUILD_PARALLEL_LEVEL) {
    $env:CMAKE_BUILD_PARALLEL_LEVEL = '2'
}
foreach ($directory in @($env:GRADLE_USER_HOME, $env:ANDROID_USER_HOME, $env:ANDROID_AVD_HOME, $env:LOCALAPPDATA, $env:APPDATA, $toolUserHome, $env:TEMP)) {
    New-Item -ItemType Directory -Force -Path $directory | Out-Null
}
if (!(Test-Path -LiteralPath (Join-Path $env:JAVA_HOME 'bin\java.exe')) -or
    !(Test-Path -LiteralPath (Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe'))) {
    throw 'The xrime JDK or Android SDK is missing from D:\xrime-tools.'
}
$env:JAVA_TOOL_OPTIONS = "-Duser.home=$toolUserHome -Djava.io.tmpdir=$($env:TEMP)"
& (Join-Path $PSScriptRoot '..\gradlew.bat') @args
exit $LASTEXITCODE
