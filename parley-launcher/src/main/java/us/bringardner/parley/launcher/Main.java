package us.bringardner.parley.launcher;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * What the launcher's script (parley) runs for everything but starting a program whose saved
 * command is current.
 *
 * To make a program's command when it is missing or out of date:
 *
 * <pre>
 * java -jar parley-launcher.jar resolve --app NAME --app-file FILE --java JAVA --cache BASE [--key KEY]
 * </pre>
 *
 * It works out the command (Resolver) and saves it for the script (SavedCommand). Exits 1, with the
 * reason on standard error, when the program can't be run.
 *
 * To manage the programs (ProgramCommands), the script says where the installation is, then the
 * command as it was typed:
 *
 * <pre>
 * java -jar parley-launcher.jar --prefix PREFIX [--bin BIN] [--home HOME] [--java JAVA --key KEY] COMMAND ...
 *
 *   add NAME [[GROUP:]ARTIFACT[:VERSION] | VERSION] [MAIN]
 *   remove NAME
 *   list
 *   update [NAME ...]
 *   available
 * </pre>
 *
 * --home: the folder the script keeps the saved commands in (~/.parley/launcher unless moved).
 * --java and --key: add and update make the program's command at once, for that java (left out:
 * it is made at the program's first start).
 *
 * <pre>
 * java -jar parley-launcher.jar versions [GROUP:]ARTIFACT[:VERSION]
 * java -jar parley-launcher.jar choose [GROUP:]ARTIFACT[:VERSION]
 * </pre>
 *
 * versions lists every version found, newest first. choose prints the full coordinates what was
 * typed means (see Chooser), asking which version on standard error when there is more than one
 * (the Windows script's add uses it).
 */
public final class Main {

	public static void main(String[] args) {
		System.exit(run(args));
	}

	static int run(String[] args) {
		// the installation, before the command
		String prefix = null, bin = null, home = null, java = null, key = "";
		int at = 0;
		while (at + 1 < args.length && (args[at].equals("--prefix") || args[at].equals("--bin") || args[at].equals("--home") || args[at].equals("--java")
				|| args[at].equals("--key")) && !"resolve".equals(args[0])) {
			String v = args[at + 1];
			switch (args[at]) {
			case "--prefix": prefix = v; break;
			case "--bin": bin = v; break;
			case "--home": home = v; break;
			case "--java": java = v; break;
			default: key = v;
			}
			at += 2;
		}
		String cmd = at >= args.length ? "--help" : args[at];
		List<String> rest = new ArrayList<>(Arrays.asList(args).subList(Math.min(at + 1, args.length), args.length));

		if( "--version".equals(cmd) ) {
			Package p = Main.class.getPackage();
			System.out.println("parley-launcher " + (p.getImplementationVersion() == null ? "(development)" : p.getImplementationVersion()));
			return 0;
		}
		switch (cmd) {
		case "add":
		case "remove":
		case "list":
		case "update":
		case "available":
			return manage(cmd, rest, prefix, bin, home, java, key);
		case "choose":
		case "versions":
			return chooseOrVersions(cmd, rest);
		case "resolve":
			return resolve(args);
		default:
			System.err.println("usage: parley-launcher --prefix PREFIX [--bin BIN] [--home HOME] [--java JAVA --key KEY] add|remove|list|update|available ...");
			System.err.println("       parley-launcher resolve --app NAME --app-file FILE --java JAVA --cache BASE [--key KEY]");
			System.err.println("       parley-launcher versions [GROUP:]ARTIFACT[:VERSION]");
			System.err.println("       parley-launcher choose [GROUP:]ARTIFACT[:VERSION]");
			return "--help".equals(cmd) ? 0 : 2;
		}
	}

	/** add, remove, list, update, available */
	private static int manage(String cmd, List<String> rest, String prefix, String bin, String home, String java, String key) {
		if( prefix == null ) {
			System.err.println("parley-launcher: " + cmd + " needs --prefix (the folder the launcher is installed in)");
			return 2;
		}
		boolean windows = System.getProperty("os.name", "").toLowerCase().startsWith("windows");
		ProgramRegistry registry = new ProgramRegistry(new File(prefix), bin == null || bin.isEmpty() ? null : new File(bin), windows);
		ProgramCommands commands = new ProgramCommands(registry, Locations.fromEnvironment(), Catalog.builtIn(), home == null || home.isEmpty() ? null : new File(home),
				new BufferedReader(new InputStreamReader(System.in, Charset.defaultCharset())), System.out, System.err);
		if( java != null ) {
			commands.makeCommandsFor(java, key);
		}
		switch (cmd) {
		case "add": return commands.add(rest);
		case "remove": return commands.remove(rest);
		case "list": return commands.list(rest);
		case "update": return commands.update(rest);
		default: return commands.available(rest);
		}
	}

	private static int chooseOrVersions(String cmd, List<String> rest) {
		if( rest.size() != 1 ) {
			System.err.println("usage: parley " + cmd + " [GROUP:]ARTIFACT[:VERSION]");
			return 2;
		}
		String spec = rest.get(0);
		Chooser chooser = new Chooser(new Repositories(Locations.fromEnvironment(), w -> System.err.println("parley: note: " + w)),
				w -> System.err.println("parley: note: " + w));
		try {
			if( "versions".equals(cmd) ) {
				List<Chooser.Found> found = chooser.versions(spec);
				int def = Chooser.defaultIndex(found);
				for(int i = 0; i < found.size(); i++) {
					System.out.println(found.get(i) + (i == def ? "  [default]" : ""));
				}
				if( found.isEmpty() ) {
					System.err.println("parley: no version of " + spec + " was found");
					return 1;
				}
			} else {
				// (the prompt on standard error, so the script can take the answer from standard output)
				System.out.println(chooser.choose(spec,
						new BufferedReader(new InputStreamReader(System.in, Charset.defaultCharset())), System.err));
			}
			return 0;
		} catch (LaunchException e) {
			System.err.println("parley: " + e.getMessage());
			return 1;
		}
	}

	/** resolve --app NAME --app-file FILE --java JAVA --cache BASE [--key KEY] */
	private static int resolve(String[] args) {
		String app = null, appFile = null, java = null, cache = null, key = "";
		for(int i = 1; i < args.length; i++) {
			String a = args[i];
			String v = i + 1 < args.length ? args[i + 1] : null;
			switch (a) {
			case "--app": app = v; i++; break;
			case "--app-file": appFile = v; i++; break;
			case "--java": java = v; i++; break;
			case "--cache": cache = v; i++; break;
			case "--key": key = v == null ? "" : v; i++; break;
			default:
				System.err.println("parley-launcher: unknown argument " + a);
				return 2;
			}
		}
		if( app == null || appFile == null || java == null || cache == null ) {
			System.err.println("parley-launcher: resolve needs --app, --app-file, --java and --cache");
			return 2;
		}
		final String name = app;
		try {
			Resolver.Launch launch = new Resolver(Locations.fromEnvironment(), w -> System.err.println(name + ": note: " + w),
					new ProgramCommands.PrintedProgress(System.err)).resolve(app, new File(appFile), java);
			SavedCommand.write(launch, java, new File(cache), key);
			return 0;
		} catch (LaunchException e) {
			System.err.println(e.getMessage().startsWith(app) ? e.getMessage() : app + ": " + e.getMessage());
			return 1;
		}
	}
}
