package us.bringardner.parley.launcher;

import java.util.Objects;

/**
 * A Maven artifact's coordinates, written as Maven writes them:
 * {@code group:artifact:version}, {@code group:artifact:type:version} or
 * {@code group:artifact:type:classifier:version}. The type is jar unless given.
 */
public final class Coordinates {

	public final String groupId;
	public final String artifactId;
	public final String type;
	/** "" when there is none */
	public final String classifier;
	public final String version;

	public Coordinates(String groupId, String artifactId, String type, String classifier, String version) {
		this.groupId = require(groupId, "group");
		this.artifactId = require(artifactId, "artifact");
		this.type = type == null || type.isEmpty() ? "jar" : type;
		this.classifier = classifier == null ? "" : classifier;
		this.version = require(version, "version");
	}

	private static String require(String value, String what) {
		if( value == null || value.isEmpty() ) {
			throw new IllegalArgumentException("no " + what);
		}
		return value;
	}

	/** Coordinates as Maven writes them (see the class comment). */
	public static Coordinates parse(String text) {
		String[] p = text.trim().split(":", -1);
		switch (p.length) {
		case 3:
			return new Coordinates(p[0], p[1], "jar", "", p[2]);
		case 4:
			return new Coordinates(p[0], p[1], p[2], "", p[3]);
		case 5:
			return new Coordinates(p[0], p[1], p[2], p[3], p[4]);
		default:
			throw new IllegalArgumentException("not group:artifact[:type[:classifier]]:version: " + text);
		}
	}

	public boolean isSnapshot() {
		return version.endsWith("-SNAPSHOT");
	}

	/** group:artifact plus the classifier: what is the same library whatever its version */
	public String key() {
		return groupId + ":" + artifactId + (classifier.isEmpty() ? "" : ":" + classifier);
	}

	public Coordinates withVersion(String v) {
		return new Coordinates(groupId, artifactId, type, classifier, v);
	}

	public Coordinates withClassifier(String c) {
		return new Coordinates(groupId, artifactId, type, c, version);
	}

	public Coordinates withType(String t) {
		return new Coordinates(groupId, artifactId, t, classifier, version);
	}

	/** The file's name in a repository: artifact-version[-classifier].type */
	public String fileName() {
		return artifactId + "-" + version + (classifier.isEmpty() ? "" : "-" + classifier) + "." + type;
	}

	/** The file's path in a repository, with / between the parts */
	public String path() {
		return groupId.replace('.', '/') + "/" + artifactId + "/" + version + "/" + fileName();
	}

	/** The POM's path beside it */
	public String pomPath() {
		return groupId.replace('.', '/') + "/" + artifactId + "/" + version + "/" + artifactId + "-" + version + ".pom";
	}

	@Override
	public String toString() {
		StringBuilder b = new StringBuilder(groupId).append(':').append(artifactId);
		if( !classifier.isEmpty() ) {
			b.append(':').append(type).append(':').append(classifier);
		} else if( !"jar".equals(type) ) {
			b.append(':').append(type);
		}
		return b.append(':').append(version).toString();
	}

	@Override
	public boolean equals(Object o) {
		if( !(o instanceof Coordinates) ) {
			return false;
		}
		Coordinates c = (Coordinates) o;
		return groupId.equals(c.groupId) && artifactId.equals(c.artifactId) && type.equals(c.type)
				&& classifier.equals(c.classifier) && version.equals(c.version);
	}

	@Override
	public int hashCode() {
		return Objects.hash(groupId, artifactId, type, classifier, version);
	}

	/**
	 * Compare two versions the simple way: numbers part by part, a -SNAPSHOT or other qualifier
	 * before the same version without one. Enough to warn that a plugin asked for a newer library.
	 */
	public static int compareVersions(String a, String b) {
		String[] pa = a.split("[.-]");
		String[] pb = b.split("[.-]");
		for(int i = 0; i < Math.max(pa.length, pb.length); i++) {
			String x = i < pa.length ? pa[i] : "";
			String y = i < pb.length ? pb[i] : "";
			boolean nx = x.matches("\\d+");
			boolean ny = y.matches("\\d+");
			int c;
			if( nx && ny ) {
				c = Long.compare(Long.parseLong(x), Long.parseLong(y));
			} else if( x.isEmpty() || y.isEmpty() ) {
				// (1.0 > 1.0-SNAPSHOT; 1.0.1 > 1.0)
				c = x.isEmpty() ? (ny ? -1 : 1) : (nx ? 1 : -1);
			} else if( nx != ny ) {
				c = nx ? 1 : -1;
			} else {
				c = x.compareToIgnoreCase(y);
			}
			if( c != 0 ) {
				return c;
			}
		}
		return 0;
	}
}
