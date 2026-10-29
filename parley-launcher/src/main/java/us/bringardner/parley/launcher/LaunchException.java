package us.bringardner.parley.launcher;

/** Why a program can't be started, in words for the person who started it */
public class LaunchException extends Exception {
	private static final long serialVersionUID = 1L;

	public LaunchException(String message) {
		super(message);
	}

	public LaunchException(String message, Throwable cause) {
		super(message, cause);
	}
}
