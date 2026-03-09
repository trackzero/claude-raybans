@echo off
set "JAVA_HOME=C:\Program Files\Android\Android Studio\jbr"
set "ANDROID_HOME=C:\Users\track\AppData\Local\Android\Sdk"
set "PATH=%JAVA_HOME%\bin;%PATH%"
cd /d E:\repos\claude-raybans\android-bridge
call gradlew.bat assembleDebug --stacktrace > build_output.txt 2>&1
echo Exit code: %ERRORLEVEL% >> build_output.txt
