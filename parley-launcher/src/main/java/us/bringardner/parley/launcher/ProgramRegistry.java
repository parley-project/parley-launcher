package us.bringardner.parley.launcher;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;

/**
 * The programs added to one installation of the launcher (PREFIX, the folder install.sh or
 * install.cmd made). Each program is an app file, PREFIX/apps/NAME.properties:
 *
 * <pre>
 * # fsh: the program the command fsh runs
 * artifact=us.bringardner:fsh:2.0.0
 * main=...          (only when it isn't the main class the jar names)
 * </pre>
 *
 * and a command of that name. On Linux and macOS the command is a link PREFIX/bin/NAME to the
 * launcher's script beside it, and a link to that in the bin folder (/usr/local/bin, ~/.local/bin).
 * On Windows it is a small NAME.cmd in both places that runs parley-launcher.cmd for NAME.
 *
 * This is what parley add, remove, list and update work on.
 */
public final class ProgramRegistry {

	/** The launcher's script on Linux and macOS, in PREFIX/bin */
	public static final String SCRIPT = "parley";
	/** The script's earlier name, still a command (a link to SCRIPT) */
	public static final String EARLIER_SCRIPT = "parley-launcher";
	/** The launcher's script on Windows, in PREFIX\bin */
	public static final String WINDOWS_SCRIPT = "parley-launcher.cmd";

	private final File prefix;
	private final File bin;
	private final boolean windows;

	/**
	 * @param prefix the installation's folder (it holds bin, lib and apps)
	 * @param bin the folder on the PATH the commands are also put in; null when there is none
	 * @param windows true for Windows' kind of command (NAME.cmd), false for links
	 */
	public ProgramRegistry(File prefix, File bin, boolean windows) {
		this.prefix = prefix.getAbsoluteFile();
		this.bin = bin == null ? null : bin.getAbsoluteFile();
		this.windows = windows;
	}

	/** A program's app file (which may not exist) */
	public File appFile(String name) {
		return new File(new File(prefix, "apps"), name + ".properties");
	}

	/** The programs added here, by name */
	public List<Program> list() throws LaunchException {
		List<Program> ret = new ArrayList<>();
		File[] files = new File(prefix, "apps").listFiles((dir, n) -> n.endsWith(".properties"));
		if( files == null ) {
			return ret;
		}
		for(File f : files) {
			String name = f.getName().substring(0, f.getName().length() - ".properties".length());
			ret.add(read(name, f));
		}
		ret.sort(Comparator.comparing(p -> p.name));
		return ret;
	}

	/** The program of that name, or null when none was added */
	public Program find(String name) throws LaunchException {
		File f = appFile(name);
		return f.isFile() ? read(name, f) : null;
	}

	private static Program read(String name, File f) throws LaunchException {
		Properties p = new Properties();
		try (InputStream in = new FileInputStream(f)) {
			p.load(new InputStreamReader(in, StandardCharsets.UTF_8));
		} catch (IOException e) {
			throw new LaunchException("can't read " + f + ": " + e.getMessage(), e);
		}
		String art = p.getProperty("artifact", "").trim();
		try {
			return new Program(name, Coordinates.parse(art), p.getProperty("main"));
		} catch (IllegalArgumentException e) {
			throw new LaunchException(f + " names no artifact (artifact=group:artifact:version)", e);
		}
	}

	/** Why name can't be a program's name, or null when it can */
	static String badName(String name) {
		if( name == null || name.isEmpty() || name.startsWith(".") || name.startsWith("-") ) {
			return "not a program name";
		}
		if( name.equalsIgnoreCase(SCRIPT) || name.equalsIgnoreCase(EARLIER_SCRIPT) ) {
			return "that is the launcher's own name";
		}
		for(char ch : name.toCharArray()) {
			if( Character.isWhitespace(ch) || "/\\:*?<>|\"'".indexOf(ch) >= 0 ) {
				return "not a program name";
			}
		}
		return null;
	}

	/**
	 * Add a program, or replace the one of that name: its app file and its command. A name that is
	 * already some other command in PREFIX/bin or the bin folder is refused, and nothing is written.
	 */
	public void add(Program p) throws LaunchException {
		String bad = badName(p.name);
		if( bad != null ) {
			throw new LaunchException(p.name + ": " + bad);
		}
		for(File c : commands(p.name)) {
			if( exists(c) && !ours(c, p.name) ) {
				throw new LaunchException(c + " is there already, and is not a program " + SCRIPT + " added");
			}
		}
		save(p);
		try {
			File inner = commands(p.name).get(0);
			Files.createDirectories(inner.getParentFile().toPath());
			if( windows ) {
				for(File c : commands(p.name)) {
					Files.createDirectories(c.getParentFile().toPath());
					Files.write(c.toPath(), windowsCommand(p.name).getBytes(StandardCharsets.UTF_8));
				}
			} else {
				// (a link to the script beside it, by name: the folder can be moved)
				link(inner.toPath(), Paths.get(SCRIPT));
				if( commands(p.name).size() > 1 ) {
					File outer = commands(p.name).get(1);
					Files.createDirectories(outer.getParentFile().toPath());
					link(outer.toPath(), inner.toPath());
				}
			}
		} catch (IOException | UnsupportedOperationException e) {
			throw new LaunchException("can't make the command " + p.name + ": " + e, e);
		}
	}

	/** Write a program's app file (add does; update writes only this, the command stays as it is) */
	public void save(Program p) throws LaunchException {
		File f = appFile(p.name);
		StringBuilder b = new StringBuilder("# ").append(p.name).append(": the program the command ").append(p.name)
				.append(" runs\nartifact=").append(p.coordinates).append('\n');
		if( p.mainClass != null ) {
			b.append("main=").append(p.mainClass).append('\n');
		}
		Path tmp = null;
		try {
			Files.createDirectories(f.getParentFile().toPath());
			tmp = Files.createTempFile(f.getParentFile().toPath(), f.getName(), ".part");
			Files.write(tmp, b.toString().getBytes(StandardCharsets.UTF_8));
			Repositories.moveInto(tmp, f.toPath());
			tmp = null;
		} catch (IOException e) {
			throw new LaunchException("can't write " + f + ": " + e.getMessage(), e);
		} finally {
			if( tmp != null ) {
				tmp.toFile().delete();
			}
		}
	}

	/**
	 * Remove a program: its app file, its command, and the command saved for it in launcherHome
	 * (the jars stay in the repositories).
	 *
	 * @param launcherHome the folder the saved commands are in (Locations.launcherHome)
	 * @throws LaunchException when there is no such program
	 */
	public void remove(String name, File launcherHome) throws LaunchException {
		File f = appFile(name);
		if( badName(name) != null || !f.isFile() ) {
			throw new LaunchException("there is no program " + name + " here");
		}
		List<File> commands = commands(name);
		// (the bin folder's first: it is known as ours by the inner one)
		for(int i = commands.size() - 1; i >= 0; i--) {
			File c = commands.get(i);
			if( exists(c) && (i == 0 || ours(c, name)) ) {
				c.delete();
			}
		}
		if( !f.delete() ) {
			throw new LaunchException("can't remove " + f);
		}
		File cache = new File(launcherHome, "cache");
		for(String ext : new String[] { ".args", ".check", ".sig" }) {
			new File(cache, name + ext).delete();
		}
	}

	/** Where a program's command is: in PREFIX/bin, then (when there is one) in the bin folder */
	List<File> commands(String name) {
		String file = windows ? name + ".cmd" : name;
		List<File> ret = new ArrayList<>();
		File inner = new File(new File(prefix, "bin"), file);
		ret.add(inner);
		if( bin != null && !bin.equals(inner.getParentFile()) ) {
			ret.add(new File(bin, file));
		}
		return ret;
	}

	/** What NAME.cmd holds: it runs this installation's parley-launcher.cmd for NAME */
	String windowsCommand(String name) {
		return "@setlocal & set \"PARLEY_APP=" + name + "\" & \"" + prefix.getPath() + "\\bin\\" + WINDOWS_SCRIPT + "\" %*\r\n";
	}

	/** true when something is at f, a link that leads nowhere included */
	private static boolean exists(File f) {
		return Files.exists(f.toPath(), LinkOption.NOFOLLOW_LINKS);
	}

	/** true when the command at f is one this launcher made for name */
	private boolean ours(File f, String name) {
		try {
			if( windows ) {
				return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).contains("PARLEY_APP=");
			}
			if( !Files.isSymbolicLink(f.toPath()) ) {
				return false;
			}
			String target = Files.readSymbolicLink(f.toPath()).toString();
			return target.equals(SCRIPT) || target.equals(EARLIER_SCRIPT)
					|| target.equals(new File(new File(prefix, "bin"), name).getPath());
		} catch (IOException e) {
			return false;
		}
	}

	private static void link(Path link, Path target) throws IOException {
		Files.deleteIfExists(link);
		Files.createSymbolicLink(link, target);
	}
}
