@echo off
chcp 65001 >nul
cd /d "%~dp0"
set PATH=D:\Android\Sdk\platform-tools;%PATH%
set PKG=com.example.phonemigration
set APK=D:\deepseek\work\一键搬家_正式版.apk

echo ============================================
echo   安装「一键搬家」并自动授权（免手动点）
echo ============================================

echo [1/4] 卸载旧版（避免签名冲突）...
adb uninstall %PKG% >nul 2>&1

echo [2/4] 安装正式版（弹窗请在手机上点「继续安装」）...
adb install "%APK%"
if errorlevel 1 goto FAIL

echo [3/4] 预授权运行时权限...
adb shell pm grant %PKG% android.permission.READ_MEDIA_IMAGES >nul 2>&1
adb shell pm grant %PKG% android.permission.READ_MEDIA_VIDEO >nul 2>&1
adb shell pm grant %PKG% android.permission.READ_EXTERNAL_STORAGE >nul 2>&1
adb shell pm grant %PKG% android.permission.WRITE_EXTERNAL_STORAGE >nul 2>&1
adb shell pm grant %PKG% android.permission.POST_NOTIFICATIONS >nul 2>&1
adb shell appops set %PKG% MANAGE_EXTERNAL_STORAGE allow >nul 2>&1
adb shell appops set %PKG% READ_EXTERNAL_STORAGE allow >nul 2>&1
adb shell appops set %PKG% WRITE_EXTERNAL_STORAGE allow >nul 2>&1

echo [4/4] 验证授权状态...
adb shell dumpsys package %PKG% | findstr "granted=true" | findstr /i "READ_MEDIA"

echo.
echo ===== 安装与授权完成 =====
echo 打开 App 直接点「一键打包」即可，无需再点任何授权。
goto END

:FAIL
echo.
echo ===== 安装失败，检查数据线与手机弹窗 =====

:END
echo.
pause
