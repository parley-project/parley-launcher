package us.bringardner.parley.launcher;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * What a program's jar says about running it: META-INF/parley/launcher.list, written by
 * parley-launcher-maven-plugin as the program is built. One setting a line, in order:
 *
 * <pre>
 * # parley-launcher list 1
 * artifact=us.bringardner:fsh:2.0.0
 * main=us.bringardner.fsh.Console
 * java=11
 * option=-Xss16m
 * option=-Djava.library.path=${native}
 * option=17+:--enable-native-access=ALL-UNNAMED
 * native=us.bringardner:fsh:jar:${platform}:2.0.0
 * native.optional=true
 * wrapper=${native}/fshexec
 * wrapper=-j
 * plugins=fsh
 * dependency=net.java.dev.jna:jna:5.19.1 sha256=2f0d...
 * dependency=us.bringardner.parley:parley-core:2.0.0-SNAPSHOT
 * </pre>
 *
 * <ul>
 * <li>{@code artifact}: the program's own jar</li>
 * <li>{@code main}: the class to run; {@code java}: the oldest Java it runs on</li>
 * <li>{@code option}: an option for java, before the class path; {@code 17+:OPTION} only on Java 17
 * or later</li>
 * <li>{@code native}: a jar of this platform's native files (unpacked; {@code ${platform}} is
 * linux-x86_64, macos-aarch64, windows-x86_64 ...), and {@code native.optional} when the program
 * runs without it. An option or wrapper part naming {@code ${native}} (the folder they are unpacked
 * in) is left out when there is none.</li>
 * <li>{@code wrapper}: a program that starts java (its arguments, one a line), put before it</li>
 * <li>{@code plugins}: the name of the plugin settings files (default: the artifact id)</li>
 * <li>{@code exclude}: group:artifact (either may be *) the program's build left out; a plugin's
 * dependency that matches is left out too (a plugin named in a settings file is always loaded)</li>
 * <li>{@code dependency}: a jar on the run-time class path, in class path order, with its SHA-256
 * (none for a SNAPSHOT, which changes with every build)</li>
 * </ul>
 */
public final class LaunchList {

	/** Where it is in a jar */
	public static final String RESOURCE = "META-INF/parley/launcher.list";
	public static final String HEADER = "# parley-launcher list 1";

	/** A jar on the class path, and its SHA-256 (null when not known) */
	public static final class Dependency {
		public final Coordinates coordinates;
		public final String sha256;

		public Dependency(Coordinates coordinates, String sha256) {
			this.coordinates = coordinates;
			this.sha256 = sha256 == null || sha256.isEmpty() ? null : sha256.toLowerCase();
		}

		@Override
		public String toString() {
			return coordinates + (sha256 == null ? "" : " sha256=" + sha256);
		}

		/** A dependency line's value: coordinates, then sha256=HEX when there is one */
		public static Dependency parse(String text) {
			String[] p = text.trim().split("\\s+");
			String sha = null;
			for(int i = 1; i < p.length; i++) {
				if( p[i].startsWith("sha256=") ) {
					sha = p[i].substring(7);
				}
			}
			return new Dependency(Coordinates.parse(p[0]), sha);
		}
	}

	public Coordinates artifact;
	public String mainClass;
	public int minJava = 11;
	public final List<String> options = new ArrayList<>();
	public String nativeArtifact;
	public boolean nativeOptional;
	public final List<String> wrapper = new ArrayList<>();
	public String plugins;
	public final List<Dependency> dependencies = new ArrayList<>();
	public final List<String> excludes = new ArrayList<>();

	/** Whether c matches one of the excludes (group:artifact, either may be *) */
	public boolean excluded(Coordinates c) {
		for(String e : excludes) {
			int i = e.indexOf(':');
			String g = i < 0 ? e : e.substring(0, i);
			String a = i < 0 ? "*" : e.substring(i + 1);
			if( ("*".equals(g) || g.equals(c.groupId)) && ("*".equals(a) || a.equals(c.artifactId)) ) {
				return true;
			}
		}
		return false;
	}

	public static LaunchList read(InputStream in) throws IOException {
		LaunchList ret = new LaunchList();
		BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
		String line;
		int n = 0;
		while ((line = r.readLine()) != null) {
			n++;
			String t = line.trim();
			if( t.isEmpty() || t.startsWith("#") ) {
				continue;
			}
			int eq = t.indexOf('=');
			if( eq < 0 ) {
				throw new IOException(RESOURCE + " line " + n + ": no '=': " + line);
			}
			String key = t.substring(0, eq).trim();
			String value = t.substring(eq + 1).trim();
			try {
				switch (key) {
				case "artifact": ret.artifact = Coordinates.parse(value); break;
				case "main": ret.mainClass = value; break;
				case "java": ret.minJava = Integer.parseInt(value); break;
				case "option": ret.options.add(value); break;
				case "native": ret.nativeArtifact = value; break;
				case "native.optional": ret.nativeOptional = Boolean.parseBoolean(value); break;
				case "wrapper": ret.wrapper.add(value); break;
				case "plugins": ret.plugins = value; break;
				case "dependency": ret.dependencies.add(Dependency.parse(value)); break;
				case "exclude": ret.excludes.add(value); break;
				default:
					// (a later list's settings: left for the launcher that knows them)
				}
			} catch (IllegalArgumentException e) {
				throw new IOException(RESOURCE + " line " + n + ": " + e.getMessage(), e);
			}
		}
		return ret;
	}

	public void write(Writer w) throws IOException {
		w.write(HEADER + "\n");
		if( artifact != null ) {
			w.write("artifact=" + artifact + "\n");
		}
		if( mainClass != null ) {
			w.write("main=" + mainClass + "\n");
		}
		w.write("java=" + minJava + "\n");
		for(String o : options) {
			w.write("option=" + o + "\n");
		}
		if( nativeArtifact != null ) {
			w.write("native=" + nativeArtifact + "\n");
			w.write("native.optional=" + nativeOptional + "\n");
		}
		for(String o : wrapper) {
			w.write("wrapper=" + o + "\n");
		}
		if( plugins != null ) {
			w.write("plugins=" + plugins + "\n");
		}
		for(String e : excludes) {
			w.write("exclude=" + e + "\n");
		}
		for(Dependency d : dependencies) {
			w.write("dependency=" + d + "\n");
		}
	}

	public List<Dependency> getDependencies() {
		return Collections.unmodifiableList(dependencies);
	}
}
