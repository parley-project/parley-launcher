package us.bringardner.parley.launcher;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Works out how to run a program: its jar (named by its app file), the jars its list names, this
 * platform's native files and the plugins its settings files name, each found or downloaded
 * (Repositories), and from them the command line that runs it.
 */
public final class Resolver {

	/** What running the program takes: the command line, and the files it was made from */
	public static final class Launch {
		/** the wrapper (if any), java, its options, -cp, the class path and the main class */
		public final List<String> command = new ArrayList<>();
		/** files that, when changed, mean the command must be made again; "!" + path: must not exist */
		public final List<String> checks = new ArrayList<>();
		/** something failed that may work later (an optional native jar not downloaded): try again next time */
		public boolean retry;
		public final List<String> classPath = new ArrayList<>();
	}

	private static final String NATIVE = "${native}";
	private static final java.util.regex.Pattern MIN_JAVA_OPTION = java.util.regex.Pattern.compile("(\\d+)\\+:(.*)");

	private final Locations where;
	private final Repositories repos;
	private final Consumer<String> warn;

	public Resolver(Locations where, Consumer<String> warn) {
		this.where = where;
		this.warn = warn;
		this.repos = new Repositories(where, warn);
	}

	/** A jar on the class path: what it is and where */
	private static final class Entry {
		final Coordinates coordinates;
		final File file;

		Entry(Coordinates coordinates, File file) {
			this.coordinates = coordinates;
			this.file = file;
		}
	}

	/**
	 * @param app the program's name (fsh): its plugin settings files are named after it unless its
	 *        list names others
	 * @param appFile the app file: artifact=group:artifact:version, and sha256= to pin its jar
	 * @param java the java that will run it (this JVM's version is checked against the program's)
	 */
	public Launch resolve(String app, File appFile, String java) throws LaunchException {
		Properties desc = new Properties();
		try (InputStream in = new FileInputStream(appFile)) {
			desc.load(new InputStreamReader(in, StandardCharsets.UTF_8));
		} catch (IOException e) {
			throw new LaunchException("can't read " + appFile + ": " + e.getMessage(), e);
		}
		String art = desc.getProperty("artifact");
		if( art == null || art.trim().isEmpty() ) {
			throw new LaunchException(appFile + " names no artifact (artifact=group:artifact:version)");
		}
		Coordinates main;
		try {
			main = Coordinates.parse(art);
		} catch (IllegalArgumentException e) {
			throw new LaunchException(appFile + ": " + e.getMessage(), e);
		}

		Launch ret = new Launch();
		ret.checks.add(appFile.getAbsolutePath());
		File mainJar = repos.find(main, desc.getProperty("sha256"), ret.checks);
		LaunchList list = readList(mainJar);
		if( list == null ) {
			throw new LaunchException(main + " (" + mainJar + ") has no " + LaunchList.RESOURCE
					+ ": it was not built with parley-launcher-maven-plugin");
		}
		if( list.mainClass == null ) {
			throw new LaunchException(main + "'s " + LaunchList.RESOURCE + " names no main class");
		}
		int running = Runtime.version().feature();
		if( running < list.minJava ) {
			throw new LaunchException(app + " needs Java " + list.minJava + " or later; " + java + " is Java " + running);
		}

		List<String> errors = new ArrayList<>();
		Map<String, Entry> cp = new LinkedHashMap<>();
		cp.put(main.key(), new Entry(main, mainJar));
		for(LaunchList.Dependency d : list.dependencies) {
			if( cp.containsKey(d.coordinates.key()) ) {
				continue;
			}
			try {
				cp.put(d.coordinates.key(), new Entry(d.coordinates, repos.find(d.coordinates, d.sha256, ret.checks)));
			} catch (LaunchException e) {
				errors.add(e.getMessage());
			}
		}

		// this platform's native files
		File nativeDir = null;
		if( list.nativeArtifact != null ) {
			Coordinates n = Coordinates.parse(list.nativeArtifact.replace("${platform}", Locations.platform()));
			try {
				File jar = repos.find(n, null, ret.checks);
				nativeDir = unpack(n, jar);
				ret.checks.add(jar.getAbsolutePath());
				ret.checks.add(new File(nativeDir, ".unpacked").getAbsolutePath());
			} catch (LaunchException e) {
				if( list.nativeOptional ) {
					warn.accept("running without " + n + " (" + e.getMessage() + ")");
					ret.retry |= e.isTemporary();
				} else {
					errors.add(e.getMessage());
				}
			}
		}

		// the plugins the settings files name
		String pluginsName = list.plugins == null ? app : list.plugins;
		for(File settings : where.pluginSettings(pluginsName)) {
			if( !settings.isFile() ) {
				ret.checks.add("!" + settings.getAbsolutePath());
				continue;
			}
			ret.checks.add(settings.getAbsolutePath());
			List<String> lines;
			try {
				lines = Files.readAllLines(settings.toPath(), StandardCharsets.UTF_8);
			} catch (IOException e) {
				errors.add("can't read " + settings + ": " + e.getMessage());
				continue;
			}
			int no = 0;
			for(String line : lines) {
				no++;
				String t = line.trim();
				if( t.isEmpty() || t.startsWith("#") ) {
					continue;
				}
				try {
					addPlugin(LaunchList.Dependency.parse(t), list, cp, errors, ret.checks);
				} catch (IllegalArgumentException e) {
					errors.add(settings + " line " + no + ": " + e.getMessage());
				}
			}
		}

		if( !errors.isEmpty() ) {
			StringBuilder b = new StringBuilder(app).append(" can't start:");
			for(String e : errors) {
				b.append("\n  ").append(e);
			}
			throw new LaunchException(b.toString());
		}

		// the command
		String nd = nativeDir == null ? null : nativeDir.getAbsolutePath();
		List<String> wrapper = new ArrayList<>();
		for(String w : list.wrapper) {
			wrapper.add(w);
		}
		boolean useWrapper = !wrapper.isEmpty();
		for(int i = 0; useWrapper && i < wrapper.size(); i++) {
			String w = wrapper.get(i);
			if( w.contains(NATIVE) ) {
				if( nd == null ) {
					useWrapper = false;
				} else {
					wrapper.set(i, w.replace(NATIVE, nd));
				}
			}
		}
		if( useWrapper && !new File(wrapper.get(0)).canExecute() ) {
			useWrapper = false;
		}
		if( useWrapper ) {
			ret.command.addAll(wrapper);
		}
		ret.command.add(java);
		for(String o : list.options) {
			// (N+:option: only on Java N or later)
			java.util.regex.Matcher m = MIN_JAVA_OPTION.matcher(o);
			if( m.matches() ) {
				if( running < Integer.parseInt(m.group(1)) ) {
					continue;
				}
				o = m.group(2);
			}
			if( o.contains(NATIVE) ) {
				if( nd == null ) {
					continue;
				}
				o = o.replace(NATIVE, nd);
			}
			ret.command.add(o);
		}
		for(Entry e : cp.values()) {
			ret.classPath.add(e.file.getAbsolutePath());
			ret.checks.add(e.file.getAbsolutePath());
		}
		ret.command.add("-cp");
		ret.command.add(String.join(File.pathSeparator, ret.classPath));
		ret.command.add(list.mainClass);
		return ret;
	}

	/** A plugin, and the jars its own list names that the class path doesn't have yet */
	private void addPlugin(LaunchList.Dependency p, LaunchList program, Map<String, Entry> cp, List<String> errors,
			List<String> checks) {
		if( p.sha256 != null && p.coordinates.isSnapshot() ) {
			warn.accept("plugin " + p.coordinates + ": a SNAPSHOT changes with every build, so its sha256= is not checked");
		}
		Entry have = cp.get(p.coordinates.key());
		if( have != null ) {
			if( !have.coordinates.version.equals(p.coordinates.version) ) {
				warn.accept("plugin " + p.coordinates + ": " + have.coordinates + " is already on the class path; it is used");
			}
			return;
		}
		File jar;
		try {
			jar = repos.find(p.coordinates, p.coordinates.isSnapshot() ? null : p.sha256, checks);
		} catch (LaunchException e) {
			errors.add("plugin " + p.coordinates + ": " + e.getMessage());
			return;
		}
		cp.put(p.coordinates.key(), new Entry(p.coordinates, jar));
		LaunchList list;
		try {
			list = readList(jar);
		} catch (LaunchException e) {
			errors.add("plugin " + p.coordinates + ": " + e.getMessage());
			return;
		}
		if( list == null ) {
			// (a plugin built without the list: its jar alone, as a jar in a plugin folder)
			return;
		}
		for(LaunchList.Dependency d : list.dependencies) {
			if( program.excluded(d.coordinates) ) {
				continue;
			}
			Entry e = cp.get(d.coordinates.key());
			if( e != null ) {
				if( Coordinates.compareVersions(d.coordinates.version, e.coordinates.version) > 0 ) {
					warn.accept("plugin " + p.coordinates + " was built with " + d.coordinates + "; "
							+ e.coordinates.version + " is used, the program's");
				}
				continue;
			}
			try {
				cp.put(d.coordinates.key(), new Entry(d.coordinates, repos.find(d.coordinates, d.sha256, checks)));
			} catch (LaunchException x) {
				errors.add("plugin " + p.coordinates + ": " + x.getMessage());
			}
		}
	}

	/** A jar's launcher list, or null when it has none */
	static LaunchList readList(File jar) throws LaunchException {
		try (ZipFile z = new ZipFile(jar)) {
			ZipEntry e = z.getEntry(LaunchList.RESOURCE);
			if( e == null ) {
				return null;
			}
			try (InputStream in = z.getInputStream(e)) {
				return LaunchList.read(in);
			}
		} catch (IOException e) {
			throw new LaunchException("can't read " + jar + ": " + e.getMessage(), e);
		}
	}

	/**
	 * Unpack a native jar into the launcher's native folder (once for each jar: again when the jar
	 * changes), files made executable, as the programs and libraries in it are.
	 */
	File unpack(Coordinates c, File jar) throws LaunchException {
		File dir = new File(new File(where.launcherHome, "native"),
				c.groupId.replace('.', '/') + "/" + c.artifactId + "/" + c.version + "/" + c.classifier);
		File marker = new File(dir, ".unpacked");
		String stamp = jar.getAbsolutePath() + " " + jar.length() + " " + jar.lastModified();
		File parent = dir.getParentFile();
		Path tmp = null;
		try {
			if( stamp.equals(readStamp(marker)) ) {
				return dir;
			}
			Files.createDirectories(parent.toPath());
			// (one start at a time unpacks: two at once would both move a folder into place)
			try (java.nio.channels.FileChannel ch = java.nio.channels.FileChannel.open(
					parent.toPath().resolve(dir.getName() + ".lock"), java.nio.file.StandardOpenOption.CREATE,
					java.nio.file.StandardOpenOption.WRITE);
					java.nio.channels.FileLock lock = ch.lock()) {
				if( stamp.equals(readStamp(marker)) ) {
					return dir;
				}
				// (unpacked aside and moved in, so a program running from the folder keeps its files)
				tmp = Files.createTempDirectory(parent.toPath(), dir.getName() + ".");
				try (ZipFile z = new ZipFile(jar)) {
					Enumeration<? extends ZipEntry> en = z.entries();
					while (en.hasMoreElements()) {
						ZipEntry e = en.nextElement();
						String name = e.getName();
						if( e.isDirectory() || name.startsWith("META-INF/") ) {
							continue;
						}
						Path out = tmp.resolve(name).normalize();
						if( !out.startsWith(tmp) ) {
							throw new LaunchException(jar + ": " + name + " is outside its folder");
						}
						Files.createDirectories(out.getParent());
						try (InputStream in = z.getInputStream(e)) {
							Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
						}
						out.toFile().setReadable(true, false);
						out.toFile().setExecutable(true, false);
					}
				}
				Files.write(tmp.resolve(".unpacked"), stamp.getBytes(StandardCharsets.UTF_8));
				tmp.toFile().setReadable(true, false);
				tmp.toFile().setExecutable(true, false);
				if( dir.exists() ) {
					File old = new File(parent, dir.getName() + ".old." + System.nanoTime());
					if( !dir.renameTo(old) ) {
						throw new IOException("can't move " + dir + " aside");
					}
					deleteTree(old.toPath());
				}
				Files.move(tmp, dir.toPath());
				tmp = null;
				return dir;
			}
		} catch (IOException e) {
			throw new LaunchException("can't unpack " + jar + " into " + dir + ": " + e.getMessage(), e);
		} finally {
			if( tmp != null ) {
				deleteTree(tmp);
			}
		}
	}

	private static String readStamp(File marker) {
		try {
			return marker.isFile() ? new String(Files.readAllBytes(marker.toPath()), StandardCharsets.UTF_8) : null;
		} catch (IOException e) {
			return null;
		}
	}

	private static void deleteTree(Path p) {
		try (java.util.stream.Stream<Path> s = Files.walk(p)) {
			s.sorted(java.util.Comparator.reverseOrder()).forEach(x -> x.toFile().delete());
		} catch (IOException e) {
			// (left behind: harmless)
		}
	}
}
