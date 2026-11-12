package us.bringardner.parley.launcher;

/**
 * Told what Repositories downloads, as it does: the command line prints it, so a first start that
 * fetches a program's jars isn't silent.
 */
public interface ProgressListener {

	/** A listener that does nothing */
	ProgressListener NONE = new ProgressListener() {
		@Override
		public void downloading(Coordinates c, String fileName) {
		}

		@Override
		public void downloaded(Coordinates c, String fileName, long bytes) {
		}

		@Override
		public void failed(Coordinates c, String fileName) {
		}
	};

	/** The download of c's file is starting */
	void downloading(Coordinates c, String fileName);

	/** It is on this machine now, checked; bytes is its size */
	void downloaded(Coordinates c, String fileName, long bytes);

	/** It was not downloaded (the reason follows as the launcher's error or note) */
	void failed(Coordinates c, String fileName);
}
