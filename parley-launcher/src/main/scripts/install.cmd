@echo off
rem Install parley-launcher from this folder (install.cmd is install.sh's Windows version):
rem
rem   PREFIX\bin\parley-launcher.cmd   the launcher; each program is a NAME.cmd that runs it for it
rem   PREFIX\lib                       parley-launcher.jar
rem   PREFIX\apps                      NAME.properties for each program (parley-launcher add)
rem   BIN\parley-launcher.cmd          runs it, and BIN\NAME.cmd for each program added
rem
rem PREFIX is %ProgramData%\Parley\launcher (BIN %ProgramData%\Parley\bin; needs an administrator's
rem prompt), or with --user %USERPROFILE%\.parley\launcher (BIN %USERPROFILE%\.local\bin). Then add
rem the programs, for example:
rem
rem   parley-launcher add fsh                      (picks the version; or fsh:2.0.0, us.bringardner:fsh:2.0.0)
rem
rem Usage: install.cmd [--user] [--prefix DIR] [--bin DIR] [--uninstall]
rem Running it again (from a newer one) upgrades: bin and lib are replaced, apps are kept.
rem --uninstall removes the launcher, its programs' commands and app files; the jars stay in the
rem repositories (they are Maven's too). BIN is not put on the PATH for you: the last line says so.

setlocal DisableDelayedExpansion
for %%i in ("%~dp0.") do set "SRC=%%~fi"

set "user="
set "prefix="
set "bin="
set "uninstall="
:args
if "%~1"=="" goto :args_done
if /i "%~1"=="--user" (
	set "user=yes"
	goto :next
)
if /i "%~1"=="--uninstall" (
	set "uninstall=yes"
	goto :next
)
if /i "%~1"=="-h" goto :help
if /i "%~1"=="--help" goto :help
if /i "%~1"=="--prefix" goto :take_prefix
if /i "%~1"=="--bin" goto :take_bin
echo %~nx0: unknown option %~1 ^(--help lists them^) 1>&2
exit /b 2
:take_prefix
if "%~2"=="" goto :needs_folder
set "prefix=%~2"
shift
goto :next
:take_bin
if "%~2"=="" goto :needs_folder
set "bin=%~2"
shift
goto :next
:needs_folder
echo %~nx0: %~1 needs a folder 1>&2
exit /b 2
:next
shift
goto :args
:help
echo Usage: install.cmd [--user] [--prefix DIR] [--bin DIR] [--uninstall]
echo.
echo   PREFIX is %%ProgramData%%\Parley\launcher ^(an administrator's prompt^), or with --user
echo   %%USERPROFILE%%\.parley\launcher; BIN is %%ProgramData%%\Parley\bin, or with --user
echo   %%USERPROFILE%%\.local\bin.
exit /b 0
:args_done

if defined user (
	if not defined prefix set "prefix=%USERPROFILE%\.parley\launcher"
	if not defined bin set "bin=%USERPROFILE%\.local\bin"
) else (
	if not defined prefix set "prefix=%ProgramData%\Parley\launcher"
	if not defined bin set "bin=%ProgramData%\Parley\bin"
)
for %%i in ("%prefix%") do set "prefix=%%~fi"
for %%i in ("%bin%") do set "bin=%%~fi"

rem (a system-wide install writes where only an administrator can)
if not defined user (
	whoami /groups 2>nul | findstr /c:"S-1-16-12288" >nul
	if errorlevel 1 (
		echo %~nx0: installing in %prefix% and %bin% needs an administrator's prompt ^(or use --user^) 1>&2
		exit /b 1
	)
)

if defined uninstall goto :uninstall

if not exist "%SRC%\lib\parley-launcher.jar" goto :not_here
if not exist "%SRC%\bin\parley-launcher.cmd" goto :not_here
if /i "%SRC%"=="%prefix%" (
	echo %~nx0: this folder is %prefix% already 1>&2
	exit /b 1
)

if not exist "%prefix%\bin" mkdir "%prefix%\bin"
if not exist "%prefix%\lib" mkdir "%prefix%\lib"
if not exist "%prefix%\apps" mkdir "%prefix%\apps"
if not exist "%bin%" mkdir "%bin%"
for %%d in ("%prefix%\bin" "%prefix%\lib" "%prefix%\apps" "%bin%") do if not exist "%%~d" (
	echo %~nx0: can't make %%~d 1>&2
	exit /b 1
)
rem (copied aside and renamed, so a program running right now never reads half a file)
copy /y "%SRC%\bin\parley-launcher.cmd" "%prefix%\bin\parley-launcher.cmd.new" >nul || exit /b 1
move /y "%prefix%\bin\parley-launcher.cmd.new" "%prefix%\bin\parley-launcher.cmd" >nul || exit /b 1
copy /y "%SRC%\lib\parley-launcher.jar" "%prefix%\lib\parley-launcher.jar.new" >nul || exit /b 1
move /y "%prefix%\lib\parley-launcher.jar.new" "%prefix%\lib\parley-launcher.jar" >nul || exit /b 1
if exist "%SRC%\LICENSE" copy /y "%SRC%\LICENSE" "%prefix%\" >nul
> "%prefix%\launcher.conf" echo bin=%bin%
> "%bin%\parley-launcher.cmd" echo @"%prefix%\bin\parley-launcher.cmd" %%*
rem (the programs already added keep their commands: an upgrade replaced only what they run)

echo parley-launcher installed in %prefix%, with %bin%\parley-launcher.cmd
echo add a program with: parley-launcher add NAME [[GROUP:]ARTIFACT[:VERSION]] (parley-launcher --help)
echo ";%PATH%;"| findstr /l /i /c:";%bin%;" >nul
if errorlevel 1 echo note: %bin% is not on your PATH; add it to run the programs by name
exit /b 0

:uninstall
if not exist "%prefix%\bin\parley-launcher.cmd" (
	echo parley-launcher is not installed in %prefix%
	exit /b 0
)
for %%f in ("%bin%\*.cmd") do (
	findstr /l /c:"%prefix%\bin\parley-launcher.cmd" "%%f" >nul 2>&1
	if not errorlevel 1 del /q "%%f"
)
rmdir /s /q "%prefix%\bin" "%prefix%\lib" "%prefix%\apps" 2>nul
del /q "%prefix%\launcher.conf" "%prefix%\LICENSE" 2>nul
rmdir "%prefix%" 2>nul
echo parley-launcher removed from %prefix%
exit /b 0

:not_here
echo %~nx0: run this from the unpacked parley-launcher folder 1>&2
exit /b 1
