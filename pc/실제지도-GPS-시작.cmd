@echo off
cd /d "%~dp0"
where node >nul 2>&1
if errorlevel 1 (
  echo Node.js 20 or newer is required. Install from https://nodejs.org/
  pause
  exit /b 1
)
echo Route Memory - real road map and GPS
echo Starting local server at http://127.0.0.1:4173
echo Keep this window open. Close it to stop the server.
if exist ".env.local" (
  start "RouteMemoryServer" /b node --env-file=.env.local serve.cjs
) else (
  start "RouteMemoryServer" /b node serve.cjs
)
timeout /t 2 /nobreak >nul
start "" "http://127.0.0.1:4173"
echo If the page does not open, visit http://127.0.0.1:4173
echo Press Ctrl+C or close this window when finished.
pause >nul
