@echo off
:: Self-elevate to Administrator
net session >nul 2>&1
if %errorLevel% neq 0 (
    echo Dang yeu cau quyen Administrator de mo cong firewall va khoi dong lai PostgreSQL...
    powershell -Command "Start-Process cmd -ArgumentList '/c \"%~dpnx0\"' -Verb RunAs"
    exit /b
)

title Cau hinh Studyroom cho phep may khac ket noi
color 0a
cls
echo ==========================================================
echo    DANG CAU HINH STUDYROOM DE MAY KHAC TRUY CAP TRONG LAN
echo ==========================================================
echo.

echo 1. Mo cong Windows Firewall (5432 - DB, 5050 - Chat, 5100 - Voice)...
netsh advfirewall firewall delete rule name="Studyroom PostgreSQL" >nul 2>&1
netsh advfirewall firewall add rule name="Studyroom PostgreSQL" dir=in action=allow protocol=TCP localport=5432 >nul 2>&1

netsh advfirewall firewall delete rule name="Studyroom Chat P2P" >nul 2>&1
netsh advfirewall firewall add rule name="Studyroom Chat P2P" dir=in action=allow protocol=TCP localport=5050 >nul 2>&1

netsh advfirewall firewall delete rule name="Studyroom Voice Calling" >nul 2>&1
netsh advfirewall firewall add rule name="Studyroom Voice Calling" dir=in action=allow protocol=UDP localport=5100 >nul 2>&1

echo [OK] Da mo thanh cong cac cong Firewall!
echo.

echo 2. Khoi dong lai dich vu PostgreSQL...
powershell -Command "Restart-Service -Name '*postgres*' -Force -ErrorAction SilentlyContinue"
echo [OK] Da khoi dong lai PostgreSQL thanh cong!
echo.

echo ==========================================================
echo                   HOAN TAT CAU HINH!
echo ==========================================================
echo Dia chi IP may ban la: 192.168.1.14
echo.
echo Hay bao ban cua ban (o may khac, cung mang Wi-Fi) go lenh:
echo.
echo   git clone https://github.com/ngochoangitqh/studyroom.git
echo   cd studyroom
echo   powershell -Command "$env:STUDYROOM_DB_URL='jdbc:postgresql://192.168.1.14:5432/studyroom'; $env:STUDYROOM_DB_USER='postgres'; .\run.bat"
echo.
echo ==========================================================
pause
