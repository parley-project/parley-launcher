package us.bringardner.parley.launcher;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Works out a program's full coordinates from what was typed for parley-launcher add, where the
 * group and the version may be left out:
 *
 * <pre>
 *   ARTIFACT                    fsh
 *   ARTIFACT:VERSION            fsh:2.0.0          (the second part starts with a digit)
 *   GROUP:ARTIFACT              us.bringardner.parley:parley-ssh
 *   GROUP:ARTIFACT:VERSION      as before: taken as it is, nothing looked up
 * </pre>
 *
 * Without a group, the artifact is looked for in us.bringardner, then us.bringardner.parley.
 * Without a version, the versions found (on Maven Central, and in the machine and user
 * repositories, where mvn install puts a SNAPSHOT) are listed, newest first, and the person picks
 * one; the default is the newest release, or the newest SNAPSHOT when there is no release.
 */
public final class Chooser {

	/** The groups tried, in this order, when none is given */
	public static final List<String> GROUPS = Collections.unmodifiableList(Arrays.asList("us.bringardner", "us.bringardner.parley"));

	/** One version found: its coordinates, and whether its jar is on this machine */
	public static final class Found {
		public final Coordinates coordinates;
		public final boolean installed;

		Found(Coordinates coordinates, boolean installed) {
			this.coordinates = coordinates;
			this.installed = installed;
		}

		@Override
		public String toString() {
			return coordinates + (installed ? " (installed)" : "");
		}
	}

	private final Repositories repositories;
	private final Consumer<String> warn;

	public Chooser(Repositories repositories, Consumer<String> warn) {
		this.repositories = repositories;
		this.warn = warn;
	}

	/** What was typed, split: group (null: not given), artifact, version (null: not given) */
	static String[] split(String spec) throws LaunchException {
		String[] p = spec.trim().split(":", -1);
		for(String s : p) {
			if( s.isEmpty() ) {
				throw new LaunchException(spec + " is not [GROUP:]ARTIFACT[:VERSION]");
			}
		}
		switch (p.length) {
		case 1:
			return new String[] { null, p[0], null };
		case 2:
			return Character.isDigit(p[1].charAt(0)) ? new String[] { null, p[0], p[1] } : new String[] { p[0], p[1], null };
		case 3:
			return p;
		default:
			throw new LaunchException(spec + " is not [GROUP:]ARTIFACT[:VERSION]");
		}
	}

	/**
	 * Every version that matches what was typed, newest first (for the same version, in the order of
	 * GROUPS). Empty when there is none.
	 */
	public List<Found> versions(String spec) throws LaunchException {
		String[] s = split(spec);
		List<String> groups = s[0] == null ? GROUPS : Collections.singletonList(s[0]);
		List<Found> ret = new ArrayList<>();
		LaunchException unreachable = null;
		for(String g : groups) {
			Set<String> local = repositories.localVersions(g, s[1]);
			List<String> all = new ArrayList<>(local);
			try {
				for(String v : repositories.centralVersions(g, s[1])) {
					if( !all.contains(v) ) {
						all.add(v);
					}
				}
			} catch (LaunchException e) {
				unreachable = e;
			}
			for(String v : all) {
				if( s[2] == null || s[2].equals(v) ) {
					ret.add(new Found(new Coordinates(g, s[1], "jar", "", v), local.contains(v)));
				}
			}
		}
		if( unreachable != null ) {
			if( ret.isEmpty() ) {
				throw new LaunchException("can't list the versions of " + spec + " on Maven Central: " + unreachable.getMessage(), unreachable);
			}
			warn.accept("Maven Central can't be reached (" + unreachable.getMessage() + "): only the versions on this machine are listed");
		}
		// (stable: for the same version, us.bringardner before us.bringardner.parley)
		ret.sort((a, b) -> Coordinates.compareVersions(b.coordinates.version, a.coordinates.version));
		return ret;
	}

	/** Which of found is the default: the newest release, else the newest (a SNAPSHOT) */
	public static int defaultIndex(List<Found> found) {
		for(int i = 0; i < found.size(); i++) {
			if( !found.get(i).coordinates.isSnapshot() ) {
				return i;
			}
		}
		return 0;
	}

	/**
	 * The coordinates what was typed means: as it is when it names group, artifact and version;
	 * else the one match, or the one picked from the list (the prompt on out, the answer read from
	 * in; nothing typed, or no answer at all, takes the default)
	 */
	public Coordinates choose(String spec, BufferedReader in, PrintStream out) throws LaunchException {
		String[] s = split(spec);
		if( s[0] != null && s[2] != null ) {
			return new Coordinates(s[0], s[1], "jar", "", s[2]);
		}
		List<Found> found = versions(spec);
		if( found.isEmpty() ) {
			String where = s[0] == null ? String.join(" or ", GROUPS) : s[0];
			throw new LaunchException(s[1] + (s[2] == null ? "" : " " + s[2]) + " was found neither on Maven Central nor on this machine (group "
					+ where + ")" + (s[2] != null && s[2].endsWith("-SNAPSHOT") ? ": run mvn install in its project" : ""));
		}
		if( found.size() == 1 ) {
			return found.get(0).coordinates;
		}
		int def = defaultIndex(found);
		out.println("Versions of " + s[1] + " (newest first):");
		for(int i = 0; i < found.size(); i++) {
			out.printf("  %2d) %s%s%n", i + 1, found.get(i), i == def ? "  [default]" : "");
		}
		for(;;) {
			out.print("Which one? [" + (def + 1) + "] ");
			out.flush();
			String line;
			try {
				line = in.readLine();
			} catch (IOException e) {
				line = null;
			}
			if( line == null ) {
				out.println();
				out.println("(no answer: " + found.get(def).coordinates + ")");
				return found.get(def).coordinates;
			}
			line = line.trim();
			if( line.isEmpty() ) {
				return found.get(def).coordinates;
			}
			if( line.matches("\\d+") ) {
				int n = Integer.parseInt(line);
				if( n >= 1 && n <= found.size() ) {
					return found.get(n - 1).coordinates;
				}
			}
			for(Found f : found) {
				if( f.coordinates.version.equals(line) || f.coordinates.toString().equals(line) ) {
					return f.coordinates;
				}
			}
			out.println("Type a number from 1 to " + found.size() + ", or press Enter for " + (def + 1) + ".");
		}
	}
}
