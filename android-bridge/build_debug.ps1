$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
$env:ANDROID_HOME = "C:\Users\track\AppData\Local\Android\Sdk"
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"

Set-Location "E:\repos\claude-raybans\android-bridge"
& .\gradlew.bat assembleDebug --stacktrace *>&1 | Tee-Object -FilePath "build_output.txt"
