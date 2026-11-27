@echo off
rem 双击即可：提交当前目录所有改动并推送，触发 GitHub Actions 构建安卓包。
rem 想自定义提交信息：push.bat 修复勾选状态不同步
rem 只看不推：push.bat --dry-run

chcp 65001 >nul
setlocal
cd /d "%~dp0"

where python >nul 2>nul
if errorlevel 1 (
  echo [error] 找不到 python，请先安装 Python 3 并加入 PATH
  pause
  exit /b 1
)

python "%~dp0tools\push.py" %*
set RC=%ERRORLEVEL%

echo.
if not "%RC%"=="0" (
  echo [error] 退出码 %RC%
) else (
  echo [ok] 完成。构建结果请在上面的 Actions 链接查看。
)

rem 双击运行时保留窗口，方便看错误；从命令行调用则不打扰
echo %CMDCMDLINE% | find /i "/c" >nul
if not errorlevel 1 pause
exit /b %RC%
