@echo off
chcp 65001 >nul
cd /d "%~dp0"
echo ============================================
echo   一键换 App 图标
echo ============================================
echo.
echo 用法：
echo   1. 把你的图片放到  图标\input.png
echo   2. 运行本脚本
echo   3. 运行  一键构建.bat  重新出包
echo.
if not exist "图标" mkdir "图标"
python 换图标.py
if errorlevel 1 (
  echo.
  echo Python 未找到，改用 py：
  py 换图标.py
)
echo.
pause
