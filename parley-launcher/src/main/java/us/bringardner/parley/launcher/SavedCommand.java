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
 * A program's command as the launcher's script keeps it, so that the next start needs no JVM to
 * work it out: BASE.args, the key (the script's choice of Java and repositories, so a different one
 * makes the command again) then one word of the command a line, and BASE.check, the files that make
 * it out of date when they change ("!" before a path: when it comes to exist; "until SECONDS": at
 * that time; "retry": at the next start). BASE is LAUNCHER-HOME/cache/NAME.
 */
public final class SavedCommand {

	private SavedCommand() {
	}

	/** Where NAME's command is saved, without the .args or .check */
	public static File base(Locations where, String name) {
		return new File(new File(where.launcherHome, "cache"), name);
	}

	/**
	 * Save a command the Resolver worked out.
	 *
	 * @param java the java executable the command runs
	 * @param base where (see base)
	 * @param key the script's key for it
	 */
	public static void write(Resolver.Launch launch, String java, File base, String key) throws LaunchException {
		List<String> checks = new ArrayList<>(launch.checks);
		// (the java too: when it is gone, another is found; this launcher's jar: an upgrade makes
		// the commands again; Maven's settings.xml: it may move the user repository)
		checks.add(0, new File(java).getAbsolutePath());
		File self = selfJar();
		if( self != null ) {
			checks.add(self.getAbsolutePath());
		}
		File settings = new File(new File(System.getProperty("user.home"), ".m2"), "settings.xml");
		checks.add((settings.isFile() ? "" : "!") + settings.getAbsolutePath());
		// ("retry": made again at the next start, when something failed that may work later, or
		// when a SNAPSHOT or a plugins.list changed in this very second, which a shell comparing
		// whole seconds would miss)
		long now = System.currentTimeMillis() / 1000;
		boolean retry = launch.retry;
		for(String c : checks) {
			if( !c.startsWith("!") && (c.contains("-SNAPSHOT") || c.endsWith("plugins.list"))
					&& new File(c).lastModified() / 1000 >= now ) {
				retry = true;
			}
		}
		if( retry ) {
			checks.add("retry");
		}
		List<String> lines = new ArrayList<>();
		lines.add(key == null ? "" : key);
		lines.addAll(launch.command);
		for(String l : lines) {
			if( l.indexOf('\n') >= 0 ) {
				throw new LaunchException("a path with a line break can't be used: " + l);
			}
		}
		try {
			writeLines(new File(base.getPath() + ".check").toPath(), checks);
			// (args last: the script takes it as current only when it is newer than the files it checks)
			writeLines(new File(base.getPath() + ".args").toPath(), lines);
		} catch (IOException e) {
			throw new LaunchException("can't write " + base + ".args: " + e.getMessage(), e);
		}
	}

	/** the jar this class came from, or null */
	private static File selfJar() {
		try {
			java.security.CodeSource cs = SavedCommand.class.getProtectionDomain().getCodeSource();
			if( cs != null && cs.getLocation() != null ) {
				File f = new File(cs.getLocation().toURI());
				return f.isFile() ? f : null;
			}
		} catch (java.net.URISyntaxException | RuntimeException e) {
			// (none)
		}
		return null;
	}

	private static void writeLines(Path p, List<String> lines) throws IOException {
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
