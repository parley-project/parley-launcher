package us.bringardner.parley.launcher;

import java.io.BufferedReader;
import java.io.File;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The launcher's commands that manage programs, as typed after "parley":
 *
 * <pre>
 *   add NAME [[GROUP:]ARTIFACT[:VERSION] | VERSION] [MAIN]
 *   remove NAME
 *   list
 *   update [NAME ...]
 *   available
 * </pre>
 *
 * Each returns the exit code: 0 when it worked, 1 when it couldn't be done (the reason on err),
 * 2 when it was typed wrong.
 */
public final class ProgramCommands {

	private final ProgramRegistry registry;
	private final Locations where;
	private final Catalog catalog;
	/** the folder the saved commands are in (as the script has it) */
	private final File launcherHome;
	private final BufferedReader in;
	private final PrintStream out;
	private final PrintStream err;
	/** the java a program's command is made for; null: commands aren't made here */
	private String javaPath;
	private String key = "";

	/**
	 * @param launcherHome the folder the script keeps the saved commands in; null: where.launcherHome
	 * @param in where the answer to "Which version?" is read from
	 * @param out what a command prints; err: its questions, notes, progress and errors
	 */
	public ProgramCommands(ProgramRegistry registry, Locations where, Catalog catalog, File launcherHome, BufferedReader in,
			PrintStream out, PrintStream err) {
		this.launcherHome = launcherHome == null ? where.launcherHome : launcherHome;
		this.registry = registry;
		this.where = where;
		this.catalog = catalog;
		this.in = in;
		this.out = out;
		this.err = err;
	}

	/**
	 * Make each program's command as it is added or updated (so a missing jar shows then, not at its
	 * first start), for this java and with the script's key. Not called: the command is made at the
	 * program's first start, which is what an administrator adding programs for everyone wants.
	 */
	public void makeCommandsFor(String java, String key) {
		this.javaPath = java;
		this.key = key == null ? "" : key;
	}

	private void note(String text) {
		err.println(ProgramRegistry.SCRIPT + ": note: " + text);
	}

	private Chooser chooser() {
		return new Chooser(new Repositories(where, this::note), this::note);
	}

	/**
	 * Whether this user can change the programs here. When not (the launcher was installed for every
	 * user, and this is not an administrator), says so on err, with the command that does it.
	 *
	 * @param typed what was typed after "parley", for the message
	 */
	private boolean allowed(String command, List<String> typed) {
		if( registry.writable() ) {
			return true;
		}
		List<File> own = registry.commands(ProgramRegistry.SCRIPT);
		String again = own.get(own.size() - 1).getPath() + " " + command + (typed.isEmpty() ? "" : " " + String.join(" ", typed));
		err.println(ProgramRegistry.SCRIPT + ": the launcher in " + registry.prefix() + " is every user's, so its programs are changed by an administrator:");
		if( File.separatorChar == '\\' ) {
			err.println("  " + again + "   (from an administrator's prompt)");
			err.println("or install the launcher for yourself alone (install.cmd --user).");
		} else {
			// (the full path: sudo's PATH often has no /usr/local/bin)
			err.println("  sudo " + again);
			err.println("or install the launcher for yourself alone (install.sh --user).");
		}
		return false;
	}

	/** add NAME [SPEC] [MAIN] */
	public int add(List<String> args) {
		if( args.isEmpty() || args.size() > 3 ) {
			err.println("usage: parley add NAME [[GROUP:]ARTIFACT[:VERSION] | VERSION] [MAIN]");
			return 2;
		}
		if( !allowed("add", args) ) {
			return 1;
		}
		String name = args.get(0);
		String spec = args.size() > 1 ? args.get(1) : null;
		String main = args.size() > 2 ? args.get(2) : null;
		try {
			String bad = ProgramRegistry.badName(name);
			if( bad != null ) {
				throw new LaunchException(name + ": " + bad);
			}
			Catalog.Entry known = catalog.find(name);
			// (what the name alone means: the catalog's artifact for it, else an artifact of that name)
			String base = known == null ? name : known.artifact();
			if( spec == null ) {
				spec = base;
			} else if( spec.indexOf(':') < 0 && Character.isDigit(spec.charAt(0)) ) {
				// (only a version: parley add fsh 2.0.0)
				spec = base + ":" + spec;
			}
			Coordinates c = chooser().choose(spec, in, err);
			if( main == null && known != null && known.groupId.equals(c.groupId) && known.artifactId.equals(c.artifactId) ) {
				main = known.mainClass;
			}
			Program p = new Program(name, c, main);
			registry.add(p);
			List<File> commands = registry.commands(name);
			out.println("added " + name + " (" + c + "): " + commands.get(commands.size() - 1));
			return make(p) ? 0 : 1;
		} catch (LaunchException e) {
			err.println(ProgramRegistry.SCRIPT + ": " + e.getMessage());
			return 1;
		}
	}

	/** remove NAME */
	public int remove(List<String> args) {
		if( args.size() != 1 ) {
			err.println("usage: parley remove NAME");
			return 2;
		}
		if( !allowed("remove", args) ) {
			return 1;
		}
		try {
			registry.remove(args.get(0), launcherHome);
			out.println("removed " + args.get(0));
			return 0;
		} catch (LaunchException e) {
			err.println(ProgramRegistry.SCRIPT + ": " + e.getMessage());
			return 1;
		}
	}

	/** list: each program and its artifact, a tab between them */
	public int list(List<String> args) {
		if( !args.isEmpty() ) {
			err.println("usage: parley list");
			return 2;
		}
		try {
			for(Program p : registry.list()) {
				out.println(p.name + "\t" + p.coordinates);
			}
			return 0;
		} catch (LaunchException e) {
			err.println(ProgramRegistry.SCRIPT + ": " + e.getMessage());
			return 1;
		}
	}

	/** available: the programs the catalog knows, and which of them are added here */
	public int available(List<String> args) {
		if( !args.isEmpty() ) {
			err.println("usage: parley available");
			return 2;
		}
		int width = 0;
		for(Catalog.Entry e : catalog.entries()) {
			width = Math.max(width, e.name.length());
		}
		for(Catalog.Entry e : catalog.entries()) {
			String added = "";
			try {
				Program p = registry.find(e.name);
				added = p == null ? "" : "  [added: " + p.coordinates.version + "]";
			} catch (LaunchException x) {
				// (an app file that can't be read: listed as not added)
			}
			out.println(String.format("%-" + width + "s  %s%s", e.name, e.description, added));
		}
		out.println();
		out.println("Add one with: parley add NAME");
		return 0;
	}

	/**
	 * update [NAME ...]: move each program (all of them when none is named) to its newest version.
	 * A program on a release moves to the newest release; one on a SNAPSHOT to the newest version of
	 * all, release or SNAPSHOT.
	 */
	public int update(List<String> args) {
		if( !allowed("update", args) ) {
			return 1;
		}
		int ret = 0;
		try {
			List<Program> programs = new ArrayList<>();
			if( args.isEmpty() ) {
				programs.addAll(registry.list());
				if( programs.isEmpty() ) {
					out.println("no programs have been added (parley add NAME)");
				}
			} else {
				for(String name : args) {
					Program p = registry.find(name);
					if( p == null ) {
						err.println(ProgramRegistry.SCRIPT + ": there is no program " + name + " here");
						ret = 1;
					} else {
						programs.add(p);
					}
				}
			}
			Chooser chooser = chooser();
			for(Program p : programs) {
				try {
					String newest = newest(chooser, p);
					if( newest == null ) {
						out.println(p.name + ": " + p.coordinates.version + " is the newest");
						continue;
					}
					Program moved = p.withVersion(newest);
					registry.save(moved);
					out.println(p.name + ": " + p.coordinates.version + " -> " + newest);
					if( !make(moved) ) {
						ret = 1;
					}
				} catch (LaunchException e) {
					err.println(ProgramRegistry.SCRIPT + ": " + p.name + ": " + e.getMessage());
					ret = 1;
				}
			}
		} catch (LaunchException e) {
			err.println(ProgramRegistry.SCRIPT + ": " + e.getMessage());
			return 1;
		}
		return ret;
	}

	/** The version p should move to, or null when it is on the newest already */
	private static String newest(Chooser chooser, Program p) throws LaunchException {
		String best = null;
		for(Chooser.Found f : chooser.versions(p.coordinates.groupId + ":" + p.coordinates.artifactId)) {
			String v = f.coordinates.version;
			if( f.coordinates.isSnapshot() && !p.coordinates.isSnapshot() ) {
				continue;
			}
			if( Coordinates.compareVersions(v, p.coordinates.version) > 0
					&& (best == null || Coordinates.compareVersions(v, best) > 0) ) {
				best = v;
			}
		}
		return best;
	}

	/** Make p's command now, when commands are made here; false when it couldn't be (the reason on err) */
	private boolean make(Program p) {
		if( javaPath == null ) {
			out.println("each user's first " + p.name + " fetches what it needs");
			return true;
		}
		try {
			Resolver resolver = new Resolver(where, w -> err.println(p.name + ": note: " + w), new PrintedProgress(err));
			Resolver.Launch launch = resolver.resolve(p.name, registry.appFile(p.name), javaPath);
			SavedCommand.write(launch, javaPath, SavedCommand.base(launcherHome, p.name), key);
			return true;
		} catch (LaunchException e) {
			err.println(e.getMessage().startsWith(p.name) ? e.getMessage() : p.name + ": " + e.getMessage());
			return false;
		}
	}

	/** Downloads as lines of text: "  downloading parley-fsh-2.0.0.jar ... 1.2 MB" */
	static final class PrintedProgress implements ProgressListener {
		private final PrintStream to;

		PrintedProgress(PrintStream to) {
			this.to = to;
		}

		@Override
		public void downloading(Coordinates c, String fileName) {
			to.print("  downloading " + fileName + " ... ");
			to.flush();
		}

		@Override
		public void downloaded(Coordinates c, String fileName, long bytes) {
			to.println(size(bytes));
		}

		@Override
		public void failed(Coordinates c, String fileName) {
			to.println("failed");
		}

		/** 812 bytes, 37 KB, 1.9 MB */
		static String size(long bytes) {
			if( bytes < 1024 ) {
				return bytes + " bytes";
			}
			if( bytes < 1024 * 1024 ) {
				return (bytes + 512) / 1024 + " KB";
			}
			return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0));
		}
	}
}
