@echo off
setlocal
cd /d "%~dp0"
title Xuat Database PostgreSQL - Studyroom
color 0a

echo ==========================================================
echo           DANG XUAT DATABASE POSTGRESQL (STUDYROOM)       
echo ==========================================================
echo.

set PGPASSWORD=ngochoang
set PGDUMP="C:\Program Files\PostgreSQL\18\bin\pg_dump.exe"

if not exist %PGDUMP% (
    echo [LOI] Khong tim thay pg_dump.exe tai C:\Program Files\PostgreSQL\18\bin\pg_dump.exe
    pause
    exit /b 1
)

echo Dang sao luu toan bo du lieu ra file studyroom_backup.sql...
%PGDUMP% -U postgres -h localhost -p 5432 -d studyroom --clean --if-exists --inserts -f "%~dp0studyroom_backup.sql"

if %errorlevel% neq 0 (
    echo [LOI] Xuat database that bai! Vui long kiem tra lai PostgreSQL.
    pause
    exit /b %errorlevel%
)

echo.
echo [THANH CONG] Da xuat toan bo Database ra:
echo   - %~dp0studyroom_backup.sql
echo.
echo ==========================================================
pause
