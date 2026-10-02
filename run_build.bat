@echo off
rem DailyWeather - build entry point.
rem NOTE: keep this file GBK-encoded (cmd parses .bat on the system codepage).
rem ADR-009 (inherited from wui): builds MUST run from the pure-ASCII path.
rem The Chinese path (...天气\每日天气) is only a junction pointing here;
rem building through it re-introduces the @argfile/GBK path-corruption issue.
setlocal
set JAVA_HOME=D:\DEV\jdk-17
set GRADLE_USER_HOME=D:\DEV\.gradle
set ANDROID_HOME=D:\DEV\android-sdk
cd /d "D:\DEV\dailyweather"
"D:\DEV\gradle\gradle-9.6.0\bin\gradle.bat" %*
exit /b %ERRORLEVEL%
