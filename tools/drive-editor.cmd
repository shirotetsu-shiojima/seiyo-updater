@echo off
java -jar "%~dp0..\release\seiyo-tools.jar" editor "%~dp0seiyo-catalog-generator.html"
if errorlevel 1 pause
