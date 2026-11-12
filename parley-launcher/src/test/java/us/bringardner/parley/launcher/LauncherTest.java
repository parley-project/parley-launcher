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
		// (on Windows a wrapper that is no .exe, .com, .bat or .cmd is left out)
		if( !platform.startsWith("windows-") ) {
			want.add(new File(nd, "exec").getAbsolutePath());
			want.add("-j");
		}
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
	void aWrapperWithoutAWindowsProgramNameIsNotRunOnWindows() throws Exception {
		File plain = tmp.resolve("fshexec").toFile();
		Files.write(plain.toPath(), "x".getBytes(StandardCharsets.UTF_8));
		assertTrue(plain.setExecutable(true));
		File exe = tmp.resolve("wrap.EXE").toFile();
		Files.write(exe.toPath(), "x".getBytes(StandardCharsets.UTF_8));
		assertTrue(exe.setExecutable(true));
		assertTrue(Resolver.canRun(plain, false));
		assertFalse(Resolver.canRun(plain, true));
		assertTrue(Resolver.canRun(exe, true));
		assertFalse(Resolver.canRun(tmp.resolve("missing.exe").toFile(), true));
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
	void anotherMainClassPlatformOptionsAndTheAppsName() throws Exception {
		String other = Locations.platform().startsWith("macos-") ? "linux" : "macos";
		File appFile = app(LaunchList.HEADER + "\nmain=org.x.Main\noption=" + Locations.platform().split("-")[0]
				+ ":-Dname=${app}\noption=" + other + ":-Dno=1\noption=" + Locations.platform().split("-")[0] + ":999+:-Dno=2\n");
		Files.write(appFile.toPath(), "artifact=org.x:app:1.0\nmain=org.x.fx.Other\n".getBytes(StandardCharsets.UTF_8));
		Resolver.Launch l = new Resolver(where, notes::add).resolve("app-fx", appFile, "java");
		assertEquals("org.x.fx.Other", l.command.get(l.command.size() - 1));
		assertTrue(l.command.contains("-Dname=app-fx"), l.command.toString());
		assertFalse(l.command.contains("-Dno=1"), l.command.toString());
		assertFalse(l.command.contains("-Dno=2"), l.command.toString());
	}

	@Test
	void javafxForThisPlatform() throws Exception {
		String fx = Resolver.javafxPlatform(Locations.platform());
		Coordinates base = Coordinates.parse("org.openjfx:javafx-base:jar:" + fx + ":21");
		publish(base, jar("fx", fx));
		File appFile = app(LaunchList.HEADER + "\nmain=org.x.Main\ndependency=org.openjfx:javafx-base:jar:${javafx.platform}:21\n");
		Resolver.Launch l = new Resolver(where, notes::add).resolve("app", appFile, "java");
		String cp = l.command.get(l.command.indexOf("-cp") + 1);
		assertTrue(cp.contains("javafx-base-21-" + fx + ".jar"), cp);
		assertEquals("mac-aarch64", Resolver.javafxPlatform("macos-aarch64"));
		assertEquals("win", Resolver.javafxPlatform("windows-x86_64"));
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

	// ---- picking the group and the version (parley-launcher add NAME [GROUP:]ARTIFACT[:VERSION])

	void metadata(String group, String artifact, String... versions) {
		StringBuilder b = new StringBuilder("<metadata><groupId>" + group + "</groupId><artifactId>" + artifact
				+ "</artifactId><versioning><latest>x</latest><release>x</release><versions>");
		for(String v : versions) {
			b.append("<version>").append(v).append("</version>");
		}
		b.append("</versions></versioning></metadata>");
		central.put(group.replace('.', '/') + "/" + artifact + "/maven-metadata.xml", b.toString().getBytes(StandardCharsets.UTF_8));
	}

	Coordinates choose(String spec, String answer, StringBuilder shown) throws LaunchException {
		java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
		java.io.BufferedReader in = new java.io.BufferedReader(new java.io.StringReader(answer));
		Coordinates c = new Chooser(new Repositories(where, notes::add), notes::add).choose(spec, in,
				new java.io.PrintStream(o, true));
		shown.append(new String(o.toByteArray(), StandardCharsets.UTF_8));
		return c;
	}

	@Test
	void whatIsTypedSplits() throws LaunchException {
		assertEquals("[null, fsh, null]", java.util.Arrays.toString(Chooser.split("fsh")));
		assertEquals("[null, fsh, 2.0.0]", java.util.Arrays.toString(Chooser.split("fsh:2.0.0")));
		assertEquals("[us.bringardner.parley, parley-ssh, null]", java.util.Arrays.toString(Chooser.split("us.bringardner.parley:parley-ssh")));
		assertEquals("[g, a, 1]", java.util.Arrays.toString(Chooser.split("g:a:1")));
		assertThrows(LaunchException.class, () -> Chooser.split("fsh:"));
		assertThrows(LaunchException.class, () -> Chooser.split("a:b:c:d"));
	}

	@Test
	void allThreeGivenIsTakenAsItIs() throws LaunchException {
		StringBuilder shown = new StringBuilder();
		assertEquals("g:a:9", choose("g:a:9", "", shown).toString());
		assertTrue(asked.isEmpty(), asked.toString());
		assertEquals("", shown.toString());
	}

	@Test
	void theNewestReleaseIsTheDefault() throws Exception {
		metadata("us.bringardner", "fsh", "1.0.0", "1.1.0", "1.0.1");
		put(where.userRepository, Coordinates.parse("us.bringardner:fsh:2.0.0-SNAPSHOT"), jar("x", "y"));
		StringBuilder shown = new StringBuilder();
		assertEquals("us.bringardner:fsh:1.1.0", choose("fsh", "\n", shown).toString());
		String s = shown.toString();
		assertTrue(s.indexOf("2.0.0-SNAPSHOT (installed)") < s.indexOf("1.1.0  [default]"), s);
		assertTrue(s.indexOf("1.1.0") < s.indexOf("1.0.1") && s.indexOf("1.0.1") < s.indexOf("1.0.0"), s);
		assertTrue(s.contains("Which one? [2]"), s);
	}

	@Test
	void pickByNumberOrVersion() throws Exception {
		metadata("us.bringardner", "fsh", "1.0.0", "1.1.0");
		put(where.userRepository, Coordinates.parse("us.bringardner:fsh:2.0.0-SNAPSHOT"), jar("x", "y"));
		assertEquals("us.bringardner:fsh:2.0.0-SNAPSHOT", choose("fsh", "1\n", new StringBuilder()).toString());
		assertEquals("us.bringardner:fsh:1.0.0", choose("fsh", "1.0.0\n", new StringBuilder()).toString());
		StringBuilder shown = new StringBuilder();
		assertEquals("us.bringardner:fsh:1.0.0", choose("fsh", "9\nnope\n3\n", shown).toString());
		assertTrue(shown.toString().contains("Type a number from 1 to 3"), shown.toString());
		// (no answer at all: the default)
		assertEquals("us.bringardner:fsh:1.1.0", choose("fsh", "", new StringBuilder()).toString());
	}

	@Test
	void onlySnapshotsTheNewestIsTheDefault() throws Exception {
		put(where.userRepository, Coordinates.parse("us.bringardner:fsh:2.0.0-SNAPSHOT"), jar("x", "y"));
		put(where.machineRepository, Coordinates.parse("us.bringardner:fsh:2.1.0-SNAPSHOT"), jar("x", "y"));
		assertEquals("us.bringardner:fsh:2.1.0-SNAPSHOT", choose("fsh", "\n", new StringBuilder()).toString());
	}

	@Test
	void oneVersionNoQuestion() throws Exception {
		metadata("us.bringardner", "fsh", "1.0.0");
		StringBuilder shown = new StringBuilder();
		assertEquals("us.bringardner:fsh:1.0.0", choose("fsh", "", shown).toString());
		assertEquals("", shown.toString());
	}

	@Test
	void theGroupIsFoundParleysToo() throws Exception {
		metadata("us.bringardner.parley", "parley-ssh", "1.0.0");
		assertEquals("us.bringardner.parley:parley-ssh:1.0.0", choose("parley-ssh", "", new StringBuilder()).toString());
		// (the version given, the group found)
		put(where.userRepository, Coordinates.parse("us.bringardner.parley:parley-ssh:2.0.0-SNAPSHOT"), jar("x", "y"));
		assertEquals("us.bringardner.parley:parley-ssh:2.0.0-SNAPSHOT",
				choose("parley-ssh:2.0.0-SNAPSHOT", "", new StringBuilder()).toString());
	}

	@Test
	void inBothGroupsBothAreListed() throws Exception {
		metadata("us.bringardner", "x", "1.0.0");
		metadata("us.bringardner.parley", "x", "1.0.0", "1.2.0");
		StringBuilder shown = new StringBuilder();
		assertEquals("us.bringardner.parley:x:1.2.0", choose("x", "", shown).toString());
		String s = shown.toString();
		// (the same version: us.bringardner first)
		assertTrue(s.indexOf("us.bringardner:x:1.0.0") < s.indexOf("us.bringardner.parley:x:1.0.0"), s);
		assertEquals("us.bringardner:x:1.0.0", choose("x:1.0.0", "\n", new StringBuilder()).toString());
	}

	@Test
	void notFound() {
		LaunchException e = assertThrows(LaunchException.class, () -> choose("nothing", "", new StringBuilder()));
		assertTrue(e.getMessage().contains("us.bringardner or us.bringardner.parley"), e.getMessage());
		e = assertThrows(LaunchException.class, () -> choose("fsh:9.9.9-SNAPSHOT", "", new StringBuilder()));
		assertTrue(e.getMessage().contains("mvn install"), e.getMessage());
	}

	@Test
	void offlineOnlyThisMachinesVersions() throws Exception {
		where = locations(true);
		metadata("us.bringardner", "fsh", "1.0.0");
		put(where.userRepository, Coordinates.parse("us.bringardner:fsh:2.0.0-SNAPSHOT"), jar("x", "y"));
		assertEquals("us.bringardner:fsh:2.0.0-SNAPSHOT", choose("fsh", "", new StringBuilder()).toString());
		assertTrue(asked.isEmpty(), asked.toString());
	}

	@Test
	void centralUnreachableListsWhatIsHere() throws Exception {
		put(where.userRepository, Coordinates.parse("us.bringardner:fsh:2.0.0-SNAPSHOT"), jar("x", "y"));
		server.stop(0);
		assertEquals("us.bringardner:fsh:2.0.0-SNAPSHOT", choose("fsh", "", new StringBuilder()).toString());
		assertTrue(notes.stream().anyMatch(n -> n.contains("can't be reached")), notes.toString());
	}

	// ---- SNAPSHOTs from Central's snapshot repository

	/** the locations with a snapshot repository: the stand-in's /maven2/snapshots/ */
	Locations withSnapshots(boolean offline) {
		Locations l = locations(offline);
		return new Locations(l.machineRepository, l.userRepository, l.central, l.central + "snapshots/", l.launcherHome,
				l.systemRoot, l.userRoot, offline);
	}

	/** put a build of SNAPSHOT c on the stand-in's snapshot repository, its metadata naming it the newest */
	void publishSnapshot(Coordinates c, String timestamp, int build, byte[] data) throws Exception {
		String dir = "snapshots/" + c.groupId.replace('.', '/') + "/" + c.artifactId + "/" + c.version + "/";
		String value = c.version.replace("SNAPSHOT", timestamp + "-" + build);
		String name = c.artifactId + "-" + value + (c.classifier.isEmpty() ? "" : "-" + c.classifier) + "." + c.type;
		central.put(dir + name, data);
		central.put(dir + name + ".sha1", sha(data, "SHA-1").getBytes(StandardCharsets.UTF_8));
		central.put(dir + "maven-metadata.xml", ("<metadata><groupId>" + c.groupId + "</groupId><artifactId>" + c.artifactId
				+ "</artifactId><version>" + c.version + "</version><versioning><snapshot><timestamp>" + timestamp
				+ "</timestamp><buildNumber>" + build + "</buildNumber></snapshot><snapshotVersions>"
				+ "<snapshotVersion><extension>pom</extension><value>" + value + "</value></snapshotVersion>"
				+ "<snapshotVersion><classifier>" + c.classifier + "</classifier><extension>" + c.type + "</extension><value>" + value
				+ "</value></snapshotVersion></snapshotVersions></versioning></metadata>").getBytes(StandardCharsets.UTF_8));
	}

	@Test
	void aSnapshotNotInstalledIsDownloaded() throws Exception {
		where = withSnapshots(false);
		Coordinates c = Coordinates.parse("org.x:lib:1.0-SNAPSHOT");
		publishSnapshot(c, "20261031.120000", 3, jar("a", "3"));
		List<String> checks = new ArrayList<>();
		File f = new Repositories(where, notes::add).find(c, null, checks);
		assertEquals("lib-1.0-20261031.120000-3.jar", f.getName());
		assertEquals(new File(where.userRepository, c.path()).getParentFile(), f.getParentFile());
		assertTrue(new File(f.getPath() + ".sha1").isFile());
		// (mvn install's file coming to be there, or a day passing, makes the command again)
		assertTrue(checks.contains("!" + new File(where.userRepository, c.path()).getAbsolutePath()), checks.toString());
		assertTrue(checks.stream().anyMatch(x -> x.startsWith("until ")), checks.toString());
		// (the same build again: not downloaded again)
		int before = asked.get("snapshots/org/x/lib/1.0-SNAPSHOT/lib-1.0-20261031.120000-3.jar").get();
		assertEquals(f, new Repositories(where, notes::add).find(c, null));
		assertEquals(before, asked.get("snapshots/org/x/lib/1.0-SNAPSHOT/lib-1.0-20261031.120000-3.jar").get());
		// (a newer build: that one)
		publishSnapshot(c, "20261101.080000", 4, jar("a", "4"));
		assertEquals("lib-1.0-20261101.080000-4.jar", new Repositories(where, notes::add).find(c, null).getName());
	}

	@Test
	void anInstalledSnapshotStillComesFirst() throws Exception {
		where = withSnapshots(false);
		Coordinates c = Coordinates.parse("org.x:lib:1.0-SNAPSHOT");
		publishSnapshot(c, "20261031.120000", 3, jar("a", "3"));
		put(where.userRepository, c, jar("a", "mine"));
		assertEquals(new File(where.userRepository, c.path()), new Repositories(where, notes::add).find(c, null));
		assertTrue(asked.isEmpty(), asked.toString());
	}

	@Test
	void aSnapshotWithAClassifier() throws Exception {
		where = withSnapshots(false);
		Coordinates c = Coordinates.parse("org.x:lib:jar:linux-x86_64:1.0-SNAPSHOT");
		publishSnapshot(c, "20261031.120000", 3, jar("n", "n"));
		assertEquals("lib-1.0-20261031.120000-3-linux-x86_64.jar", new Repositories(where, notes::add).find(c, null).getName());
	}

	@Test
	void aDownloadedSnapshotThatDoesNotMatchIsRefused() throws Exception {
		where = withSnapshots(false);
		Coordinates c = Coordinates.parse("org.x:lib:1.0-SNAPSHOT");
		publishSnapshot(c, "20261031.120000", 3, jar("a", "3"));
		central.put("snapshots/org/x/lib/1.0-SNAPSHOT/lib-1.0-20261031.120000-3.jar.sha1", "0000".getBytes(StandardCharsets.UTF_8));
		LaunchException e = assertThrows(LaunchException.class, () -> new Repositories(where, notes::add).find(c, null));
		assertTrue(e.getMessage().contains("does not match"), e.getMessage());
		assertNull(new Repositories(where, notes::add).cachedSnapshot(c));
	}

	@Test
	void aSnapshotOnNeitherSaysWhatToDo() {
		where = withSnapshots(false);
		Coordinates c = Coordinates.parse("org.x:lib:1.0-SNAPSHOT");
		LaunchException e = assertThrows(LaunchException.class, () -> new Repositories(where, notes::add).find(c, null));
		assertTrue(e.getMessage().contains("is not on") && e.getMessage().contains("mvn install in lib's project"), e.getMessage());
		assertFalse(e.isTemporary());
	}

	@Test
	void withoutTheNetworkTheBuildDownloadedBeforeIsUsed() throws Exception {
		where = withSnapshots(false);
		Coordinates c = Coordinates.parse("org.x:lib:1.0-SNAPSHOT");
		publishSnapshot(c, "20261031.120000", 3, jar("a", "3"));
		File f = new Repositories(where, notes::add).find(c, null);
		// (offline: no question asked)
		asked.clear();
		assertEquals(f, new Repositories(withSnapshots(true), notes::add).find(c, null));
		assertTrue(asked.isEmpty(), asked.toString());
		// (the repository busy: noted, and looked for again at the next start)
		busy.put("snapshots/org/x/lib/1.0-SNAPSHOT/maven-metadata.xml", new AtomicInteger(100));
		List<String> checks = new ArrayList<>();
		assertEquals(f, new Repositories(where, notes::add).find(c, null, checks));
		assertTrue(checks.contains("retry"), checks.toString());
		assertTrue(notes.stream().anyMatch(n -> n.contains("downloaded before is used")), notes.toString());
	}

	@Test
	void theNewestBuildDownloadedBefore() throws IOException {
		where = withSnapshots(true);
		Coordinates c = Coordinates.parse("org.x:lib:1.0-SNAPSHOT");
		File dir = new File(where.userRepository, c.path()).getParentFile();
		dir.mkdirs();
		for(String n : new String[] { "lib-1.0-20261031.120000-9.jar", "lib-1.0-20261031.120000-10.jar", "lib-1.0-20261030.235959-11.jar",
				"lib-1.0-20261031.120000-10.jar.sha1", "lib-1.0-20261031.120000-12-sources.jar" }) {
			Files.write(new File(dir, n).toPath(), new byte[] { 1 });
		}
		assertEquals("lib-1.0-20261031.120000-10.jar", new Repositories(where, notes::add).cachedSnapshot(c).getName());
	}

	@Test
	void olderMetadataTimestampAndBuildNumber() {
		Coordinates c = Coordinates.parse("org.x:lib:1.0-SNAPSHOT");
		assertEquals("lib-1.0-20261031.120000-7.jar", Repositories.snapshotFileName(c,
				"<metadata><versioning><snapshot><timestamp>20261031.120000</timestamp><buildNumber>7</buildNumber></snapshot></versioning></metadata>"));
		assertNull(Repositories.snapshotFileName(c, "<metadata/>"));
	}

	@Test
	void snapshotRepositoryVersionsAreListed() throws Exception {
		where = withSnapshots(false);
		metadata("us.bringardner", "fsh", "1.0.0");
		central.put("snapshots/us/bringardner/fsh/maven-metadata.xml", ("<metadata><versioning><versions><version>2.0.0-SNAPSHOT</version>"
				+ "</versions></versioning></metadata>").getBytes(StandardCharsets.UTF_8));
		StringBuilder shown = new StringBuilder();
		assertEquals("us.bringardner:fsh:2.0.0-SNAPSHOT", choose("fsh", "1\n", shown).toString());
		assertTrue(shown.toString().contains("us.bringardner:fsh:2.0.0-SNAPSHOT\n") || shown.toString().contains("us.bringardner:fsh:2.0.0-SNAPSHOT" + System.lineSeparator()),
				shown.toString());
	}

	@Test
	void snapshotsNoneSetsNoRepository() {
		Locations l = Locations.fromEnvironment(Map.of("PARLEY_SNAPSHOTS", "none"), tmp.toString(), false);
		assertNull(l.snapshots);
		assertEquals(Locations.DEFAULT_SNAPSHOTS, Locations.fromEnvironment(Map.of(), tmp.toString(), false).snapshots);
	}

	@Test
	void aFileTheBuildDidNotPublishIsNotTriedAtEveryStart() throws Exception {
		where = withSnapshots(false);
		Coordinates c = Coordinates.parse("org.x:lib:1.0-SNAPSHOT");
		publishSnapshot(c, "20261031.120000", 3, jar("a", "3"));
		Coordinates nat = c.withClassifier("linux-x86_64");
		List<String> checks = new ArrayList<>();
		LaunchException e = assertThrows(LaunchException.class, () -> new Repositories(where, notes::add).find(nat, null, checks));
		assertFalse(e.isTemporary(), e.getMessage());
		assertTrue(e.getMessage().contains("no such file"), e.getMessage());
		assertTrue(checks.stream().anyMatch(x -> x.startsWith("until ")), checks.toString());
		assertNull(asked.get("snapshots/org/x/lib/1.0-SNAPSHOT/lib-1.0-20261031.120000-3-linux-x86_64.jar"));
	}

	// ---- the programs: the catalog, the registry, and parley add / remove / list / update / available

	static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase().startsWith("windows");

	final java.io.ByteArrayOutputStream printed = new java.io.ByteArrayOutputStream();
	final java.io.ByteArrayOutputStream errors = new java.io.ByteArrayOutputStream();

	ProgramRegistry registry() {
		return new ProgramRegistry(tmp.resolve("prefix").toFile(), tmp.resolve("bin").toFile(), WINDOWS);
	}

	/** the commands, with this typed as the answer to any question; make: commands are made as programs are added */
	ProgramCommands commands(String answer, boolean make) {
		ProgramCommands c = new ProgramCommands(registry(), where, Catalog.builtIn(), null,
				new java.io.BufferedReader(new java.io.StringReader(answer)), new java.io.PrintStream(printed, true),
				new java.io.PrintStream(errors, true));
		if( make ) {
			c.makeCommandsFor("/j/bin/java", "KEY");
		}
		return c;
	}

	String printed() {
		return new String(printed.toByteArray(), StandardCharsets.UTF_8);
	}

	String errors() {
		return new String(errors.toByteArray(), StandardCharsets.UTF_8);
	}

	/** a program's jar on the stand-in: these versions of group:artifact, each with a launcher list */
	void program(String group, String artifact, String... versions) throws Exception {
		metadata(group, artifact, versions);
		for(String v : versions) {
			publish(new Coordinates(group, artifact, "jar", "", v),
					jar(LaunchList.RESOURCE, LaunchList.HEADER + "\nmain=org.x.Main\njava=11\n", "v", v));
		}
	}

	static List<String> args(String... a) {
		return java.util.Arrays.asList(a);
	}

	@Test
	void theCatalogKnowsTheParleyPrograms() {
		Catalog c = Catalog.builtIn();
		assertEquals("us.bringardner.parley:parley-ssh", c.find("pssh").artifact());
		assertNull(c.find("pssh").mainClass);
		assertEquals("us.bringardner:fsh-ide", c.find("fsh-ide-fx").artifact());
		assertEquals("us.bringardner.fsh.ide.fx.FshIdeFx", c.find("fsh-ide-fx").mainClass);
		assertNull(c.find("no-such-program"));
		assertFalse(c.find("fsh").description.isEmpty());
	}

	@Test
	void aCatalogLineThatIsWrongIsRefused() {
		assertThrows(IOException.class, () -> Catalog.read(new ByteArrayInputStream("fsh | not-coordinates\n".getBytes(StandardCharsets.UTF_8))));
	}

	@Test
	void theRegistryAddsListsAndRemoves() throws Exception {
		ProgramRegistry r = registry();
		assertTrue(r.list().isEmpty());
		assertNull(r.find("fsh"));
		r.add(new Program("fsh", Coordinates.parse("us.bringardner:fsh:2.0.0"), null));
		r.add(new Program("fsh-ide-fx", Coordinates.parse("us.bringardner:fsh-ide:2.0.0"), "x.Fx"));
		assertEquals("# fsh: the program the command fsh runs\nartifact=us.bringardner:fsh:2.0.0\n",
				new String(Files.readAllBytes(r.appFile("fsh").toPath()), StandardCharsets.UTF_8));
		assertEquals("[fsh (us.bringardner:fsh:2.0.0), fsh-ide-fx (us.bringardner:fsh-ide:2.0.0)]", r.list().toString());
		assertEquals("x.Fx", r.find("fsh-ide-fx").mainClass);
		File inner = r.commands("fsh").get(0);
		File outer = r.commands("fsh").get(1);
		if( WINDOWS ) {
			assertTrue(new String(Files.readAllBytes(outer.toPath()), StandardCharsets.UTF_8).contains("PARLEY_APP=fsh"));
		} else {
			// (a link to the script beside it, and a link to that in the bin folder)
			assertEquals("parley", Files.readSymbolicLink(inner.toPath()).toString());
			assertEquals(inner.getPath(), Files.readSymbolicLink(outer.toPath()).toString());
		}
		// (adding it again replaces it)
		r.add(new Program("fsh", Coordinates.parse("us.bringardner:fsh:2.1.0"), null));
		assertEquals("2.1.0", r.find("fsh").coordinates.version);

		File cache = new File(where.launcherHome, "cache");
		cache.mkdirs();
		Files.write(new File(cache, "fsh.args").toPath(), new byte[0]);
		r.remove("fsh", where.launcherHome);
		assertNull(r.find("fsh"));
		assertFalse(Files.exists(inner.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS));
		assertFalse(Files.exists(outer.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS));
		assertFalse(new File(cache, "fsh.args").exists());
		assertThrows(LaunchException.class, () -> r.remove("fsh", where.launcherHome));
	}

	@Test
	void anotherCommandOfThatNameIsNotReplaced() throws Exception {
		ProgramRegistry r = registry();
		File other = r.commands("ls").get(1);
		other.getParentFile().mkdirs();
		Files.write(other.toPath(), "someone else's".getBytes(StandardCharsets.UTF_8));
		LaunchException e = assertThrows(LaunchException.class, () -> r.add(new Program("ls", Coordinates.parse("g:a:1"), null)));
		assertTrue(e.getMessage().contains("is there already"), e.getMessage());
		assertFalse(r.appFile("ls").exists());
		assertEquals("someone else's", new String(Files.readAllBytes(other.toPath()), StandardCharsets.UTF_8));
	}

	@Test
	void namesThatCantBePrograms() {
		for(String bad : new String[] { "", ".hidden", "-x", "a/b", "a b", "parley", "parley-launcher", "a:b" }) {
			assertNotNull(ProgramRegistry.badName(bad), bad);
		}
		assertNull(ProgramRegistry.badName("fsh-ide"));
	}

	@Test
	void windowsCommandsAreSmallCmdFiles() throws Exception {
		ProgramRegistry r = new ProgramRegistry(tmp.resolve("prefix").toFile(), tmp.resolve("bin").toFile(), true);
		r.add(new Program("fsh", Coordinates.parse("us.bringardner:fsh:2.0.0"), null));
		File outer = r.commands("fsh").get(1);
		assertEquals("fsh.cmd", outer.getName());
		String text = new String(Files.readAllBytes(outer.toPath()), StandardCharsets.UTF_8);
		assertEquals("@setlocal & set \"PARLEY_APP=fsh\" & \"" + tmp.resolve("prefix").toFile().getAbsolutePath()
				+ "\\bin\\parley-launcher.cmd\" %*\r\n", text);
		r.remove("fsh", where.launcherHome);
		assertFalse(outer.exists());
	}

	@Test
	void addByNameTakesTheCatalogsArtifactAndMakesTheCommand() throws Exception {
		program("us.bringardner.parley", "parley-ssh", "1.0.0", "1.1.0");
		assertEquals(0, commands("\n", true).add(args("pssh")), errors());
		assertEquals("us.bringardner.parley:parley-ssh:1.1.0", registry().find("pssh").coordinates.toString());
		assertTrue(printed().startsWith("added pssh (us.bringardner.parley:parley-ssh:1.1.0): "), printed());
		// (what is downloaded is said)
		assertTrue(errors().contains("  downloading parley-ssh-1.1.0.jar ... "), errors());
		List<String> saved = Files.readAllLines(new File(where.launcherHome, "cache/pssh.args").toPath());
		assertEquals("KEY", saved.get(0));
		assertEquals("/j/bin/java", saved.get(1));
		assertEquals("org.x.Main", saved.get(saved.size() - 1));
		assertTrue(new File(where.launcherHome, "cache/pssh.check").isFile());
	}

	@Test
	void addWithOnlyAVersionAsksNothing() throws Exception {
		program("us.bringardner.parley", "parley-ssh", "1.0.0", "1.1.0");
		assertEquals(0, commands("", false).add(args("pssh", "1.0.0")), errors());
		assertEquals("us.bringardner.parley:parley-ssh:1.0.0", registry().find("pssh").coordinates.toString());
		assertFalse(errors().contains("Which one?"), errors());
		// (commands aren't made here: at the program's first start)
		assertFalse(new File(where.launcherHome, "cache/pssh.args").exists());
		assertTrue(printed().contains("each user's first pssh fetches what it needs"), printed());
	}

	@Test
	void addTakesTheCatalogsMainClassForItsArtifactOnly() throws Exception {
		program("us.bringardner", "fsh-ide", "2.0.0");
		program("org.other", "thing", "1.0");
		assertEquals(0, commands("", false).add(args("fsh-ide-fx")), errors());
		assertEquals("us.bringardner.fsh.ide.fx.FshIdeFx", registry().find("fsh-ide-fx").mainClass);
		// (the name given another artifact: not the catalog's main class)
		assertEquals(0, commands("", false).add(args("fsh-ide-fx", "org.other:thing:1.0")), errors());
		assertNull(registry().find("fsh-ide-fx").mainClass);
		// (a main class typed is the one)
		assertEquals(0, commands("", false).add(args("fsh-ide-fx", "us.bringardner:fsh-ide:2.0.0", "my.Main")), errors());
		assertEquals("my.Main", registry().find("fsh-ide-fx").mainClass);
	}

	@Test
	void addANameTheCatalogDoesNotKnowIsAnArtifactOfThatName() throws Exception {
		program("us.bringardner", "newthing", "3.0");
		assertEquals(0, commands("", false).add(args("newthing")), errors());
		assertEquals("us.bringardner:newthing:3.0", registry().find("newthing").coordinates.toString());
	}

	@Test
	void addWhatIsNotThereSaysSo() {
		assertEquals(1, commands("", false).add(args("pssh")));
		assertTrue(errors().contains("parley: parley-ssh was found neither on Maven Central nor on this machine"), errors());
		assertEquals(2, commands("", false).add(args()));
		assertEquals(1, commands("", false).add(args("parley")));
	}

	@Test
	void listRemoveAndAvailable() throws Exception {
		program("us.bringardner", "fsh", "2.0.0");
		ProgramCommands c = commands("", false);
		assertEquals(0, c.add(args("fsh")), errors());
		printed.reset();
		assertEquals(0, c.list(args()));
		assertEquals("fsh\tus.bringardner:fsh:2.0.0" + System.lineSeparator(), printed());
		printed.reset();
		assertEquals(0, c.available(args()));
		assertTrue(printed().contains("The fsh shell  [added: 2.0.0]"), printed());
		assertTrue(printed().contains("pssh"), printed());
		assertFalse(printed().contains("The Parley SSH client  [added"), printed());
		printed.reset();
		assertEquals(0, c.remove(args("fsh")));
		assertEquals("removed fsh" + System.lineSeparator(), printed());
		assertEquals(1, c.remove(args("fsh")));
		assertTrue(errors().contains("there is no program fsh here"), errors());
	}

	@Test
	void updateMovesAReleaseToTheNewestRelease() throws Exception {
		program("us.bringardner", "fsh", "2.0.0");
		assertEquals(0, commands("", true).add(args("fsh")), errors());
		printed.reset();
		assertEquals(0, commands("", true).update(args()));
		assertEquals("fsh: 2.0.0 is the newest" + System.lineSeparator(), printed());

		// (a newer release, and a SNAPSHOT newer still: a program on a release stays on releases)
		program("us.bringardner", "fsh", "2.0.0", "2.1.0");
		put(where.userRepository, Coordinates.parse("us.bringardner:fsh:3.0.0-SNAPSHOT"), jar("x", "y"));
		printed.reset();
		assertEquals(0, commands("", true).update(args("fsh")), errors());
		assertEquals("fsh: 2.0.0 -> 2.1.0" + System.lineSeparator(), printed());
		assertEquals("2.1.0", registry().find("fsh").coordinates.version);
		// (its command was made again, for the new jar)
		String saved = new String(Files.readAllBytes(new File(where.launcherHome, "cache/fsh.args").toPath()), StandardCharsets.UTF_8);
		assertTrue(saved.contains("fsh-2.1.0.jar"), saved);

		assertEquals(1, commands("", true).update(args("nothere")));
		assertTrue(errors().contains("there is no program nothere here"), errors());
	}

	@Test
	void updateKeepsTheMainClassAndMovesASnapshotToTheNewestOfAll() throws Exception {
		ProgramRegistry r = registry();
		put(where.userRepository, Coordinates.parse("us.bringardner:fsh-ide:2.0.0-SNAPSHOT"), jar("x", "y"));
		r.add(new Program("fsh-ide-fx", Coordinates.parse("us.bringardner:fsh-ide:2.0.0-SNAPSHOT"), "x.Fx"));
		program("us.bringardner", "fsh-ide", "2.0.0");
		assertEquals(0, commands("", false).update(args()), errors());
		assertEquals("us.bringardner:fsh-ide:2.0.0", r.find("fsh-ide-fx").coordinates.toString());
		assertEquals("x.Fx", r.find("fsh-ide-fx").mainClass);
	}

	@Test
	void sizesAsPeopleReadThem() {
		assertEquals("812 bytes", ProgramCommands.PrintedProgress.size(812));
		assertEquals("37 KB", ProgramCommands.PrintedProgress.size(37 * 1024 + 10));
		assertEquals("1.9 MB", ProgramCommands.PrintedProgress.size((long) (1.9 * 1024 * 1024)));
	}
}
