package us.bringardner.parley.launcher;

/**
 * A program the launcher runs, as its app file (PREFIX/apps/NAME.properties) describes it: the
 * command's name, the artifact that is its jar, and the main class when it isn't the jar's own.
 */
public final class Program {

	public final String name;
	public final Coordinates coordinates;
	/** null: the main class the program's jar names */
	public final String mainClass;

	public Program(String name, Coordinates coordinates, String mainClass) {
		this.name = name;
		this.coordinates = coordinates;
		this.mainClass = mainClass == null || mainClass.trim().isEmpty() ? null : mainClass.trim();
	}

	/** The same program at another version */
	public Program withVersion(String version) {
		return new Program(name, coordinates.withVersion(version), mainClass);
	}

	@Override
	public String toString() {
		return name + " (" + coordinates + ")";
	}
}
