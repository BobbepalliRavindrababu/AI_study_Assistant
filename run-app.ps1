# Helper script to start backend and frontend in separate terminal windows

Write-Host "Starting AI StudyRAG Backend..." -ForegroundColor Magenta
Start-Process powershell -ArgumentList "-NoExit", "-Command", "cd C:\Users\91934\.gemini\antigravity\scratch\study-assistant\backend; Write-Host 'Booting Java Backend on Port 8085...' -ForegroundColor Cyan; .\gradlew :study-assistant-app:run"

Write-Host "Starting Vite React Frontend..." -ForegroundColor Green
Start-Process powershell -ArgumentList "-NoExit", "-Command", "cd C:\Users\91934\.gemini\antigravity\scratch\study-assistant\frontend; Write-Host 'Booting React Frontend Dev Server...' -ForegroundColor Cyan; npm run dev"

Write-Host "Both processes launched in separate windows!" -ForegroundColor Yellow
