@echo off
setlocal
cd /d "%~dp0"
title Studyroom - May Chu (Server)
powershell -ExecutionPolicy Bypass -Command "$env:STUDYROOM_DB_URL='jdbc:postgresql://localhost:5432/studyroom'; & '%~dp0run.ps1'"
