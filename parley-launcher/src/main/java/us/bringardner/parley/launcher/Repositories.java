package us.bringardner.parley.launcher;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Finds an artifact's file: in the machine repository, then the user's (a SNAPSHOT: the user's
 * first, where mvn install puts it), else downloads it from Maven Central into the user repository,
 * written as Maven writes it (the .pom and .sha1 files beside the jar, and _remote.repositories), so
 * Maven takes it as its own. A release is checked against the SHA-256 the program's list gives for
 * it, else against the .sha1 Maven Central publishes; one that doesn't match is not used.
 */
public final class Repositories {

	private final Locations where;
	private final Consumer<String> warn;
	private HttpClient http;

	public Repositories(Locations where, Consumer<String> warn) {
		this.where = where;
		this.warn = warn;
	}

	/**
	 * The file of c, found or downloaded.
	 *
	 * @param sha256 its SHA-256, from the program's list; null when not known (then Maven Central's
	 *        .sha1 is the check)
	 */
	public File find(Coordinates c, String sha256) throws LaunchException {
		return find(c, sha256, new ArrayList<>());
	}

	/**
	 * As find(c, sha256), adding to checks, for the saved command, "!" + each place looked in first
	 * where c is not: c coming to be there means the command must be made again
	 */
	public File find(Coordinates c, String sha256, List<String> checks) throws LaunchException {
		File[] order = c.isSnapshot()
				? new File[] { where.userRepository, where.machineRepository }
				: new File[] { where.machineRepository, where.userRepository };
		for(int i = 0; i < order.length; i++) {
			File f = new File(order[i], c.path());
			if( !f.isFile() ) {
				continue;
			}
			if( c.isSnapshot() ) {
				// (changes with every mvn install, so there is nothing to check it against)
				absentBefore(order, i, c, checks);
				return f;
			}
			String bad = check(f, sha256);
			if( bad == null ) {
				absentBefore(order, i, c, checks);
				return f;
			}
			warn.accept(c + " in " + order[i] + " is not used: " + bad);
		}
		try {
			if( c.isSnapshot() ) {
				throw new LaunchException(c + " is not installed in " + where.userRepository
						+ ": a SNAPSHOT is not downloaded; run mvn install in " + c.artifactId + "'s project");
			}
			if( where.offline ) {
				throw new LaunchException(c + " is not in " + where.machineRepository + " or " + where.userRepository
						+ " (PARLEY_OFFLINE: nothing is downloaded)").temporary();
			}
			File ret = download(c, sha256);
			// (it is in the user repository now; the machine repository comes before it)
			absentBefore(order, java.util.Arrays.asList(order).indexOf(where.userRepository), c, checks);
			return ret;
		} catch (LaunchException e) {
			absentBefore(order, order.length, c, checks);
			throw e;
		}
	}

	private static final java.util.regex.Pattern VERSION = java.util.regex.Pattern.compile("<version>\\s*([^<\\s]+)\\s*</version>");

	/**
	 * The versions of group:artifact that Maven Central lists (its maven-metadata.xml); empty when it
	 * has none, or when offline
	 *
	 * @throws LaunchException when Central can't be reached
	 */
	public List<String> centralVersions(String group, String artifact) throws LaunchException {
		List<String> ret = new ArrayList<>();
		if( where.offline ) {
			return ret;
		}
		String url = where.central + group.replace('.', '/') + "/" + artifact + "/maven-metadata.xml";
		try {
			// (tried only twice: a list to pick from, not a download; this machine's versions are listed either way)
			HttpResponse<String> r = send(url, HttpResponse.BodyHandlers.ofString(), METADATA_ATTEMPTS);
			if( r.statusCode() == 404 ) {
				return ret;
			}
			if( r.statusCode() != 200 ) {
				throw new LaunchException(url + ": HTTP " + r.statusCode()).temporary();
			}
			String xml = r.body().replaceAll("(?s)<!--.*?-->", "");
			int at = xml.indexOf("<versions>");
			java.util.regex.Matcher m = VERSION.matcher(at < 0 ? "" : xml.substring(at));
			while (m.find()) {
				if( !ret.contains(m.group(1)) ) {
					ret.add(m.group(1));
				}
			}
			return ret;
		} catch (IOException e) {
			throw new LaunchException(url + ": " + e, e).temporary();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new LaunchException(url + " was interrupted", e).temporary();
		}
	}

	/** The versions of group:artifact whose jar is in the machine or the user repository */
	public Set<String> localVersions(String group, String artifact) {
		Set<String> ret = new LinkedHashSet<>();
		for(File repo : new File[] { where.machineRepository, where.userRepository }) {
			File[] dirs = new File(repo, group.replace('.', '/') + "/" + artifact).listFiles(File::isDirectory);
			if( dirs == null ) {
				continue;
			}
			for(File d : dirs) {
				if( new File(d, artifact + "-" + d.getName() + ".jar").isFile() ) {
					ret.add(d.getName());
				}
			}
		}
		return ret;
	}

	/** "!" + c's path in each of the first n repositories of order: c coming to be there changes things */
	private static void absentBefore(File[] order, int n, Coordinates c, List<String> checks) {
		for(int i = 0; i < n; i++) {
			File f = new File(order[i], c.path());
			if( !f.isFile() ) {
				checks.add("!" + f.getAbsolutePath());
			}
		}
	}

	/** Why f is not c's file, or null when it is */
	private String check(File f, String sha256) throws LaunchException {
		if( sha256 != null ) {
			String got = digest(f.toPath(), "SHA-256");
			return got.equals(sha256) ? null : "its SHA-256 is " + got + ", the program's list says " + sha256;
		}
		// (a .sha1 beside it, as Maven writes for what it downloads; none: installed here, taken as is)
		File sha1 = new File(f.getPath() + ".sha1");
		if( sha1.isFile() ) {
			try {
				String want = firstWord(new String(Files.readAllBytes(sha1.toPath()), StandardCharsets.UTF_8));
				String got = digest(f.toPath(), "SHA-1");
				if( !want.isEmpty() && !got.equals(want) ) {
					return "its SHA-1 is " + got + ", " + sha1.getName() + " says " + want;
				}
			} catch (IOException e) {
				// (unreadable: as if there were none)
			}
		}
		return null;
	}

	private File download(Coordinates c, String sha256) throws LaunchException {
		File target = new File(where.userRepository, c.path());
		File dir = target.getParentFile();
		try {
			Files.createDirectories(dir.toPath());
		} catch (IOException e) {
			throw new LaunchException("can't make " + dir + " for " + c + ": " + e.getMessage(), e);
		}
		String url = where.central + c.path();
		Path part = null;
		try {
			part = Files.createTempFile(dir.toPath(), target.getName(), ".part");
			int status = get(url, part);
			if( status == 404 ) {
				throw new LaunchException(c + " is not on Maven Central (" + url + ")");
			}
			if( status != 200 ) {
				throw new LaunchException("downloading " + c + " from " + url + ": HTTP " + status).temporary();
			}
			String sha1 = digest(part, "SHA-1");
			if( sha256 != null ) {
				String got = digest(part, "SHA-256");
				if( !got.equals(sha256) ) {
					throw new LaunchException("the " + c + " downloaded from " + url + " is not the one the program was built with "
							+ "(its SHA-256 is " + got + ", the program's list says " + sha256 + ")");
				}
			} else {
				String want = firstWord(getText(url + ".sha1"));
				if( !want.equals(sha1) ) {
					throw new LaunchException("the " + c + " downloaded from " + url + " does not match Maven Central's "
							+ "SHA-1 (" + sha1 + ", " + url + ".sha1 says " + want + ")");
				}
			}
			moveInto(part, target.toPath());
			part = null;
			writeText(new File(target.getPath() + ".sha1").toPath(), sha1);
			Set<String> names = new LinkedHashSet<>();
			names.add(target.getName());
			// (the POM and its parents, so Maven takes the jar's dependencies from them too, offline
			// as well; not needed to run)
			try {
				if( savePom(c) ) {
					names.add(new File(c.pomPath()).getName());
				}
				savePomParents(c);
			} catch (IOException | LaunchException e) {
				warn.accept("the POM of " + c + " was not saved: " + e.getMessage());
			}
			recordRemote(dir, names);
			return target;
		} catch (IOException e) {
			throw new LaunchException("downloading " + c + " from " + url + ": " + e, e).temporary();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new LaunchException("downloading " + c + " was interrupted", e).temporary();
		} finally {
			if( part != null ) {
				try {
					Files.deleteIfExists(part);
				} catch (IOException e) {
					// (a .part file left behind; the next run makes its own)
				}
			}
		}
	}

	private static final java.util.regex.Pattern PARENT = java.util.regex.Pattern.compile(
			"(?s)<parent>.*?<groupId>\\s*([^<\\s]+)\\s*</groupId>.*?<artifactId>\\s*([^<\\s]+)\\s*</artifactId>.*?<version>\\s*([^<\\s]+)\\s*</version>.*?</parent>");

	/** Download c's POM into the user repository when it isn't there; true when it was */
	private boolean savePom(Coordinates c) throws IOException, InterruptedException, LaunchException {
		File pom = new File(where.userRepository, c.pomPath());
		if( pom.isFile() ) {
			return false;
		}
		Files.createDirectories(pom.getParentFile().toPath());
		Path p = Files.createTempFile(pom.getParentFile().toPath(), pom.getName(), ".part");
		try {
			if( get(where.central + c.pomPath(), p) != 200 ) {
				return false;
			}
			String sha1 = digest(p, "SHA-1");
			// (checked as a jar is: a proxy's error page must not become the POM Maven keeps)
			String want = firstWord(getText(where.central + c.pomPath() + ".sha1"));
			if( !want.equals(sha1) ) {
				throw new LaunchException(c.pomPath() + " does not match Maven Central's SHA-1");
			}
			moveInto(p, pom.toPath());
			writeText(new File(pom.getPath() + ".sha1").toPath(), sha1);
			return true;
		} finally {
			Files.deleteIfExists(p);
		}
	}

	/** The POMs c's POM inherits from (its <parent>, and theirs), as Maven needs them to read it */
	private void savePomParents(Coordinates c) throws IOException, InterruptedException, LaunchException {
		Coordinates at = c;
		for(int depth = 0; depth < 20; depth++) {
			File pom = new File(where.userRepository, at.pomPath());
			if( !pom.isFile() ) {
				return;
			}
			String xml = new String(Files.readAllBytes(pom.toPath()), StandardCharsets.UTF_8).replaceAll("(?s)<!--.*?-->", "");
			java.util.regex.Matcher m = PARENT.matcher(xml);
			if( !m.find() || m.group(3).contains("${") ) {
				return;
			}
			Coordinates parent = new Coordinates(m.group(1), m.group(2), "pom", "", m.group(3));
			if( savePom(parent) ) {
				Set<String> one = new LinkedHashSet<>();
				one.add(new File(parent.pomPath()).getName());
				recordRemote(new File(where.userRepository, parent.pomPath()).getParentFile(), one);
			}
			at = parent;
		}
	}

	/** _remote.repositories, as Maven's resolver keeps it: these files came from central */
	private static void recordRemote(File dir, Set<String> names) {
		Path p = dir.toPath().resolve("_remote.repositories");
		try {
			List<String> lines = Files.isRegularFile(p) ? Files.readAllLines(p, StandardCharsets.UTF_8) : new ArrayList<>();
			if( lines.isEmpty() ) {
				lines.add("#NOTE: This is a Maven Resolver internal implementation file, its format can be changed without prior notice.");
				lines.add("#" + new java.util.Date());
			}
			boolean changed = false;
			for(String n : names) {
				String entry = n + ">central=";
				if( !lines.contains(entry) ) {
					lines.add(entry);
					changed = true;
				}
			}
			if( changed ) {
				writeText(p, String.join("\n", lines));
			}
		} catch (IOException e) {
			// (only Maven's bookkeeping: the files are there either way)
		}
	}

	/** How many times a download is tried when the server is busy (429, 5xx) or the connection fails */
	static final int ATTEMPTS = 6;

	/** How many times Maven Central's list of an artifact's versions is tried */
	static final int METADATA_ATTEMPTS = 2;

	private int get(String url, Path to) throws IOException, InterruptedException, LaunchException {
		return send(url, HttpResponse.BodyHandlers.ofFile(to)).statusCode();
	}

	private String getText(String url) throws IOException, InterruptedException, LaunchException {
		HttpResponse<String> r = send(url, HttpResponse.BodyHandlers.ofString());
		if( r.statusCode() != 200 ) {
			throw new LaunchException(url + ": HTTP " + r.statusCode());
		}
		return r.body();
	}

	/** GET, again after a pause (Retry-After, else 1, 2, 4, 8, 16 s) while the server is busy or unreachable */
	private <T> HttpResponse<T> send(String url, HttpResponse.BodyHandler<T> body)
			throws IOException, InterruptedException, LaunchException {
		return send(url, body, ATTEMPTS);
	}

	/** As send(url, body), trying at most attempts times */
	private <T> HttpResponse<T> send(String url, HttpResponse.BodyHandler<T> body, int attempts)
			throws IOException, InterruptedException, LaunchException {
		for(int attempt = 1;; attempt++) {
			HttpResponse<T> r;
			try {
				r = client().send(request(url), body);
			} catch (IOException e) {
				if( attempt >= attempts ) {
					throw e;
				}
				Thread.sleep(1000L << (attempt - 1));
				continue;
			}
			int st = r.statusCode();
			boolean busy = st == 429 || (st >= 500 && st <= 504);
			if( !busy || attempt >= attempts ) {
				return r;
			}
			long wait = 1000L << (attempt - 1);
			String after = r.headers().firstValue("Retry-After").orElse("");
			if( after.matches("\\d+") ) {
				wait = Math.min(30_000L, Long.parseLong(after) * 1000L);
			}
			Thread.sleep(wait);
		}
	}

	private static HttpRequest request(String url) throws LaunchException {
		try {
			return HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(2))
					.header("User-Agent", "parley-launcher").GET().build();
		} catch (IllegalArgumentException e) {
			throw new LaunchException("not a URL: " + url, e);
		}
	}

	private synchronized HttpClient client() {
		if( http == null ) {
			HttpClient.Builder b = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL)
					.connectTimeout(Duration.ofSeconds(20));
			ProxySelector proxy = proxyFromEnvironment();
			if( proxy != null ) {
				b.proxy(proxy);
			}
			http = b.build();
		}
		return http;
	}

	/**
	 * HTTPS_PROXY (or https_proxy), as curl and most tools take it, when Java's own https.proxyHost
	 * isn't set; null: Java's default
	 */
	static ProxySelector proxyFromEnvironment() {
		if( System.getProperty("https.proxyHost") != null ) {
			return null;
		}
		String p = System.getenv("HTTPS_PROXY");
		if( p == null ) {
			p = System.getenv("https_proxy");
		}
		if( p == null || p.isEmpty() ) {
			return null;
		}
		try {
			URI u = URI.create(p.contains("://") ? p : "http://" + p);
			if( u.getHost() == null ) {
				return null;
			}
			return ProxySelector.of(new InetSocketAddress(u.getHost(), u.getPort() < 0 ? 80 : u.getPort()));
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	static void moveInto(Path from, Path to) throws IOException {
		readable(from);
		try {
			Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
		} catch (AtomicMoveNotSupportedException e) {
			Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	/** rw-r--r--, as Maven leaves what it writes (a temporary file is made rw-------) */
	static void readable(Path p) {
		try {
			Files.setPosixFilePermissions(p, java.nio.file.attribute.PosixFilePermissions.fromString("rw-r--r--"));
		} catch (IOException | UnsupportedOperationException e) {
			// (not a POSIX file system: as it is)
		}
	}

	private static void writeText(Path p, String text) throws IOException {
		Path tmp = Files.createTempFile(p.getParent(), p.getFileName().toString(), ".part");
		try {
			Files.write(tmp, text.getBytes(StandardCharsets.UTF_8));
			moveInto(tmp, p);
		} finally {
			Files.deleteIfExists(tmp);
		}
	}

	private static String firstWord(String s) {
		String t = s.trim();
		int sp = t.indexOf(' ');
		return (sp < 0 ? t : t.substring(0, sp)).toLowerCase();
	}

	/** A file's digest, in lower-case hex */
	public static String digest(Path f, String algorithm) throws LaunchException {
		try (InputStream in = Files.newInputStream(f)) {
			MessageDigest md = MessageDigest.getInstance(algorithm);
			byte[] buf = new byte[65536];
			int n;
			while ((n = in.read(buf)) > 0) {
				md.update(buf, 0, n);
			}
			StringBuilder b = new StringBuilder();
			for(byte x : md.digest()) {
				b.append(String.format("%02x", x));
			}
			return b.toString();
		} catch (IOException | NoSuchAlgorithmException e) {
			throw new LaunchException("can't read " + f + ": " + e.getMessage(), e);
		}
	}
}
