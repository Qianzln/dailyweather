@echo off
set JAVA_HOME=D:\DEV\jdk-17
set GRADLE_USER_HOME=D:\DEV\.gradle_local
set ANDROID_HOME=D:\DEV\android-sdk
set TEMP=C:\Users\qiankun\AppData\Local\Temp
set TMP=C:\Users\qiankun\AppData\Local\Temp
cd /d "D:\DEV\dailyweather"
"D:\DEV\gradle\gradle-9.6.0\bin\gradle.bat" --no-daemon :app:assembleRelease
