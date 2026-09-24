@echo off
setlocal
if not defined GRADLE_CMD set "GRADLE_CMD=gradle"
if not defined MAVEN_CMD set "MAVEN_CMD=mvn"
if not defined ANDROID_HOME if exist "%LOCALAPPDATA%\Android\Sdk" set "ANDROID_HOME=%LOCALAPPDATA%\Android\Sdk"
echo [1/3] Hazard contract
py -3 scripts\check_contracts.py || exit /b 1
echo [2/3] JVM and Android build
pushd android-app
call "%GRADLE_CMD%" :core:test :smoke-test:test :ort-adapter:build :app:testDebugUnitTest :app:assembleDebug || exit /b 1
popd
echo [3/3] ZholNet server tests
pushd zholnet-server
call "%MAVEN_CMD%" test || exit /b 1
popd
echo Stage 7 deterministic checks passed.
