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
set "min=11"
if defined PARLEY_APP (set "app=%PARLEY_APP%") else set "app=%~n0"

for %%i in ("%~dp0..") do set "prefix=%%~fi"
set "jar=%prefix%\lib\parley-launcher.jar"
if defined PARLEY_LAUNCHER_HOME (set "home=%PARLEY_LAUNCHER_HOME%") else set "home=%USERPROFILE%\.parley\launcher"

rem (what, besides the files it was made from, the saved command depends on)
set "key=2w|%JAVA_HOME%|%PARLEY_JAVA%|%PARLEY_MACHINE_REPOSITORY%|%PARLEY_USER_REPOSITORY%|%PARLEY_CENTRAL%|%PARLEY_SNAPSHOTS%|%PARLEY_OFFLINE%"

if /i "%app%"=="parley-launcher" goto :manage

rem ---- a program

if defined PARLEY_REFRESH goto :make
call :current "%app%"
if errorlevel 1 goto :make
goto :run
:make
call :resolve "%app%"
if errorlevel 1 exit /b %errorlevel%
goto :run

:run
set "c=%home%\cache\%app%"
for %%a in ("%c%.args") do if %%~za gtr 7500 (
	echo %app%: its command is too long for a Windows command line ^(%%~za characters^) 1>&2
	exit /b 1
)
setlocal EnableDelayedExpansion
set "line="
for /f "usebackq skip=1 delims=" %%l in ("%c%.args") do set line=!line! "%%l"
endlocal & set PL_CMD=%line%
%PL_CMD% %*
exit /b %errorlevel%

rem ---- parley-launcher's own commands

:manage
set "bin="
if exist "%prefix%\launcher.conf" for /f "usebackq tokens=1,* delims==" %%a in ("%prefix%\launcher.conf") do if "%%a"=="bin" set "bin=%%b"
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
"%java%" -jar "%jar%" --version
exit /b %errorlevel%

:list
for %%f in ("%prefix%\apps\*.properties") do for /f "usebackq tokens=1,* delims==" %%a in ("%%f") do if "%%a"=="artifact" echo %%~nf	%%b
exit /b 0

:versions
if "%~2"=="" goto :versions_usage
if not "%~3"=="" goto :versions_usage
call :find_java
if errorlevel 1 exit /b 1
"%java%" -jar "%jar%" versions "%~2"
exit /b %errorlevel%
:versions_usage
echo usage: parley-launcher versions [GROUP:]ARTIFACT[:VERSION] 1>&2
exit /b 2

:add
if "%~2"=="" goto :add_usage
if not "%~5"=="" goto :add_usage
set "app=%~2"
set "coords=%~3"
set "main=%~4"
if /i "%app%"=="parley-launcher" goto :add_name
if "%app:~0,1%"=="." goto :add_name
echo "%app%"| findstr /r /c:"[\\/:*?<>| ]" >nul
if not errorlevel 1 goto :add_name
goto :add_check
:add_usage
echo usage: parley-launcher add NAME [[GROUP:]ARTIFACT[:VERSION] [MAIN]] 1>&2
exit /b 2
:add_name
echo %app%: not a program name 1>&2
exit /b 1
:add_check
rem (never over a command that isn't one of the launcher's programs)
set "other=%prefix%\bin\%app%.cmd"
if defined bin set "other=%bin%\%app%.cmd"
for %%l in ("%prefix%\bin\%app%.cmd" "%other%") do if exist "%%~l" (
	findstr /l /c:"PARLEY_APP=" "%%~l" >nul 2>&1
	if errorlevel 1 (
		echo %app%: %%~l is there already, and is not a program parley-launcher added 1>&2
		exit /b 1
	)
)
if not defined coords set "coords=%app%"
call :count_colons "%coords%"
if %colons% geq 2 goto :add_write
rem (no group or no version: the launcher finds them, asking which version when there is a choice)
call :find_java
if errorlevel 1 exit /b 1
set "chosen="
for /f "usebackq delims=" %%c in (`""%java%" -jar "%jar%" choose "%coords%""`) do set "chosen=%%c"
if not defined chosen exit /b 1
set "coords=%chosen%"
:add_write
if not exist "%prefix%\apps" mkdir "%prefix%\apps"
> "%prefix%\apps\%app%.properties" echo # %app%: the program the command %app% runs
>> "%prefix%\apps\%app%.properties" echo artifact=%coords%
if defined main >> "%prefix%\apps\%app%.properties" echo main=%main%
> "%prefix%\bin\%app%.cmd" echo @setlocal ^& set "PARLEY_APP=%app%" ^& "%prefix%\bin\parley-launcher.cmd" %%*
if defined bin (
	if not exist "%bin%" mkdir "%bin%"
	> "%bin%\%app%.cmd" echo @setlocal ^& set "PARLEY_APP=%app%" ^& "%prefix%\bin\parley-launcher.cmd" %%*
)
if defined bin (echo added %app% ^(%coords%^): %bin%\%app%.cmd) else echo added %app% ^(%coords%^)
call :is_admin
if not errorlevel 1 (
	rem (as an administrator, for everyone: each user's command is made at their first start, in
	rem their own repository and cache)
	echo each user's first %app% fetches what it needs
	exit /b 0
)
rem (made now, so a missing jar or SNAPSHOT shows here, not at the first start)
set "PARLEY_REFRESH=1"
call :resolve "%app%"
exit /b %errorlevel%

:remove
if "%~2"=="" goto :remove_usage
if not "%~3"=="" goto :remove_usage
set "app=%~2"
if not exist "%prefix%\apps\%app%.properties" (
	echo %app%: there is no program %app% here 1>&2
	exit /b 1
)
del /q "%prefix%\apps\%app%.properties" "%prefix%\bin\%app%.cmd" 2>nul
if defined bin if exist "%bin%\%app%.cmd" (
	findstr /l /c:"%prefix%\bin\parley-launcher.cmd" "%bin%\%app%.cmd" >nul 2>&1
	if not errorlevel 1 del /q "%bin%\%app%.cmd"
)
del /q "%home%\cache\%app%.args" "%home%\cache\%app%.check" "%home%\cache\%app%.sig" 2>nul
echo removed %app%
exit /b 0
:remove_usage
echo usage: parley-launcher remove NAME 1>&2
exit /b 2

:command
if "%~2"=="" goto :command_usage
if not "%~3"=="" goto :command_usage
set "app=%~2"
if defined PARLEY_REFRESH goto :command_make
call :current "%app%"
if errorlevel 1 goto :command_make
goto :command_print
:command_make
call :resolve "%app%"
if errorlevel 1 exit /b %errorlevel%
:command_print
for /f "usebackq skip=1 delims=" %%l in ("%home%\cache\%app%.args") do echo(%%l
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
set "cname=%~1"
set "cbase=%home%\cache\%~1"
if not exist "%cbase%.args" exit /b 1
if not exist "%cbase%.sig" exit /b 1
set "k="
for /f "usebackq delims=" %%k in ("%cbase%.args") do if not defined k set "k=%%k"
if not "%k%"=="%key%" exit /b 1
set "cuntil="
for /f "usebackq tokens=1,2,3 delims=|" %%a in ("%cbase%.sig") do (
	call :check_one "%%a" "%%b|%%c"
	if errorlevel 1 exit /b 1
)
if defined cuntil (
	rem (forfiles succeeds when NAME.args is from an earlier day)
	forfiles /p "%home%\cache" /m "%cname%.args" /d -1 >nul 2>&1
	if not errorlevel 1 exit /b 1
)
exit /b 0

rem one line of NAME.sig: %1 is a path (or "retry", "until SECONDS", "!" and a path), %2 its time|size
:check_one
set "f=%~1"
if "%f%"=="retry" exit /b 1
if "%f:~0,6%"=="until " (
	set "cuntil=1"
	exit /b 0
)
if "%f:~0,1%"=="!" (
	if exist "%f:~1%" exit /b 1
	exit /b 0
)
set "stamp="
for %%x in ("%f%") do set "stamp=%%~tx|%%~zx"
if not "%stamp%"=="%~2" exit /b 1
exit /b 0

rem NAME.sig from NAME.check (%1 is NAME), once its command has been made
:write_sig
set "sbase=%home%\cache\%~1"
type nul > "%sbase%.sig.part"
for /f "usebackq delims=" %%f in ("%sbase%.check") do call :sig_one "%%f"
move /y "%sbase%.sig.part" "%sbase%.sig" >nul
exit /b 0

:sig_one
set "f=%~1"
if "%f%"=="retry" goto :sig_plain
if "%f:~0,6%"=="until " goto :sig_plain
if "%f:~0,1%"=="!" goto :sig_plain
for %%x in ("%f%") do >> "%sbase%.sig.part" echo(%f%^|%%~tx^|%%~zx
exit /b 0
:sig_plain
>> "%sbase%.sig.part" echo(%f%
exit /b 0

rem ---- Java

rem The major version of the java executable %1 (8, 11, 17, 21 ...) in %major%, empty when it is not
rem one: from its JDK's release file, which needs no JVM started, else from java -version
:java_major
set "major="
if not exist "%~1" exit /b 1
set "ver="
set "jhome="
for %%i in ("%~1") do set "jhome=%%~dpi.."
if exist "%jhome%\release" for /f "usebackq tokens=2 delims==" %%v in (`findstr /b /c:"JAVA_VERSION=" "%jhome%\release"`) do set "ver=%%~v"
if not defined ver for /f "usebackq tokens=3" %%v in (`""%~1" -version 2^>^&1 ^| findstr /c:" version ""`) do if not defined ver set "ver=%%~v"
if not defined ver exit /b 1
rem ("1.8.0_392" and "17.0.9" / "25-ea" both)
if "%ver:~0,2%"=="1." set "ver=%ver:~2%"
for /f "delims=.+-_ " %%m in ("%ver%") do set "major=%%m"
if not defined major exit /b 1
echo %major%| findstr /r "^[0-9][0-9]*$" >nul || set "major="
if not defined major exit /b 1
exit /b 0

rem the java in %java%: PARLEY_JAVA, else JAVA_HOME's when it is new enough, else the newest found
:find_java
set "java="
if defined PARLEY_JAVA goto :find_parley_java
if not defined JAVA_HOME goto :find_newest
if not exist "%JAVA_HOME%\bin\java.exe" goto :find_newest
call :java_major "%JAVA_HOME%\bin\java.exe"
if not defined major goto :find_newest
if %major% lss %min% goto :find_newest
set "java=%JAVA_HOME%\bin\java.exe"
exit /b 0

:find_parley_java
set "j=%PARLEY_JAVA%"
rem (a name or a relative path: the java it means, as a path that stays right from any folder)
set "jfound="
echo %PARLEY_JAVA%| findstr /c:"\" /c:"/" /c:":" >nul
if not errorlevel 1 goto :find_parley_path
for /f "delims=" %%p in ('where "%PARLEY_JAVA%" 2^>nul') do if not defined jfound set "jfound=%%p"
if defined jfound set "j=%jfound%"
:find_parley_path
for %%i in ("%j%") do set "j=%%~fi"
if not exist "%j%" if exist "%j%.exe" set "j=%j%.exe"
call :java_major "%j%"
if not defined major (
	echo %app%: PARLEY_JAVA=%PARLEY_JAVA% is not a java executable 1>&2
	exit /b 1
)
if %major% lss %min% (
	echo %app%: PARLEY_JAVA=%PARLEY_JAVA% is Java %major%; Java %min% or later is needed 1>&2
	exit /b 1
)
set "java=%j%"
exit /b 0

:find_newest
set "best=0"
set "older="
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
if defined java exit /b 0
echo %app%: Java %min% or later is needed, and none was found on this computer. 1>&2
if defined older echo These are installed but too old:%older% 1>&2
echo Install a JDK %min% or later (for example from https://adoptium.net), or set JAVA_HOME 1>&2
echo (or PARLEY_JAVA) to one, and run %app% again. 1>&2
exit /b 1

:consider
call :java_major "%~1"
if not defined major exit /b 0
if %major% lss %min% (
	set "older=%older% [Java %major%: %~1]"
	exit /b 0
)
if %major% gtr %best% (
	set "best=%major%"
	set "java=%~1"
)
exit /b 0

rem Make %1's saved command (the launcher works it out, fetching what is missing)
:resolve
set "rapp=%~1"
set "rfile=%prefix%\apps\%~1.properties"
if not exist "%rfile%" (
	echo %rapp%: there is no program %rapp% here ^(%rfile%^): parley-launcher add %rapp% 1>&2
	exit /b 1
)
if not exist "%jar%" (
	echo %jar% is missing: keep the folder parley-launcher was installed in together 1>&2
	exit /b 1
)
call :find_java
if errorlevel 1 exit /b 1
if not exist "%home%\cache" mkdir "%home%\cache"
"%java%" -XX:TieredStopAtLevel=1 -Xshare:auto -jar "%jar%" resolve --app "%rapp%" --app-file "%rfile%" --java "%java%" --cache "%home%\cache\%rapp%" --key "%key%"
if errorlevel 1 exit /b %errorlevel%
call :write_sig "%rapp%"
exit /b 0

rem the number of colons in %1, in %colons%
:count_colons
set "colons=0"
set "s=%~1"
:count_more
if not defined s exit /b 0
if "%s:~0,1%"==":" set /a colons+=1
set "s=%s:~1%"
goto :count_more

rem exit code 0 when this is an administrator's (elevated) prompt
:is_admin
whoami /groups 2>nul | findstr /c:"S-1-16-12288" >nul
exit /b %errorlevel%
