@echo off
echo Compilando Bardo...
call mvnw.cmd clean package -q
if %ERRORLEVEL% neq 0 (
    echo.
    echo ERROR: La compilacion fallo.
    exit /b 1
)
copy /Y target\bardo.jar bardo.jar >nul
echo.
echo Listo: bardo.jar generado en la raiz del proyecto.
