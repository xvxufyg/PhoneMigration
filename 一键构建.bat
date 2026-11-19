@echo off
chcp 65001 >nul
cd /d "%~dp0"
echo ============================================
echo   一键构建「一键搬家」正式 APK
echo ============================================
set JAVA_HOME=C:\Users\14909\AppData\Local\Programs\dsh-tools\jdk21
set ANDROID_HOME=D:\Android\Sdk
set PATH=%JAVA_HOME%\bin;D:\Android\gradle-8.7\bin;%PATH%

echo [1/3] 构建 release APK ...
gradle assembleRelease --no-daemon
if errorlevel 1 goto FAIL

echo [2/3] 验签 ...
D:\Android\Sdk\build-tools\34.0.0\apksigner.bat verify --print-certs app\build\outputs\apk\release\app-release.apk
if errorlevel 1 goto FAIL

echo [3/3] 复制到交付目录 ...
copy /Y app\build\outputs\apk\release\app-release.apk D:\deepseek\work\一键搬家_正式版.apk
if errorlevel 1 goto FAIL

echo.
echo ===== 构建完成 =====
echo 产物: D:\deepseek\work\一键搬家_正式版.apk
for %%F in ("app\build\outputs\apk\release\app-release.apk") do echo 大小: %%~zF 字节
goto END

:FAIL
echo.
echo ===== 构建失败，看上面报错 =====

:END
echo.
pause
