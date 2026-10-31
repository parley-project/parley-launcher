# parley-launcher

Runs a Parley program on a Java that is already installed (11 or later). Every program (fsh,
fsh-ide, pssh, parley-files-viewer ...) starts through one script, renamed or linked for it. The
script makes sure every jar the program needs is on the machine, fetching the missing ones from
Maven Central, then runs it. The programs share their jars the way Maven projects share
`~/.m2/repository`: one copy of `parley-core-2.0.0.jar`, whatever uses it.

```sh
tar xzf parley-launcher-2.0.0.tar.gz && parley-launcher-2.0.0/install.sh   # or --user
parley-launcher add fsh            # pick the version; the newest release is the default
fsh
```

## How it works

1. **Each program's jar says what it needs.** `parley-launcher-maven-plugin` writes
   `META-INF/parley/launcher.list` into the jar as the program is built: its main class, its Java
   options, its native files, and every jar on its run-time class path with that jar's SHA-256.
   The launcher reads that list. It doesn't run Maven or read POMs to work out what a program
   needs.
2. **Two repositories, in Maven's layout.** A jar is looked for in the **machine repository**
   (`/usr/local/lib/parley/repository`, on Windows `%ProgramData%\Parley\repository`), shared by
   every user and written only by an administrator, then in the **user repository**, the user's
   real `~/.m2/repository` (or `settings.xml`'s `localRepository`), shared with Maven itself.
   A SNAPSHOT is looked for in the user repository first, where `mvn install` puts it.
3. **What is missing is downloaded** from Maven Central into the user repository, written as
   Maven writes it: the `.pom` (and its parent POMs) and the `.sha1` files beside the jar, and
   `_remote.repositories`, so Maven takes the files as its own, offline too. Each file is written
   aside and renamed, so a Maven build running at the same time never sees half a jar. A busy
   server (HTTP 429, 5xx) is tried again.
4. **Released jars are checked** against the list's SHA-256, wherever they are found; a copy
   that doesn't match is not used, and is downloaded again. A jar the list doesn't cover (the
   program's own, a plugin's) is checked against the `.sha1` Maven Central publishes.
5. **A SNAPSHOT is never downloaded** (phase 1): it comes from the user repository, after `mvn
   install` in its project, and isn't checked (it changes with every build). One that isn't
   there stops the program with a message naming the project to install.
6. **The command is saved.** The first start works out the command (`java`, its options, the
   class path) and saves it in `~/.parley/launcher/cache/NAME.args`, with the files it was made
   from in `NAME.check`. Later starts only check that those files haven't changed, then run it:
   no other JVM starts, and nothing goes over the network. A newer SNAPSHOT installed, a plugin
   added, a jar removed or put in the machine repository, a new launcher or a changed
   `settings.xml` makes the command again, and so does a failure that may pass (an optional
   native jar the network didn't deliver).
7. **Native files.** A program can name a jar of this platform's native files
   (`fsh-2.0.0-linux-x86_64.jar`: fsh's keyboard library and `fshexec`). The launcher unpacks it
   into `~/.parley/launcher/native/` and passes that folder to the program's options. Without it,
   the options that need it are left out (fsh then uses JNA).

## Programs

| Command | |
|---|---|
| `parley-launcher add NAME [[GROUP:]ARTIFACT[:VERSION]]` | Add a program (see below for leaving out the artifact, group or version): `PREFIX/apps/NAME.properties`, a link `NAME` beside the launcher and in the bin folder, and its command made at once (so a missing jar shows now). As root (`sudo`, for a system-wide install), each user's command is made at their own first start instead, so root never writes into a user's `~/.m2`. It refuses a name that is already some other command |
| `parley-launcher versions [GROUP:]ARTIFACT` | The versions found, newest first, the default marked |
| `parley-launcher remove NAME` | Remove one |
| `parley-launcher list` | The programs and their artifacts |
| `parley-launcher command NAME` | The command NAME runs, one word a line |

The artifact, the group and the version can be left out of `add`:

1. **No artifact** (`parley-launcher add fsh`): the program's name is the artifact. To name
   another main class (the fourth argument), the artifact must be given.
2. **No group** (`fsh`, `fsh:2.0.0`): the artifact is looked for in `us.bringardner`, then
   `us.bringardner.parley`. A second part that starts with a digit is a version (`fsh:2.0.0`);
   otherwise it is the group and the artifact (`us.bringardner.parley:parley-ssh`).
3. **No version**: the versions on Maven Central (its `maven-metadata.xml`) and in the machine and
   user repositories (a SNAPSHOT that `mvn install` put there) are listed, newest first, and you
   pick one by its number or its version. Enter takes the default: the newest release, or the
   newest SNAPSHOT when there is no release. With only one version there is no question; with no
   answer at all (no terminal) the default is taken. When the artifact is in both groups, both
   are listed. `PARLEY_OFFLINE=true`, or Central out of reach, lists this machine's versions only.
4. The app file always gets the full `group:artifact:version` that was picked, so the program
   doesn't change version until it is added again.

`install.sh` installs the launcher in `/usr/local/lib/parley/launcher`, linked from
`/usr/local/bin` (sudo asks), or with `--user` in `~/.parley/launcher`, linked from
`~/.local/bin`. Running a newer one's `install.sh` upgrades it; `--uninstall` removes it and the
programs' links (the jars stay in the repositories, which are Maven's too).

## The Parley programs

| Command | Add it with |
|---|---|
| `fsh` | `parley-launcher add fsh` (or `us.bringardner:fsh:VERSION`) |
| `fsh-ide` | `parley-launcher add fsh-ide us.bringardner:fsh-ide:VERSION` |
| `fsh-ide-fx` (JavaFX) | `parley-launcher add fsh-ide-fx us.bringardner:fsh-ide:VERSION us.bringardner.fsh.ide.fx.FshIdeFx` |
| `fshdb` | `parley-launcher add fshdb us.bringardner:fshdb:VERSION` |
| `pssh` | `parley-launcher add pssh us.bringardner.parley:parley-ssh:VERSION` |
| `parley-files-viewer` | `parley-launcher add parley-files-viewer us.bringardner.parley:parley-files-viewer:VERSION` |
| `parley-files-viewer-fx` (JavaFX) | `parley-launcher add parley-files-viewer-fx us.bringardner.parley:parley-files-viewer:VERSION us.bringardner.parley.files.viewer.fx.Launcher` |
| `pfv` | `parley-launcher add pfv us.bringardner.parley:parley-files-viewer-tui:VERSION` |

The last argument of `add` names another main class in the same jar (`main=` in the app file).
fsh-ide and fshdb run fsh scripts, so they use fsh's native jar too. The JavaFX programs get
JavaFX's jars for the platform they run on.

## Plugins

A plugin is named by its coordinates, one a line, in a `plugins.list`. These are read in this
order, the same order as parley-core's plugin folders:

1. `/usr/local/lib/parley/APP/plugins.list` (Windows: `%ProgramData%\Parley\APP\plugins.list`)
2. `/usr/local/lib/parley/plugins.list`, for every program
3. `~/.parley/APP/plugins.list`
4. `~/.parley/plugins.list`

```
# fsh's plugins
us.bringardner.parley:parley-files-viewer-tui-lanterna:2.0.0
org.example:my-commands:1.2 sha256=9f2c...
```

At the next start the launcher fetches each plugin, and the jars its own list names (a plugin
built with `parley-launcher-maven-plugin` too), and puts them on the class path after the
program's. When a plugin and the program need the same library, the program's version is used,
with a note if the plugin was built with a newer one. A jar the program's build excluded
(`<exclusions>`) is left out of a plugin's dependencies too. `sha256=` pins a plugin's jar. The
plugin folders (`/usr/local/lib/parley/APP/plugins` and the others) still work for jars that
aren't on Maven Central.

## Settings

| Environment variable | |
|---|---|
| `JAVA_HOME` | Its java when that is 11 or later (else the newest Java found) |
| `PARLEY_JAVA` | The java executable to use |
| `PARLEY_REFRESH=1` | Make the command again (after installing a newer Java, say) |
| `PARLEY_OFFLINE=true` | Download nothing |
| `PARLEY_MACHINE_REPOSITORY` | Instead of `/usr/local/lib/parley/repository` |
| `PARLEY_USER_REPOSITORY` | Instead of `~/.m2/repository` |
| `PARLEY_CENTRAL` | Instead of `https://repo1.maven.org/maven2/` (a mirror) |
| `PARLEY_LAUNCHER_HOME` | Instead of `~/.parley/launcher` (the saved commands and native files) |
| `JDK_JAVA_OPTIONS` | More options for java (java itself reads it) |
| `HTTPS_PROXY` | A proxy for the downloads, when Java's `https.proxyHost` isn't set |

## Building a program for it

```xml
<plugin>
  <groupId>us.bringardner.parley</groupId>
  <artifactId>parley-launcher-maven-plugin</artifactId>
  <version>2.0.0</version>
  <executions><execution><goals><goal>list</goal></goals></execution></executions>
  <configuration>
    <mainClass>us.bringardner.fsh.Console</mainClass>
    <options>
      <option>-Xss16m</option>
      <option>-Djava.library.path={native}</option>
      <option>17+:--enable-native-access=ALL-UNNAMED</option>  <!-- only on Java 17 or later -->
    </options>
    <nativeClassifier>{platform}</nativeClassifier>             <!-- fsh-2.0.0-linux-x86_64.jar ... -->
    <wrapper><part>{native}/fshexec</part><part>-j</part></wrapper>
  </configuration>
</plugin>
```

`{native}` is the folder the native jar is unpacked in, `{platform}` is this platform
(`linux-x86_64`, `linux-aarch64`, `macos-x86_64`, `macos-aarch64` or `windows-x86_64`) and `{app}`
is the program's name. An option can start with conditions: `17+:` (only on Java 17 or later) and
`macos:`, `linux:` or `windows:` (only on that platform), as in `macos:-Xdock:name={app}`.
`<nativeFrom>us.bringardner:fsh</nativeFrom>` instead of `nativeClassifier` uses a dependency's
native jar. JavaFX's platform jars are listed as `${javafx.platform}`: the launcher takes the one
for where the program runs (`linux`, `mac-aarch64`, `win` ...), checked against Maven Central's
`.sha1`. A plugin needs only the `list` goal, with no configuration.

## Not yet (phase 2)

1. SNAPSHOTs from GitHub Packages.
2. Filling the machine repository (`parley-launcher fetch APP --machine`), which is also how a
   machine without internet access is filled.
3. Checking Maven Central's GPG signatures.
4. A Windows launcher (`parley-launcher.cmd`). The Java part already runs there.

## Layout

- `parley-launcher`: the launcher (no dependencies). `Main` is what the script runs when a
  command must be made. `Resolver` works it out, `Repositories` finds and downloads the jars, and
  `LaunchList` is the list's format. `src/main/scripts` holds the script and `install.sh`, and
  `target/parley-launcher-VERSION.tar.gz` is what a user unpacks.
- `parley-launcher-maven-plugin`: the `list` goal.

## License

Apache License 2.0
