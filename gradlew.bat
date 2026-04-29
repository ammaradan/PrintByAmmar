@rem Gradle startup script for Windows
@if "%DEBUG%" == "" @echo off
setlocal
set CLASSPATH=%~dp0\gradle\wrapper\gradle-wrapper.jar
java -classpath "%CLASSPATH%" org.gradle.wrapper.GradleWrapperMain %*
