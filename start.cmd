@echo off
REM Double-click to start Handsoff (app + relay). Close this window to stop it.
cd /d "%~dp0"
if not exist "web\.next\BUILD_ID" (
  echo Building the app for the first time...
  node web\node_modules\next\dist\bin\next build web
)
node scripts\run.js prod
echo.
echo Handsoff stopped.
pause
