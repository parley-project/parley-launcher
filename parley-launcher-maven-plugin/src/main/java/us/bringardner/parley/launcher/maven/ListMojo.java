package us.bringardner.parley.launcher.maven;

import java.io.File;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;

import us.bringardner.parley.launcher.Coordinates;
import us.bringardner.parley.launcher.LaunchException;
import us.bringardner.parley.launcher.LaunchList;
import us.bringardner.parley.launcher.Repositories;

/**
 * Writes META-INF/parley/launcher.list into the classes the jar is made from: what parley-launcher
 * needs to run the program (its main class, Java options, native files) and every jar on its
 * run-time class path, in class path order, each with its SHA-256 (none for a SNAPSHOT). A plugin
 * is built with it too (no main class needed), so the launcher fetches what the plugin needs.
 *
 * <pre>
 * &lt;plugin&gt;
 *   &lt;groupId&gt;us.bringardner.parley&lt;/groupId&gt;
 *   &lt;artifactId&gt;parley-launcher-maven-plugin&lt;/artifactId&gt;
 *   &lt;executions&gt;&lt;execution&gt;&lt;goals&gt;&lt;goal&gt;list&lt;/goal&gt;&lt;/goals&gt;&lt;/execution&gt;&lt;/executions&gt;
 *   &lt;configuration&gt;
 *     &lt;mainClass&gt;us.bringardner.fsh.Console&lt;/mainClass&gt;
 *     &lt;options&gt;&lt;option&gt;-Xss16m&lt;/option&gt;&lt;option&gt;-Djava.library.path={native}&lt;/option&gt;&lt;/options&gt;
 *     &lt;nativeClassifier&gt;{platform}&lt;/nativeClassifier&gt;
 *   &lt;/configuration&gt;
 * &lt;/plugin&gt;
 * </pre>
 *
 * {native} is the folder the native jar is unpacked in, {platform} this platform (linux-x86_64,
 * macos-aarch64 ...), both filled in by the launcher when it runs the program.
 */
@Mojo(name = "list", defaultPhase = LifecyclePhase.PREPARE_PACKAGE, requiresDependencyResolution = ResolutionScope.RUNTIME, threadSafe = true)
public class ListMojo extends AbstractMojo {

	@Parameter(defaultValue = "${project}", readonly = true, required = true)
	private MavenProject project;

	/** The class to run; none for a plugin or a library */
	@Parameter
	private String mainClass;

	/** The oldest Java the program runs on */
	@Parameter(defaultValue = "${maven.compiler.release}")
	private String minJava;

	/** Options for java, before the class path ({native}: the native files' folder) */
	@Parameter
	private List<String> options = new ArrayList<>();

	/**
	 * The classifier of this project's jar of native files, {platform} where the platform goes
	 * (linux-x86_64 ...); none: the program has none
	 */
	@Parameter
	private String nativeClassifier;

	/** Whether the program runs without its native files (on a platform nobody built them for) */
	@Parameter(defaultValue = "true")
	private boolean nativeOptional;

	/** A program that starts java, and its arguments ({native}/fshexec, -j): left out without it */
	@Parameter
	private List<String> wrapper = new ArrayList<>();

	/** The name of the plugin settings files (default: the artifact id) */
	@Parameter
	private String plugins;

	@Parameter(defaultValue = "${project.build.outputDirectory}/" + LaunchList.RESOURCE)
	private File outputFile;

	@Parameter(property = "parley.launcher.skip", defaultValue = "false")
	private boolean skip;

	@Override
	public void execute() throws MojoExecutionException {
		if( skip ) {
			getLog().info("skipped");
			return;
		}
		LaunchList list = new LaunchList();
		list.artifact = new Coordinates(project.getGroupId(), project.getArtifactId(), "jar", "", project.getVersion());
		list.mainClass = mainClass;
		try {
			list.minJava = minJava == null || minJava.trim().isEmpty() ? 11 : Integer.parseInt(minJava.trim());
		} catch (NumberFormatException e) {
			throw new MojoExecutionException("minJava is not a Java version: " + minJava);
		}
		for(String o : options) {
			list.options.add(tokens(o));
		}
		if( nativeClassifier != null && !nativeClassifier.trim().isEmpty() ) {
			list.nativeArtifact = list.artifact.withClassifier(tokens(nativeClassifier.trim())).toString();
			list.nativeOptional = nativeOptional;
		}
		for(String w : wrapper) {
			list.wrapper.add(tokens(w));
		}
		list.plugins = plugins;

		// (what the build left out of the program, so a plugin doesn't bring it back)
		for(org.apache.maven.model.Dependency d : project.getDependencies()) {
			for(org.apache.maven.model.Exclusion x : d.getExclusions()) {
				String e = x.getGroupId() + ":" + x.getArtifactId();
				if( !list.excludes.contains(e) ) {
					list.excludes.add(e);
				}
			}
		}
		for(Artifact a : project.getArtifacts()) {
			if( !a.getArtifactHandler().isAddedToClasspath() ) {
				continue;
			}
			File f = a.getFile();
			if( f == null || !f.isFile() ) {
				throw new MojoExecutionException(a + " has no file to list (was it resolved?)");
			}
			Coordinates c = new Coordinates(a.getGroupId(), a.getArtifactId(),
					a.getArtifactHandler().getExtension(), a.getClassifier(), a.getBaseVersion());
			String sha = null;
			if( !a.isSnapshot() ) {
				try {
					sha = Repositories.digest(f.toPath(), "SHA-256");
				} catch (LaunchException e) {
					throw new MojoExecutionException(e.getMessage(), e);
				}
			}
			list.dependencies.add(new LaunchList.Dependency(c, sha));
		}

		try {
			Files.createDirectories(outputFile.getParentFile().toPath());
			try (Writer w = new OutputStreamWriter(Files.newOutputStream(outputFile.toPath()), StandardCharsets.UTF_8)) {
				list.write(w);
			}
		} catch (IOException e) {
			throw new MojoExecutionException("can't write " + outputFile + ": " + e.getMessage(), e);
		}
		getLog().info(outputFile.getName() + ": " + list.dependencies.size() + " jars"
				+ (mainClass == null ? "" : ", main " + mainClass));
	}

	/** {native} and {platform} as the list writes them */
	private static String tokens(String s) {
		return s.replace("{native}", "${native}").replace("{platform}", "${platform}").replace("$${", "${");
	}
}
