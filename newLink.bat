@echo off
set ep=E:\Works\eclipse\SairFramework\bin
set sp=E:\SairFramework

mklink /j %ep%\data %sp%\data
mklink /j %ep%\plugins %sp%\plugins

pause