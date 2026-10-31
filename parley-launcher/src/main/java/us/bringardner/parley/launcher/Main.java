package us.bringardner.parley.launcher;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * What the launcher script runs when a program's saved command is missing or out of date:
 *
 * <pre>
 * java -jar parley-launcher.jar resolve --app NAME --app-file FILE --java JAVA --cache BASE [--key KEY]
 * </pre>
 *
 * It works out the command (Resolver) and writes it for the script: BASE.args, the key (the script's
 * choice of Java, so a different one makes the command again) then one word of the command a line,
 * and BASE.check, the files that make it out of date when they change (a "!" before a path: when
 * it comes to exist). Exits 1, with the reason on standard error, when the program can't be run.
 *
 * <pre>
 * java -jar parley-launcher.jar choose [GROUP:]ARTIFACT[:VERSION]
 * java -jar parley-launcher.jar versions [GROUP:]ARTIFACT[:VERSION]
 * </pre>
 *
 * choose prints the full coordinates what was typed means (see Chooser), asking which version on
 * standard error when there is more than one; versions lists them all, newest first.
 */
public final class Main {

	public static void main(String[] args) {
		System.exit(run(args));
	}

	static int run(String[] args) {
		String cmd = args.length == 0 ? "--help" : args[0];
		if( "--version".equals(cmd) ) {
			Package p = Main.class.getPackage();
			System.out.println("parley-launcher " + (p.getImplementationVersion() == null ? "(development)" : p.getImplementationVersion()));
			return 0;
		}
		if( "choose".equals(cmd) || "versions".equals(cmd) ) {
			if( args.length != 2 ) {
				System.err.println("usage: parley-launcher " + cmd + " [GROUP:]ARTIFACT[:VERSION]");
				return 2;
			}
			Chooser chooser = new Chooser(new Repositories(Locations.fromEnvironment(), w -> System.err.println("parley-launcher: note: " + w)),
					w -> System.err.println("parley-launcher: note: " + w));
			try {
				if( "versions".equals(cmd) ) {
					List<Chooser.Found> found = chooser.versions(args[1]);
					int def = Chooser.defaultIndex(found);
					for(int i = 0; i < found.size(); i++) {
						System.out.println(found.get(i) + (i == def ? "  [default]" : ""));
					}
					if( found.isEmpty() ) {
						System.err.println("parley-launcher: no version of " + args[1] + " was found");
						return 1;
					}
				} else {
					// (the prompt on standard error, so the script can take the answer from standard output)
					System.out.println(chooser.choose(args[1],
							new java.io.BufferedReader(new java.io.InputStreamReader(System.in, java.nio.charset.Charset.defaultCharset())),
							System.err));
				}
				return 0;
			} catch (LaunchException e) {
				System.err.println("parley-launcher: " + e.getMessage());
				return 1;
			}
		}
		if( !"resolve".equals(cmd) ) {
			System.err.println("usage: parley-launcher resolve --app NAME --app-file FILE --java JAVA --cache BASE [--key KEY]");
			System.err.println("       parley-launcher choose [GROUP:]ARTIFACT[:VERSION]");
			System.err.println("       parley-launcher versions [GROUP:]ARTIFACT[:VERSION]");
			return "--help".equals(cmd) ? 0 : 2;
		}
		String app = null, appFile = null, java = null, cache = null, key = "";
		for(int i = 1; i < args.length; i++) {
			String a = args[i];
			String v = i + 1 < args.length ? args[i + 1] : null;
			switch (a) {
			case "--app": app = v; i++; break;
			case "--app-file": appFile = v; i++; break;
			case "--java": java = v; i++; break;
			case "--cache": cache = v; i++; break;
			case "--key": key = v == null ? "" : v; i++; break;
			default:
				System.err.println("parley-launcher: unknown argument " + a);
				return 2;
			}
		}
		if( app == null || appFile == null || java == null || cache == null ) {
			System.err.println("parley-launcher: resolve needs --app, --app-file, --java and --cache");
			return 2;
		}
		final String name = app;
		try {
			Resolver.Launch launch = new Resolver(Locations.fromEnvironment(),
					w -> System.err.println(name + ": note: " + w)).resolve(app, new File(appFile), java);
			// (the java too: when it is gone, another is found; this launcher's jar: an upgrade makes
			// the commands again; Maven's settings.xml: it may move the user repository)
			launch.checks.add(0, new File(java).getAbsolutePath());
			File self = selfJar();
			if( self != null ) {
				launch.checks.add(self.getAbsolutePath());
			}
			File settings = new File(new File(System.getProperty("user.home"), ".m2"), "settings.xml");
			launch.checks.add((settings.isFile() ? "" : "!") + settings.getAbsolutePath());
			// ("retry": made again at the next start, when something failed that may work later, or
			// when a SNAPSHOT or a plugins.list changed in this very second, which a shell comparing
			// whole seconds would miss)
			long now = System.currentTimeMillis() / 1000;
			boolean retry = launch.retry;
			for(String c : launch.checks) {
				if( !c.startsWith("!") && (c.contains("-SNAPSHOT") || c.endsWith("plugins.list"))
						&& new File(c).lastModified() / 1000 >= now ) {
					retry = true;
				}
			}
			if( retry ) {
				launch.checks.add("retry");
			}
			write(new File(cache + ".check").toPath(), launch.checks);
			List<String> lines = new ArrayList<>();
			lines.add(key);
			lines.addAll(launch.command);
			for(String l : lines) {
				if( l.indexOf('\n') >= 0 ) {
					throw new LaunchException("a path with a line break can't be used: " + l);
				}
			}
			// (args last: the script takes it as current only when it is newer than the files it checks)
			write(new File(cache + ".args").toPath(), lines);
			return 0;
		} catch (LaunchException e) {
			System.err.println(e.getMessage().startsWith(app) ? e.getMessage() : app + ": " + e.getMessage());
			return 1;
		} catch (IOException e) {
			System.err.println(app + ": can't write " + cache + ".args: " + e.getMessage());
			return 1;
		}
	}

	/** the jar this class came from, or null */
	private static File selfJar() {
		try {
			java.security.CodeSource cs = Main.class.getProtectionDomain().getCodeSource();
			if( cs != null && cs.getLocation() != null ) {
				File f = new File(cs.getLocation().toURI());
				return f.isFile() ? f : null;
			}
		} catch (java.net.URISyntaxException | RuntimeException e) {
			// (none)
		}
		return null;
	}

	private static void write(Path p, List<String> lines) throws IOException {
		Files.createDirectories(p.getParent());
		Path tmp = Files.createTempFile(p.getParent(), p.getFileName().toString(), ".part");
		try {
			Files.write(tmp, (String.join("\n", lines) + "\n").getBytes(StandardCharsets.UTF_8));
			Repositories.readable(tmp);
			try {
				Files.move(tmp, p, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
			} catch (AtomicMoveNotSupportedException e) {
				Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING);
			}
		} finally {
			Files.deleteIfExists(tmp);
		}
	}
}
