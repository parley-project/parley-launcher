package us.bringardner.parley.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.StringWriter;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.sun.net.httpserver.HttpServer;

/** The launcher against a stand-in for Maven Central (a local HTTP server) and repositories in a temporary folder */
class LauncherTest {

	@TempDir
	Path tmp;

	HttpServer server;
	/** what the stand-in serves: path under /maven2/ -> bytes */
	final Map<String, byte[]> central = new ConcurrentHashMap<>();
	/** how many times each path was asked for */
	final Map<String, AtomicInteger> asked = new ConcurrentHashMap<>();
	/** paths answered 429 this many times first */
	final Map<String, AtomicInteger> busy = new ConcurrentHashMap<>();
	final List<String> notes = new ArrayList<>();
	Locations where;

	@BeforeEach
	void start() throws IOException {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/maven2/", x -> {
			String p = x.getRequestURI().getPath().substring("/maven2/".length());
			asked.computeIfAbsent(p, k -> new AtomicInteger()).incrementAndGet();
			AtomicInteger b = busy.get(p);
			if( b != null && b.getAndDecrement() > 0 ) {
				x.getResponseHeaders().add("Retry-After", "0");
				x.sendResponseHeaders(429, -1);
				x.close();
				return;
			}
			byte[] data = central.get(p);
			if( data == null ) {
				x.sendResponseHeaders(404, -1);
			} else {
				x.sendResponseHeaders(200, data.length);
				try (OutputStream o = x.getResponseBody()) {
					o.write(data);
				}
			}
			x.close();
		});
		server.start();
		where = locations(false);
	}

	@AfterEach
	void stop() {
		server.stop(0);
	}

	Locations locations(boolean offline) {
		return new Locations(tmp.resolve("machine").toFile(), tmp.resolve("user").toFile(),
				"http://127.0.0.1:" + server.getAddress().getPort() + "/maven2", tmp.resolve("home").toFile(),
				tmp.resolve("sys").toFile(), tmp.resolve("userroot").toFile(), offline);
	}

	/** a jar with these entries (name -> text) */
	static byte[] jar(String... nameText) throws IOException {
		java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
		try (ZipOutputStream z = new ZipOutputStream(bo)) {
			for(int i = 0; i < nameText.length; i += 2) {
				z.putNextEntry(new ZipEntry(nameText[i]));
				z.write(nameText[i + 1].getBytes(StandardCharsets.UTF_8));
				z.closeEntry();
			}
		}
		return bo.toByteArray();
	}

	static String sha(byte[] data, String alg) throws Exception {
		Path f = Files.createTempFile("sha", ".bin");
		try {
			Files.write(f, data);
			return Repositories.digest(f, alg);
		} finally {
			Files.delete(f);
		}
	}

	/** put c on the stand-in, with its .sha1 and a POM */
	void publish(Coordinates c, byte[] data) throws Exception {
		central.put(c.path(), data);
		central.put(c.path() + ".sha1", sha(data, "SHA-1").getBytes(StandardCharsets.UTF_8));
		byte[] pom = "<project/>".getBytes(StandardCharsets.UTF_8);
		central.put(c.pomPath(), pom);
		central.put(c.pomPath() + ".sha1", sha(pom, "SHA-1").getBytes(StandardCharsets.UTF_8));
	}

	void put(File repo, Coordinates c, byte[] data) throws IOException {
		File f = new File(repo, c.path());
		f.getParentFile().mkdirs();
		Files.write(f.toPath(), data);
	}

	// ---- Coordinates, the list

	@Test
	void coordinates() {
		Coordinates c = Coordinates.parse("us.bringardner:fsh:jar:linux-x86_64:2.0.0");
		assertEquals("us/bringardner/fsh/2.0.0/fsh-2.0.0-linux-x86_64.jar", c.path());
		assertEquals("us/bringardner/fsh/2.0.0/fsh-2.0.0.pom", c.pomPath());
		assertEquals("us.bringardner:fsh:jar:linux-x86_64:2.0.0", c.toString());
		assertEquals("org.x:y:1.0", Coordinates.parse("org.x:y:1.0").toString());
		assertTrue(Coordinates.parse("a:b:1.0-SNAPSHOT").isSnapshot());
		assertThrows(IllegalArgumentException.class, () -> Coordinates.parse("a:b"));
		assertTrue(Coordinates.compareVersions("5.19.1", "5.9") > 0);
		assertTrue(Coordinates.compareVersions("2.0.0", "2.0.0-SNAPSHOT") > 0);
		assertTrue(Coordinates.compareVersions("1.0", "1.0.1") < 0);
		assertEquals(0, Coordinates.compareVersions("1.8.1-jdk17", "1.8.1-jdk17"));
	}

	@Test
	void listRoundTrip() throws IOException {
		LaunchList l = new LaunchList();
		l.artifact = Coordinates.parse("g:a:1.0");
		l.mainClass = "g.Main";
		l.minJava = 17;
		l.options.add("-Djava.library.path=${native}");
		l.nativeArtifact = "g:a:jar:${platform}:1.0";
		l.nativeOptional = true;
		l.wrapper.add("${native}/exec");
		l.dependencies.add(new LaunchList.Dependency(Coordinates.parse("x:y:2.0"), "ABC"));
		l.dependencies.add(new LaunchList.Dependency(Coordinates.parse("x:z:2.0-SNAPSHOT"), null));
		StringWriter w = new StringWriter();
		l.write(w);
		LaunchList r = LaunchList.read(new ByteArrayInputStream(w.toString().getBytes(StandardCharsets.UTF_8)));
		assertEquals(l.artifact, r.artifact);
		assertEquals("g.Main", r.mainClass);
		assertEquals(17, r.minJava);
		assertEquals(l.options, r.options);
		assertEquals(l.nativeArtifact, r.nativeArtifact);
		assertTrue(r.nativeOptional);
		assertEquals(l.wrapper, r.wrapper);
		assertEquals(2, r.dependencies.size());
		assertEquals("abc", r.dependencies.get(0).sha256);
		assertNull(r.dependencies.get(1).sha256);
	}

	// ---- Repositories

	@Test
	void downloadsIntoTheUserRepositoryAsMavenDoes() throws Exception {
		Coordinates c = Coordinates.parse("org.x:lib:1.0");
		byte[] data = jar("a.txt", "a");
		publish(c, data);
		File f = new Repositories(where, notes::add).find(c, sha(data, "SHA-256"));
		assertEquals(new File(where.userRepository, c.path()), f);
		File dir = f.getParentFile();
		assertTrue(new File(dir, "lib-1.0.jar.sha1").isFile());
		assertTrue(new File(dir, "lib-1.0.pom").isFile());
		String remote = new String(Files.readAllBytes(new File(dir, "_remote.repositories").toPath()), StandardCharsets.UTF_8);
		assertTrue(remote.contains("lib-1.0.jar>central="), remote);
		assertTrue(remote.contains("lib-1.0.pom>central="), remote);
		String[] left = dir.list((d, n) -> n.endsWith(".part"));
		assertEquals(0, left.length);
		// (found the second time, not downloaded again)
		new Repositories(where, notes::add).find(c, sha(data, "SHA-256"));
		assertEquals(1, asked.get(c.path()).get());
	}

	@Test
	void theMachineRepositoryComesFirst() throws Exception {
		Coordinates c = Coordinates.parse("org.x:lib:1.0");
		byte[] data = jar("a.txt", "a");
		put(where.machineRepository, c, data);
		put(where.userRepository, c, data);
		assertEquals(new File(where.machineRepository, c.path()), new Repositories(where, notes::add).find(c, sha(data, "SHA-256")));
	}

	@Test
	void aSnapshotComesFromTheUserRepositoryFirst() throws Exception {
		Coordinates c = Coordinates.parse("org.x:lib:1.0-SNAPSHOT");
		put(where.machineRepository, c, jar("a", "old"));
		put(where.userRepository, c, jar("a", "new"));
		assertEquals(new File(where.userRepository, c.path()), new Repositories(where, notes::add).find(c, null));
	}

	@Test
	void aMissingSnapshotIsNotDownloaded() throws Exception {
		Coordinates c = Coordinates.parse("org.x:lib:1.0-SNAPSHOT");
		publish(c, jar("a", "a"));
		LaunchException e = assertThrows(LaunchException.class, () -> new Repositories(where, notes::add).find(c, null));
		assertTrue(e.getMessage().contains("mvn install in lib's project"), e.getMessage());
		assertNull(asked.get(c.path()));
	}

	@Test
	void aDamagedCopyIsReplaced() throws Exception {
		Coordinates c = Coordinates.parse("org.x:lib:1.0");
		byte[] data = jar("a.txt", "a");
		publish(c, data);
		put(where.userRepository, c, jar("a.txt", "changed"));
		File f = new Repositories(where, notes::add).find(c, sha(data, "SHA-256"));
		assertEquals(sha(data, "SHA-256"), Repositories.digest(f.toPath(), "SHA-256"));
		assertEquals(1, notes.size(), notes.toString());
	}

	@Test
	void aDownloadThatDoesNotMatchIsRefused() throws Exception {
		Coordinates c = Coordinates.parse("org.x:lib:1.0");
		publish(c, jar("a.txt", "something else"));
		LaunchException e = assertThrows(LaunchException.class,
				() -> new Repositories(where, notes::add).find(c, sha(jar("a.txt", "a"), "SHA-256")));
		assertTrue(e.getMessage().contains("not the one the program was built with"), e.getMessage());
		assertFalse(new File(where.userRepository, c.path()).exists());
	}

	@Test
	void withoutAChecksumCentralsSha1IsTheCheck() throws Exception {
		Coordinates c = Coordinates.parse("org.x:lib:1.0");
		publish(c, jar("a.txt", "a"));
		central.put(c.path() + ".sha1", "0000".getBytes(StandardCharsets.UTF_8));
		assertThrows(LaunchException.class, () -> new Repositories(where, notes::add).find(c, null));
		publish(c, jar("a.txt", "a"));
		assertNotNull(new Repositories(where, notes::add).find(c, null));
	}

	@Test
	void busyIsTriedAgain() throws Exception {
		Coordinates c = Coordinates.parse("org.x:lib:1.0");
		byte[] data = jar("a.txt", "a");
		publish(c, data);
		busy.put(c.path(), new AtomicInteger(2));
		assertNotNull(new Repositories(where, notes::add).find(c, sha(data, "SHA-256")));
		assertEquals(3, asked.get(c.path()).get());
	}

	@Test
	void offlineDownloadsNothing() throws Exception {
		Coordinates c = Coordinates.parse("org.x:lib:1.0");
		publish(c, jar("a.txt", "a"));
		LaunchException e = assertThrows(LaunchException.class, () -> new Repositories(locations(true), notes::add).find(c, null));
		assertTrue(e.getMessage().contains("PARLEY_OFFLINE"), e.getMessage());
		assertNull(asked.get(c.path()));
	}

	@Test
	void notOnCentral() {
		LaunchException e = assertThrows(LaunchException.class,
				() -> new Repositories(where, notes::add).find(Coordinates.parse("org.x:none:1.0"), null));
		assertTrue(e.getMessage().contains("is not on Maven Central"), e.getMessage());
	}

	// ---- Resolver

	/** an app jar with this list, on the stand-in; its app file */
	File app(String list) throws Exception {
		Coordinates c = Coordinates.parse("org.x:app:1.0");
		publish(c, jar(LaunchList.RESOURCE, list, "org/x/Main.class", "x"));
		File f = tmp.resolve("app.properties").toFile();
		Files.write(f.toPath(), "artifact=org.x:app:1.0\n".getBytes(StandardCharsets.UTF_8));
		return f;
	}

	@Test
	void theCommand() throws Exception {
		byte[] lib = jar("l", "l");
		publish(Coordinates.parse("org.x:lib:2.0"), lib);
		String platform = Locations.platform();
		Coordinates nat = Coordinates.parse("org.x:app:jar:" + platform + ":1.0");
		publish(nat, jar("exec", "#!/bin/sh\n", "libx.so", "x"));
		File appFile = app(LaunchList.HEADER + "\nmain=org.x.Main\njava=11\noption=-Xss16m\n"
				+ "option=-Djava.library.path=${native}\noption=999+:--not-on-this-java\noption=11+:-Don11=yes\n"
				+ "native=org.x:app:jar:${platform}:1.0\nnative.optional=true\nwrapper=${native}/exec\nwrapper=-j\n"
				+ "dependency=org.x:lib:2.0 sha256=" + sha(lib, "SHA-256") + "\n");
		Resolver.Launch l = new Resolver(where, notes::add).resolve("app", appFile, "/j/bin/java");
		File nd = new File(where.launcherHome, "native/org/x/app/1.0/" + platform);
		List<String> want = new ArrayList<>();
		want.add(new File(nd, "exec").getAbsolutePath());
		want.add("-j");
		want.add("/j/bin/java");
		want.add("-Xss16m");
		want.add("-Djava.library.path=" + nd.getAbsolutePath());
		want.add("-Don11=yes");
		want.add("-cp");
		want.add(new File(where.userRepository, "org/x/app/1.0/app-1.0.jar").getAbsolutePath() + File.pathSeparator
				+ new File(where.userRepository, "org/x/lib/2.0/lib-2.0.jar").getAbsolutePath());
		want.add("org.x.Main");
		assertEquals(want, l.command);
		assertTrue(new File(nd, "libx.so").isFile());
		assertTrue(new File(nd, "exec").canExecute());
		// (the plugin settings files that aren't there are checked for, so adding one remakes the command)
		assertTrue(l.checks.contains("!" + where.pluginSettings("app")[3].getAbsolutePath()), l.checks.toString());
	}

	@Test
	void withoutTheNativeJarItsOptionsAndWrapperAreLeftOut() throws Exception {
		File appFile = app(LaunchList.HEADER + "\nmain=org.x.Main\noption=-Djava.library.path=${native}\noption=-Xss16m\n"
				+ "native=org.x:app:jar:${platform}:1.0\nnative.optional=true\nwrapper=${native}/exec\n");
		Resolver.Launch l = new Resolver(where, notes::add).resolve("app", appFile, "java");
		assertEquals("java", l.command.get(0));
		assertEquals("-Xss16m", l.command.get(1));
		assertEquals(1, notes.size(), notes.toString());
	}

	@Test
	void everyMissingJarIsNamed() throws Exception {
		File appFile = app(LaunchList.HEADER + "\nmain=org.x.Main\ndependency=org.x:a:1.0-SNAPSHOT\ndependency=org.x:b:1.0\n");
		LaunchException e = assertThrows(LaunchException.class, () -> new Resolver(where, notes::add).resolve("app", appFile, "java"));
		assertTrue(e.getMessage().startsWith("app can't start:"), e.getMessage());
		assertTrue(e.getMessage().contains("org.x:a:1.0-SNAPSHOT"), e.getMessage());
		assertTrue(e.getMessage().contains("org.x:b:1.0"), e.getMessage());
	}

	@Test
	void aJavaTooOld() throws Exception {
		File appFile = app(LaunchList.HEADER + "\nmain=org.x.Main\njava=999\n");
		LaunchException e = assertThrows(LaunchException.class, () -> new Resolver(where, notes::add).resolve("app", appFile, "java"));
		assertTrue(e.getMessage().contains("needs Java 999"), e.getMessage());
	}

	@Test
	void pluginsAndTheirListsTheProgramsVersionWins() throws Exception {
		byte[] lib2 = jar("l", "2");
		publish(Coordinates.parse("org.x:lib:2.0"), lib2);
		byte[] other = jar("o", "o");
		publish(Coordinates.parse("org.x:other:1.0"), other);
		Coordinates plugin = Coordinates.parse("org.p:plugin:1.0");
		publish(plugin, jar(LaunchList.RESOURCE, LaunchList.HEADER + "\ndependency=org.no:left-out:1.0\ndependency=org.x:lib:3.0\ndependency=org.x:other:1.0 sha256="
				+ sha(other, "SHA-256") + "\n"));
		File appFile = app(LaunchList.HEADER + "\nmain=org.x.Main\nexclude=org.no:*\ndependency=org.x:lib:2.0 sha256=" + sha(lib2, "SHA-256") + "\n");
		File settings = where.pluginSettings("app")[2];
		settings.getParentFile().mkdirs();
		Files.write(settings.toPath(), "# plugins\norg.p:plugin:1.0\n".getBytes(StandardCharsets.UTF_8));
		Resolver.Launch l = new Resolver(where, notes::add).resolve("app", appFile, "java");
		String cp = l.command.get(l.command.indexOf("-cp") + 1);
		assertTrue(cp.contains("lib-2.0.jar") && !cp.contains("lib-3.0.jar"), cp);
		assertTrue(cp.endsWith("plugin-1.0.jar" + File.pathSeparator + new File(where.userRepository, "org/x/other/1.0/other-1.0.jar").getAbsolutePath()), cp);
		assertEquals(1, notes.size(), notes.toString());
		assertTrue(notes.get(0).contains("was built with org.x:lib:3.0"), notes.get(0));
		assertTrue(l.checks.contains(settings.getAbsolutePath()));
	}

	@Test
	void aPomThatDoesNotMatchIsNotKept() throws Exception {
		Coordinates c = Coordinates.parse("org.x:lib:1.0");
		byte[] data = jar("a.txt", "a");
		publish(c, data);
		central.put(c.pomPath(), "<html>proxy error</html>".getBytes(StandardCharsets.UTF_8));
		new Repositories(where, notes::add).find(c, sha(data, "SHA-256"));
		assertFalse(new File(where.userRepository, c.pomPath()).exists());
		assertEquals(1, notes.size(), notes.toString());
	}

	@Test
	void parentPomsAreSavedForMaven() throws Exception {
		Coordinates c = Coordinates.parse("org.x:lib:1.0");
		byte[] data = jar("a.txt", "a");
		publish(c, data);
		byte[] pom = "<project><parent><groupId>org.x</groupId><artifactId>parent</artifactId><version>3</version></parent></project>"
				.getBytes(StandardCharsets.UTF_8);
		central.put(c.pomPath(), pom);
		central.put(c.pomPath() + ".sha1", sha(pom, "SHA-1").getBytes(StandardCharsets.UTF_8));
		publish(Coordinates.parse("org.x:parent:pom:3"), "<project/>".getBytes(StandardCharsets.UTF_8));
		new Repositories(where, notes::add).find(c, sha(data, "SHA-256"));
		assertTrue(new File(where.userRepository, "org/x/parent/3/parent-3.pom").isFile());
	}

	@Test
	void whatTheCommandDependsOn() throws Exception {
		// (a release in the user repository: its place in the machine repository is watched; a
		// download the same; never the user repository's own path, or every start would remake it)
		Coordinates c = Coordinates.parse("org.x:lib:1.0");
		byte[] data = jar("a.txt", "a");
		publish(c, data);
		List<String> checks = new ArrayList<>();
		new Repositories(where, notes::add).find(c, sha(data, "SHA-256"), checks);
		assertEquals(1, checks.size(), checks.toString());
		assertEquals("!" + new File(where.machineRepository, c.path()).getAbsolutePath(), checks.get(0));
		checks.clear();
		new Repositories(where, notes::add).find(c, sha(data, "SHA-256"), checks);
		assertEquals("!" + new File(where.machineRepository, c.path()).getAbsolutePath(), checks.get(0));
		// (a SNAPSHOT from the machine repository: the user repository, where mvn install puts it, is watched)
		Coordinates s = Coordinates.parse("org.x:snap:1.0-SNAPSHOT");
		put(where.machineRepository, s, jar("a", "a"));
		checks.clear();
		new Repositories(where, notes::add).find(s, null, checks);
		assertEquals("!" + new File(where.userRepository, s.path()).getAbsolutePath(), checks.get(0));
	}

	@Test
	void anOptionalNativeJarThatFailedForNowIsTriedAgain() throws Exception {
		File appFile = app(LaunchList.HEADER + "\nmain=org.x.Main\nnative=org.x:app:jar:${platform}:1.0\nnative.optional=true\n");
		Coordinates nat = Coordinates.parse("org.x:app:jar:" + Locations.platform() + ":1.0");
		Resolver.Launch l = new Resolver(where, notes::add).resolve("app", appFile, "java");
		assertFalse(l.retry);
		assertTrue(l.checks.contains("!" + new File(where.userRepository, nat.path()).getAbsolutePath()), l.checks.toString());
		busy.put(nat.path(), new AtomicInteger(100));
		central.put(nat.path(), jar("x", "x"));
		l = new Resolver(where, notes::add).resolve("app", appFile, "java");
		assertTrue(l.retry);
	}

	@Test
	void mavenSettingsLocalRepository() throws IOException {
		File home = tmp.resolve("h").toFile();
		assertEquals(new File(home, ".m2/repository"), Locations.mavenLocalRepository(home));
		new File(home, ".m2").mkdirs();
		Files.write(new File(home, ".m2/settings.xml").toPath(), ("<settings><!-- <localRepository>/no</localRepository> -->"
				+ "<localRepository>${user.home}/repo</localRepository></settings>").getBytes(StandardCharsets.UTF_8));
		assertEquals(new File(home.getPath() + "/repo"), Locations.mavenLocalRepository(home));
	}
}
