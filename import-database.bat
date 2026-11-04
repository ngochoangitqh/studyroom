@echo off
setlocal
cd /d "%~dp0"
title Khoi phuc Database PostgreSQL - Studyroom
color 0e

echo ==========================================================
echo        KHOI PHUC / NHAP DATABASE POSTGRESQL (STUDYROOM)    
echo ==========================================================
echo.

set PGPASSWORD=ngochoang
set PSQL="C:\Program Files\PostgreSQL\18\bin\psql.exe"

if not exist %PSQL% (
    echo [LOI] Khong tim thay psql.exe tai C:\Program Files\PostgreSQL\18\bin\psql.exe
    pause
    exit /b 1
)

if not exist "%~dp0studyroom_backup.sql" (
    echo [LOI] Khong tim thay file studyroom_backup.sql!
    pause
    exit /b 1
)

echo Dang khoi tao database neu chua co...
%PSQL% -U postgres -h localhost -p 5432 -c "CREATE DATABASE studyroom;" >nul 2>&1

echo Dang nap du lieu tu studyroom_backup.sql vao database...
%PSQL% -U postgres -h localhost -p 5432 -d studyroom -f "%~dp0studyroom_backup.sql"

if %errorlevel% neq 0 (
    echo [LOI] Nap database that bai! Vui long kiem tra lai.
    pause
    exit /b %errorlevel%
)

echo.
echo [THANH CONG] Da nap toan bo Database thanh cong!
echo ==========================================================
pause
