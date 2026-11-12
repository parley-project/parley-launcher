package us.bringardner.parley.launcher;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The Parley programs the launcher knows by name, so that {@code parley add pssh} is enough: each
 * name with its group and artifact, and its main class when that isn't the jar's own. Read from
 * META-INF/parley/programs.list in the launcher's jar, one program a line:
 *
 * <pre>
 * # parley-launcher programs 1
 * pssh       | us.bringardner.parley:parley-ssh |                                    | The Parley SSH client
 * fsh-ide-fx | us.bringardner:fsh-ide           | us.bringardner.fsh.ide.fx.FshIdeFx | The fsh script IDE (JavaFX)
 * </pre>
 */
public final class Catalog {

	/** Where it is in the launcher's jar */
	public static final String RESOURCE = "META-INF/parley/programs.list";

	/** One program the catalog knows */
	public static final class Entry {
		public final String name;
		public final String groupId;
		public final String artifactId;
		/** null: the main class the program's jar names */
		public final String mainClass;
		/** "" when there is none */
		public final String description;

		Entry(String name, String groupId, String artifactId, String mainClass, String description) {
			this.name = name;
			this.groupId = groupId;
			this.artifactId = artifactId;
			this.mainClass = mainClass == null || mainClass.isEmpty() ? null : mainClass;
			this.description = description == null ? "" : description;
		}

		/** group:artifact */
		public String artifact() {
			return groupId + ":" + artifactId;
		}
	}

	private final List<Entry> entries;

	Catalog(List<Entry> entries) {
		this.entries = Collections.unmodifiableList(entries);
	}

	/** The catalog in the launcher's jar; empty when it has none */
	public static Catalog builtIn() {
		try (InputStream in = Catalog.class.getClassLoader().getResourceAsStream(RESOURCE)) {
			return in == null ? new Catalog(new ArrayList<>()) : read(in);
		} catch (IOException e) {
			return new Catalog(new ArrayList<>());
		}
	}

	public static Catalog read(InputStream in) throws IOException {
		List<Entry> ret = new ArrayList<>();
		BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
		String line;
		int n = 0;
		while ((line = r.readLine()) != null) {
			n++;
			String t = line.trim();
			if( t.isEmpty() || t.startsWith("#") ) {
				continue;
			}
			String[] p = t.split("\\|", -1);
			String[] ga = p.length < 2 ? new String[0] : p[1].trim().split(":", -1);
			if( p[0].trim().isEmpty() || ga.length != 2 || ga[0].isEmpty() || ga[1].isEmpty() ) {
				throw new IOException(RESOURCE + " line " + n + ": not NAME | GROUP:ARTIFACT | MAIN | DESCRIPTION: " + line);
			}
			ret.add(new Entry(p[0].trim(), ga[0], ga[1], p.length > 2 ? p[2].trim() : null, p.length > 3 ? p[3].trim() : ""));
		}
		return new Catalog(ret);
	}

	/** The programs, in the order the file lists them */
	public List<Entry> entries() {
		return entries;
	}

	/** The program of that name, or null when the catalog doesn't know it */
	public Entry find(String name) {
		for(Entry e : entries) {
			if( e.name.equals(name) ) {
				return e;
			}
		}
		return null;
	}
}
