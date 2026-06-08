@echo off
chcp 65001 >nul
echo ============================================
echo  智能音箱 — 一键推送到 GitHub
echo ============================================
echo.
echo 第一步：请先打开 https://github.com/new
echo         创建一个新仓库（Repo），名字随意
echo         创建后复制仓库地址，如：
echo         https://github.com/你的用户名/SmartSpeaker.git
echo.
set /p REPO_URL="粘贴仓库地址: "

echo.
echo 第二步：请输入 GitHub 用户名
set /p GH_USER="用户名: "

echo.
echo 第三步：请输入 Personal Access Token
echo （在 github.com/settings/tokens 创建，勾选 repo 权限）
echo 或者直接用密码（如果还没关闭密码认证）
echo.
set /p GH_TOKEN="Token/密码: "

echo.
echo 🚀 推送到 GitHub...
git -C "%~dp0" remote add origin %REPO_URL%
git -C "%~dp0" remote set-url origin https://%GH_USER%:%GH_TOKEN%@%REPO_URL:https://=%
git -C "%~dp0" push -u origin master

echo.
if %errorlevel% equ 0 (
    echo ✅ 推送成功！
    echo.
    echo 第四步：去 GitHub 仓库页面 → Actions 标签
    echo         等待 3-5 分钟 → 下载 APK
) else (
    echo ❌ 推送失败，请检查用户名/Token是否正确
)

pause
