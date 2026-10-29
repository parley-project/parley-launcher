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

	/** true when trying again later may work (no network, a busy server), not when it isn't there */
	private boolean temporary;

	public boolean isTemporary() {
		return temporary;
	}

	public LaunchException temporary() {
		temporary = true;
		return this;
	}
}
