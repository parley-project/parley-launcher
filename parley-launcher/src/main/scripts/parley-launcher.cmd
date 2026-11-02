@echo off
rem Runs a Parley program on a Java installed on this machine (parley-launcher.cmd is parley-launcher's
rem Windows script; see the parley-launcher script for Linux and macOS, which this follows).
rem Each program is a small NAME.cmd that runs this file for it (fsh.cmd, fsh-ide.cmd, pssh.cmd ...);
rem PREFIX\apps\NAME.properties names the program's artifact (artifact=group:artifact:version).
rem The jars it needs are found in the machine repository (%ProgramData%\Parley\repository), then in
rem the user's (%USERPROFILE%\.m2\repository), or downloaded from Maven Central into the user's; the
rem command that runs it is saved (%USERPROFILE%\.parley\launcher\cache\NAME.args), so the next start
rem only checks that the files it was made from haven't changed, and runs it: no other JVM, no
rem network. Run as parley-launcher it manages the programs (parley-launcher --help).
rem
rem Differences from the Linux and macOS script:
rem  - "until SECONDS" in NAME.check (a downloaded SNAPSHOT, looked for again a day later) is taken as
rem    "NAME.args was made on an earlier day": cmd has no clock in seconds that doesn't depend on the
rem    locale
rem  - a program's saved command has to fit in a command line (about 8000 characters), and no path in
rem    it may contain "!"; a line in NAME.args is a word, as it is there
rem  - a path with a character outside the console's code page can't be used

setlocal DisableDelayedExpansion
set "PL_min=11"
if defined PARLEY_APP (set "PL_app=%PARLEY_APP%") else set "PL_app=%~n0"
rem (this is for this script: the program it starts, and its children, don't get it)
set "PARLEY_APP="

for %%i in ("%~dp0..") do set "PL_prefix=%%~fi"
set "PL_jar=%PL_prefix%\lib\parley-launcher.jar"
if defined PARLEY_LAUNCHER_HOME (set "PL_home=%PARLEY_LAUNCHER_HOME%") else set "PL_home=%USERPROFILE%\.parley\launcher"

rem (what, besides the files it was made from, the saved command depends on)
set "PL_key=2w|%JAVA_HOME%|%PARLEY_JAVA%|%PARLEY_MACHINE_REPOSITORY%|%PARLEY_USER_REPOSITORY%|%PARLEY_CENTRAL%|%PARLEY_SNAPSHOTS%|%PARLEY_OFFLINE%"

if /i "%PL_app%"=="parley-launcher" goto :manage

rem ---- a program

if defined PARLEY_REFRESH goto :make
call :current "%PL_app%"
if errorlevel 1 goto :make
goto :run
:make
call :resolve "%PL_app%"
if errorlevel 1 exit /b %errorlevel%
goto :run

:run
set "PL_c=%PL_home%\cache\%PL_app%"
for %%a in ("%PL_c%.args") do if %%~za gtr 7500 (
	echo %PL_app%: its command is too long for a Windows command line ^(%%~za characters^) 1>&2
	exit /b 1
)
setlocal EnableDelayedExpansion
set "PL_line="
for /f "usebackq skip=1 delims=" %%l in ("%PL_c%.args") do set PL_line=!PL_line! "%%l"
endlocal & set PL_CMD=%PL_line%
rem (the program gets the environment it was started with, not this script's variables: all of them are PL_...)
for /f "delims==" %%v in ('set PL_ 2^>nul') do if /i not "%%v"=="PL_CMD" set "%%v="
set "PL_CMD=" & %PL_CMD% %*
exit /b %errorlevel%

rem ---- parley-launcher's own commands

:manage
set "PL_bin="
if exist "%PL_prefix%\launcher.conf" for /f "usebackq tokens=1,* delims==" %%a in ("%PL_prefix%\launcher.conf") do if "%%a"=="bin" set "PL_bin=%%b"
if "%~1"=="" goto :usage
if /i "%~1"=="--help" goto :usage
if /i "%~1"=="-h" goto :usage
if /i "%~1"=="--version" goto :version
if /i "%~1"=="list" goto :list
if /i "%~1"=="versions" goto :versions
if /i "%~1"=="add" goto :add
if /i "%~1"=="remove" goto :remove
if /i "%~1"=="command" goto :command
call :usage
exit /b 2

:usage
echo Runs a Parley program on a Java installed on this machine. Each program is a NAME.cmd that
echo runs this script for it (fsh, fsh-ide, pssh ...); run as parley-launcher it manages them:
echo.
echo   parley-launcher add NAME [[GROUP:]ARTIFACT[:VERSION] [MAIN]]
echo                                    add a program (its app file and its command);
echo                                    no ARTIFACT: NAME is the artifact;
echo                                    no GROUP: us.bringardner, else us.bringardner.parley;
echo                                    no VERSION: pick from the versions found (the
echo                                    newest release is the default);
echo                                    MAIN: another main class in that jar
echo   parley-launcher versions [GROUP:]ARTIFACT   the versions found, newest first
echo   parley-launcher remove NAME      remove one
echo   parley-launcher list             the programs and their artifacts
echo   parley-launcher command NAME     the command NAME runs (made if need be)
echo   parley-launcher --version
echo.
echo Settings (environment variables):
echo   JAVA_HOME                   its java when that is 11 or later, else the newest Java found
echo   PARLEY_JAVA                 the java executable to use
echo   PARLEY_REFRESH=1            make the command again (after installing a newer Java, say)
echo   PARLEY_OFFLINE=true         download nothing
echo   PARLEY_MACHINE_REPOSITORY   instead of %%ProgramData%%\Parley\repository
echo   PARLEY_USER_REPOSITORY      instead of %%USERPROFILE%%\.m2\repository (or settings.xml's localRepository)
echo   PARLEY_CENTRAL              instead of https://repo1.maven.org/maven2/
echo   PARLEY_SNAPSHOTS            instead of https://central.sonatype.com/repository/maven-snapshots/
echo                               (where a SNAPSHOT not installed here comes from; none: nowhere)
echo   PARLEY_LAUNCHER_HOME        instead of %%USERPROFILE%%\.parley\launcher (the saved commands, native files)
echo   JDK_JAVA_OPTIONS            more options for java (java itself reads it)
exit /b 0

:version
call :find_java
if errorlevel 1 exit /b 1
"%PL_java%" -jar "%PL_jar%" --version
exit /b %errorlevel%

:list
for %%f in ("%PL_prefix%\apps\*.properties") do for /f "usebackq tokens=1,* delims==" %%a in ("%%f") do if "%%a"=="artifact" echo %%~nf	%%b
exit /b 0

:versions
if "%~2"=="" goto :versions_usage
if not "%~3"=="" goto :versions_usage
call :find_java
if errorlevel 1 exit /b 1
"%PL_java%" -jar "%PL_jar%" versions "%~2"
exit /b %errorlevel%
:versions_usage
echo usage: parley-launcher versions [GROUP:]ARTIFACT[:VERSION] 1>&2
exit /b 2

:add
if "%~2"=="" goto :add_usage
if not "%~5"=="" goto :add_usage
set "PL_app=%~2"
set "PL_coords=%~3"
set "PL_main=%~4"
if /i "%PL_app%"=="parley-launcher" goto :add_name
if "%PL_app:~0,1%"=="." goto :add_name
echo "%PL_app%"| findstr /r /c:"[\\/:*?<>| ]" >nul
if not errorlevel 1 goto :add_name
goto :add_check
:add_usage
echo usage: parley-launcher add NAME [[GROUP:]ARTIFACT[:VERSION] [MAIN]] 1>&2
exit /b 2
:add_name
echo %PL_app%: not a program name 1>&2
exit /b 1
:add_check
rem (never over a command that isn't one of the launcher's programs)
set "PL_other=%PL_prefix%\bin\%PL_app%.cmd"
if defined PL_bin set "PL_other=%PL_bin%\%PL_app%.cmd"
for %%l in ("%PL_prefix%\bin\%PL_app%.cmd" "%PL_other%") do if exist "%%~l" (
	findstr /l /c:"PARLEY_APP=" "%%~l" >nul 2>&1
	if errorlevel 1 (
		echo %PL_app%: %%~l is there already, and is not a program parley-launcher added 1>&2
		exit /b 1
	)
)
if not defined PL_coords set "PL_coords=%PL_app%"
call :count_colons "%PL_coords%"
if %PL_colons% geq 2 goto :add_write
rem (no group or no version: the launcher finds them, asking which version when there is a choice)
call :find_java
if errorlevel 1 exit /b 1
set "PL_chosen="
for /f "usebackq delims=" %%c in (`""%PL_java%" -jar "%PL_jar%" choose "%PL_coords%""`) do set "PL_chosen=%%c"
if not defined PL_chosen exit /b 1
set "PL_coords=%PL_chosen%"
:add_write
if not exist "%PL_prefix%\apps" mkdir "%PL_prefix%\apps"
> "%PL_prefix%\apps\%PL_app%.properties" echo # %PL_app%: the program the command %PL_app% runs
>> "%PL_prefix%\apps\%PL_app%.properties" echo artifact=%PL_coords%
if defined PL_main >> "%PL_prefix%\apps\%PL_app%.properties" echo main=%PL_main%
> "%PL_prefix%\bin\%PL_app%.cmd" echo @setlocal ^& set "PARLEY_APP=%PL_app%" ^& "%PL_prefix%\bin\parley-launcher.cmd" %%*
if defined PL_bin (
	if not exist "%PL_bin%" mkdir "%PL_bin%"
	> "%PL_bin%\%PL_app%.cmd" echo @setlocal ^& set "PARLEY_APP=%PL_app%" ^& "%PL_prefix%\bin\parley-launcher.cmd" %%*
)
if defined PL_bin (echo added %PL_app% ^(%PL_coords%^): %PL_bin%\%PL_app%.cmd) else echo added %PL_app% ^(%PL_coords%^)
call :is_admin
if not errorlevel 1 (
	rem (as an administrator, for everyone: each user's command is made at their first start, in
	rem their own repository and cache)
	echo each user's first %PL_app% fetches what it needs
	exit /b 0
)
rem (made now, so a missing jar or SNAPSHOT shows here, not at the first start)
set "PARLEY_REFRESH=1"
call :resolve "%PL_app%"
exit /b %errorlevel%

:remove
if "%~2"=="" goto :remove_usage
if not "%~3"=="" goto :remove_usage
set "PL_app=%~2"
if not exist "%PL_prefix%\apps\%PL_app%.properties" (
	echo %PL_app%: there is no program %PL_app% here 1>&2
	exit /b 1
)
del /q "%PL_prefix%\apps\%PL_app%.properties" "%PL_prefix%\bin\%PL_app%.cmd" 2>nul
if defined PL_bin if exist "%PL_bin%\%PL_app%.cmd" (
	findstr /l /c:"%PL_prefix%\bin\parley-launcher.cmd" "%PL_bin%\%PL_app%.cmd" >nul 2>&1
	if not errorlevel 1 del /q "%PL_bin%\%PL_app%.cmd"
)
del /q "%PL_home%\cache\%PL_app%.args" "%PL_home%\cache\%PL_app%.check" "%PL_home%\cache\%PL_app%.sig" 2>nul
echo removed %PL_app%
exit /b 0
:remove_usage
echo usage: parley-launcher remove NAME 1>&2
exit /b 2

:command
if "%~2"=="" goto :command_usage
if not "%~3"=="" goto :command_usage
set "PL_app=%~2"
if defined PARLEY_REFRESH goto :command_make
call :current "%PL_app%"
if errorlevel 1 goto :command_make
goto :command_print
:command_make
call :resolve "%PL_app%"
if errorlevel 1 exit /b %errorlevel%
:command_print
for /f "usebackq skip=1 delims=" %%l in ("%PL_home%\cache\%PL_app%.args") do echo(%%l
exit /b 0
:command_usage
echo usage: parley-launcher command NAME 1>&2
exit /b 2

rem ---- the saved command

rem A program's command is saved in NAME.args, the files it was made from in NAME.check. This script
rem also saves NAME.sig: each of those files with its time (to the minute) and size at that moment,
rem which is what is compared at the next start (cmd can't compare file times to the second).

rem exit code 0 when %1's saved command is current: its key is this one, and every file it was made
rem from is as it was ("!" FILE: is still not there; "until SECONDS": see the top; "retry": made
rem again at the next start)
:current
set "PL_cname=%~1"
set "PL_cbase=%PL_home%\cache\%~1"
if not exist "%PL_cbase%.args" exit /b 1
if not exist "%PL_cbase%.sig" exit /b 1
set "PL_k="
for /f "usebackq delims=" %%k in ("%PL_cbase%.args") do if not defined PL_k set "PL_k=%%k"
if not "%PL_k%"=="%PL_key%" exit /b 1
set "PL_cuntil="
for /f "usebackq tokens=1,2,3 delims=|" %%a in ("%PL_cbase%.sig") do (
	call :check_one "%%a" "%%b|%%c"
	if errorlevel 1 exit /b 1
)
if defined PL_cuntil (
	rem (forfiles succeeds when NAME.args is from an earlier day)
	forfiles /p "%PL_home%\cache" /m "%PL_cname%.args" /d -1 >nul 2>&1
	if not errorlevel 1 exit /b 1
)
exit /b 0

rem one line of NAME.sig: %1 is a path (or "retry", "until SECONDS", "!" and a path), %2 its time|size
:check_one
set "PL_f=%~1"
if "%PL_f%"=="retry" exit /b 1
if "%PL_f:~0,6%"=="until " (
	set "PL_cuntil=1"
	exit /b 0
)
if "%PL_f:~0,1%"=="!" (
	if exist "%PL_f:~1%" exit /b 1
	exit /b 0
)
set "PL_stamp="
for %%x in ("%PL_f%") do set "PL_stamp=%%~tx|%%~zx"
if not "%PL_stamp%"=="%~2" exit /b 1
exit /b 0

rem NAME.sig from NAME.check (%1 is NAME), once its command has been made
:write_sig
set "PL_sbase=%PL_home%\cache\%~1"
type nul > "%PL_sbase%.sig.part"
for /f "usebackq delims=" %%f in ("%PL_sbase%.check") do call :sig_one "%%f"
move /y "%PL_sbase%.sig.part" "%PL_sbase%.sig" >nul
exit /b 0

:sig_one
set "PL_f=%~1"
if "%PL_f%"=="retry" goto :sig_plain
if "%PL_f:~0,6%"=="until " goto :sig_plain
if "%PL_f:~0,1%"=="!" goto :sig_plain
for %%x in ("%PL_f%") do >> "%PL_sbase%.sig.part" echo(%PL_f%^|%%~tx^|%%~zx
exit /b 0
:sig_plain
>> "%PL_sbase%.sig.part" echo(%PL_f%
exit /b 0

rem ---- Java

rem The major version of the java executable %1 (8, 11, 17, 21 ...) in %PL_major%, empty when it is not
rem one: from its JDK's release file, which needs no JVM started, else from java -version
:java_major
set "PL_major="
if not exist "%~1" exit /b 1
set "PL_ver="
set "PL_jhome="
for %%i in ("%~1") do set "PL_jhome=%%~dpi.."
if exist "%PL_jhome%\release" for /f "usebackq tokens=2 delims==" %%v in (`findstr /b /c:"JAVA_VERSION=" "%PL_jhome%\release"`) do set "PL_ver=%%~v"
if not defined PL_ver for /f "usebackq tokens=3" %%v in (`""%~1" -version 2^>^&1 ^| findstr /c:" version ""`) do if not defined PL_ver set "PL_ver=%%~v"
if not defined PL_ver exit /b 1
rem ("1.8.0_392" and "17.0.9" / "25-ea" both)
if "%PL_ver:~0,2%"=="1." set "PL_ver=%PL_ver:~2%"
for /f "delims=.+-_ " %%m in ("%PL_ver%") do set "PL_major=%%m"
if not defined PL_major exit /b 1
echo %PL_major%| findstr /r "^[0-9][0-9]*$" >nul || set "PL_major="
if not defined PL_major exit /b 1
exit /b 0

rem the java in %PL_java%: PARLEY_JAVA, else JAVA_HOME's when it is new enough, else the newest found
:find_java
set "PL_java="
if defined PARLEY_JAVA goto :find_parley_java
if not defined JAVA_HOME goto :find_newest
if not exist "%JAVA_HOME%\bin\java.exe" goto :find_newest
call :java_major "%JAVA_HOME%\bin\java.exe"
if not defined PL_major goto :find_newest
if %PL_major% lss %PL_min% goto :find_newest
set "PL_java=%JAVA_HOME%\bin\java.exe"
exit /b 0

:find_parley_java
set "PL_j=%PARLEY_JAVA%"
rem (a name or a relative path: the java it means, as a path that stays right from any folder)
set "PL_jfound="
echo %PARLEY_JAVA%| findstr /c:"\" /c:"/" /c:":" >nul
if not errorlevel 1 goto :find_parley_path
for /f "delims=" %%p in ('where "%PARLEY_JAVA%" 2^>nul') do if not defined PL_jfound set "PL_jfound=%%p"
if defined PL_jfound set "PL_j=%PL_jfound%"
:find_parley_path
for %%i in ("%PL_j%") do set "PL_j=%%~fi"
if not exist "%PL_j%" if exist "%PL_j%.exe" set "PL_j=%PL_j%.exe"
call :java_major "%PL_j%"
if not defined PL_major (
	echo %PL_app%: PARLEY_JAVA=%PARLEY_JAVA% is not a java executable 1>&2
	exit /b 1
)
if %PL_major% lss %PL_min% (
	echo %PL_app%: PARLEY_JAVA=%PARLEY_JAVA% is Java %PL_major%; Java %PL_min% or later is needed 1>&2
	exit /b 1
)
set "PL_java=%PL_j%"
exit /b 0

:find_newest
set "PL_best=0"
set "PL_older="
for /f "delims=" %%j in ('where java.exe 2^>nul') do call :consider "%%j"
for %%p in (
	"%ProgramFiles%\Java\*"
	"%ProgramFiles%\Eclipse Adoptium\*"
	"%ProgramFiles%\Microsoft\jdk*"
	"%ProgramFiles%\Zulu\*"
	"%ProgramFiles%\Amazon Corretto\*"
	"%ProgramFiles%\BellSoft\*"
	"%ProgramFiles%\Semeru\*"
	"%ProgramFiles%\OpenJDK\*"
	"%ProgramFiles%\Android\openjdk\*"
	"%LOCALAPPDATA%\Programs\Eclipse Adoptium\*"
	"%USERPROFILE%\.jdks\*"
	"%USERPROFILE%\scoop\apps\*\current"
) do for /d %%d in (%%p) do if exist "%%d\bin\java.exe" call :consider "%%d\bin\java.exe"
if defined PL_java exit /b 0
echo %PL_app%: Java %PL_min% or later is needed, and none was found on this computer. 1>&2
if defined PL_older echo These are installed but too old:%PL_older% 1>&2
echo Install a JDK %PL_min% or later (for example from https://adoptium.net), or set JAVA_HOME 1>&2
echo (or PARLEY_JAVA) to one, and run %PL_app% again. 1>&2
exit /b 1

:consider
call :java_major "%~1"
if not defined PL_major exit /b 0
if %PL_major% lss %PL_min% (
	set "PL_older=%PL_older% [Java %PL_major%: %~1]"
	exit /b 0
)
if %PL_major% gtr %PL_best% (
	set "PL_best=%PL_major%"
	set "PL_java=%~1"
)
exit /b 0

rem Make %1's saved command (the launcher works it out, fetching what is missing)
:resolve
set "PL_rapp=%~1"
set "PL_rfile=%PL_prefix%\apps\%~1.properties"
if not exist "%PL_rfile%" (
	echo %PL_rapp%: there is no program %PL_rapp% here ^(%PL_rfile%^): parley-launcher add %PL_rapp% 1>&2
	exit /b 1
)
if not exist "%PL_jar%" (
	echo %PL_jar% is missing: keep the folder parley-launcher was installed in together 1>&2
	exit /b 1
)
call :find_java
if errorlevel 1 exit /b 1
if not exist "%PL_home%\cache" mkdir "%PL_home%\cache"
"%PL_java%" -XX:TieredStopAtLevel=1 -Xshare:auto -jar "%PL_jar%" resolve --app "%PL_rapp%" --app-file "%PL_rfile%" --java "%PL_java%" --cache "%PL_home%\cache\%PL_rapp%" --key "%PL_key%"
if errorlevel 1 exit /b %errorlevel%
call :write_sig "%PL_rapp%"
exit /b 0

rem the number of colons in %1, in %PL_colons%
:count_colons
set "PL_colons=0"
set "PL_s=%~1"
:count_more
if not defined PL_s exit /b 0
if "%PL_s:~0,1%"==":" set /a PL_colons+=1
set "PL_s=%PL_s:~1%"
goto :count_more

rem exit code 0 when this is an administrator's (elevated) prompt
:is_admin
whoami /groups 2>nul | findstr /c:"S-1-16-12288" >nul
exit /b %errorlevel%
