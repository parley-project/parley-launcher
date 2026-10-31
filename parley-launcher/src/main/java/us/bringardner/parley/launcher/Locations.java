package us.bringardner.parley.launcher;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Where the launcher looks and writes. Each can be moved with an environment variable:
 *
 * <pre>
 *   machine repository   /usr/local/lib/parley/repository   PARLEY_MACHINE_REPOSITORY
 *                        (Windows: %ProgramData%\Parley\repository)
 *   user repository      ~/.m2/repository, or settings.xml's  PARLEY_USER_REPOSITORY
 *                        localRepository, as Maven has it
 *   Maven Central        https://repo1.maven.org/maven2/     PARLEY_CENTRAL
 *   SNAPSHOTs            https://central.sonatype.com/repository/maven-snapshots/
 *                        (Central's snapshot repository)     PARLEY_SNAPSHOTS (none: no SNAPSHOT
 *                                                            is downloaded)
 *   the launcher's own   ~/.parley/launcher (cache, native)  PARLEY_LAUNCHER_HOME
 *   system root          /usr/local/lib/parley                (as parley-core's PluginFolders)
 *   user root            ~/.parley
 * </pre>
 *
 * PARLEY_OFFLINE=true: nothing is downloaded.
 */
public final class Locations {

	public static final String DEFAULT_CENTRAL = "https://repo1.maven.org/maven2/";
	public static final String DEFAULT_SNAPSHOTS = "https://central.sonatype.com/repository/maven-snapshots/";

	public final File machineRepository;
	public final File userRepository;
	public final String central;
	/** where a SNAPSHOT that isn't on this machine is downloaded from; null: none is */
	public final String snapshots;
	public final File launcherHome;
	public final File systemRoot;
	public final File userRoot;
	public final boolean offline;

	public Locations(File machineRepository, File userRepository, String central, File launcherHome,
			File systemRoot, File userRoot, boolean offline) {
		this(machineRepository, userRepository, central, null, launcherHome, systemRoot, userRoot, offline);
	}

	public Locations(File machineRepository, File userRepository, String central, String snapshots, File launcherHome,
			File systemRoot, File userRoot, boolean offline) {
		this.machineRepository = machineRepository;
		this.userRepository = userRepository;
		this.central = central.endsWith("/") ? central : central + "/";
		this.snapshots = snapshots == null || snapshots.isEmpty() || "none".equalsIgnoreCase(snapshots) ? null
				: snapshots.endsWith("/") ? snapshots : snapshots + "/";
		this.launcherHome = launcherHome;
		this.systemRoot = systemRoot;
		this.userRoot = userRoot;
		this.offline = offline;
	}

	/** The locations for this machine and user */
	public static Locations fromEnvironment() {
		return fromEnvironment(System.getenv(), System.getProperty("user.home"),
				System.getProperty("os.name", "").toLowerCase().startsWith("windows"));
	}

	public static Locations fromEnvironment(Map<String, String> env, String userHome, boolean windows) {
		File home = new File(userHome);
		File sysRoot = windows
				? new File(env.getOrDefault("ProgramData", "C:\\ProgramData"), "Parley")
				: new File("/usr/local/lib/parley");
		File machine = env.containsKey("PARLEY_MACHINE_REPOSITORY")
				? new File(env.get("PARLEY_MACHINE_REPOSITORY"))
				: new File(sysRoot, "repository");
		File user = env.containsKey("PARLEY_USER_REPOSITORY")
				? new File(env.get("PARLEY_USER_REPOSITORY"))
				: mavenLocalRepository(home);
		String central = env.getOrDefault("PARLEY_CENTRAL", DEFAULT_CENTRAL);
		String snapshots = env.getOrDefault("PARLEY_SNAPSHOTS", DEFAULT_SNAPSHOTS);
		File launcher = env.containsKey("PARLEY_LAUNCHER_HOME")
				? new File(env.get("PARLEY_LAUNCHER_HOME"))
				: new File(new File(home, ".parley"), "launcher");
		boolean offline = "true".equalsIgnoreCase(env.get("PARLEY_OFFLINE"));
		return new Locations(machine, user, central, snapshots, launcher, sysRoot, new File(home, ".parley"), offline);
	}

	private static final Pattern LOCAL_REPOSITORY = Pattern.compile("<localRepository>\\s*([^<]+?)\\s*</localRepository>");

	/** Maven's local repository: settings.xml's localRepository, else ~/.m2/repository */
	static File mavenLocalRepository(File home) {
		File settings = new File(new File(home, ".m2"), "settings.xml");
		if( settings.isFile() ) {
			try {
				String xml = new String(Files.readAllBytes(settings.toPath()), StandardCharsets.UTF_8);
				// (comments out, so a commented-out localRepository isn't taken)
				xml = xml.replaceAll("(?s)<!--.*?-->", "");
				Matcher m = LOCAL_REPOSITORY.matcher(xml);
				if( m.find() ) {
					String path = m.group(1).replace("${user.home}", home.getPath());
					return new File(path);
				}
			} catch (IOException e) {
				// (as if there were none)
			}
		}
		return new File(new File(home, ".m2"), "repository");
	}

	/**
	 * The plugin settings files for an application, in the order they are read: the system's for
	 * the application, the system's shared, the user's for the application, the user's shared.
	 */
	public File[] pluginSettings(String app) {
		return new File[] {
				new File(new File(systemRoot, app), "plugins.list"),
				new File(systemRoot, "plugins.list"),
				new File(new File(userRoot, app), "plugins.list"),
				new File(userRoot, "plugins.list") };
	}

	/** This platform, as native jars are classified: linux-x86_64, macos-aarch64, windows-x86_64 ... */
	public static String platform() {
		String os = System.getProperty("os.name", "").toLowerCase();
		String p;
		if( os.startsWith("mac") || os.startsWith("darwin") ) {
			p = "macos";
		} else if( os.startsWith("windows") ) {
			p = "windows";
		} else {
			p = os.replaceAll("[^a-z0-9]", "");
		}
		String arch = System.getProperty("os.arch", "").toLowerCase();
		switch (arch) {
		case "amd64":
		case "x86_64":
			arch = "x86_64";
			break;
		case "aarch64":
		case "arm64":
			arch = "aarch64";
			break;
		default:
		}
		return p + "-" + arch;
	}
}
