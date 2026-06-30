package org.fractalx.maven;

import org.fractalx.core.FractalxVersion;
import org.fractalx.core.ReflectiveModuleAnalyzer;
import org.fractalx.core.model.FractalModule;
import org.fractalx.core.generator.ServiceGenerator;
import org.fractalx.core.validation.DecompositionValidator;
import org.fractalx.core.validation.ValidationIssue;
import org.fractalx.core.validation.ValidationReport;
import org.fractalx.core.validation.ValidationReport.Partition;
import org.apache.maven.artifact.DependencyResolutionRequiredException;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Execute;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Decomposes a modular monolith into microservices.
 *
 * <p>Before generation starts an interactive questionnaire is shown on the
 * normal screen so the developer can customise the output folder, opt into
 * .gitignore management, and toggle optional generated artefacts (gateway,
 * admin dashboard, Docker/compose).  In non-TTY / CI environments every
 * question falls back to its default automatically.
 *
 * <p>Progress is shown on an <em>alternate screen buffer</em> so the dashboard
 * is fully in-place — nothing scrolls during generation.  When generation
 * completes (or fails) the plugin returns to the normal screen and prints a
 * concise Vercel-style summary.
 */
@Mojo(name = "decompose", defaultPhase = LifecyclePhase.PROCESS_CLASSES,
      requiresDependencyResolution = ResolutionScope.COMPILE)
@Execute(phase = LifecyclePhase.COMPILE)
public class DecomposeMojo extends FractalxBaseMojo {

    private static final int GATEWAY_PORT  = 9999;
    private static final int ADMIN_PORT    = 9090;
    private static final int REGISTRY_PORT = 8761;

    // ── Parameters ───────────────────────────────────────────────────────────
    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    @Parameter(property = "fractalx.sourceDirectory",
               defaultValue = "${project.build.sourceDirectory}")
    private File sourceDirectory;

    @Parameter(property = "fractalx.outputDirectory",
               defaultValue = "${project.basedir}/microservices")
    private File outputDirectory;

    @Parameter(property = "fractalx.skip",        defaultValue = "false")
    private boolean skip;

    @Parameter(property = "fractalx.generate",    defaultValue = "true")
    private boolean generate;

    /** Set to false to bypass the interactive questionnaire and use defaults. */
    @Parameter(property = "fractalx.interactive", defaultValue = "true")
    private boolean interactive;

    /** Compiled output directory — populated by {@code @Execute(phase=COMPILE)}. */
    @Parameter(defaultValue = "${project.build.outputDirectory}", readonly = true)
    private File classesDirectory;

    // ── Decomposition options collected by the questionnaire ─────────────────

    private record DecompositionConfig(
            String  folderName,
            boolean gitPresent,
            boolean addToGitIgnore,
            boolean generateGateway,
            boolean generateAdmin,
            boolean generateDocker) {

        static DecompositionConfig defaults(boolean multiModule) {
            return new DecompositionConfig("microservices", false, false, multiModule, true, true);
        }
    }

    // =========================================================================
    @Override
    public void execute() throws MojoExecutionException {
        initCli();
        long t0 = System.currentTimeMillis();

        if (skip) { out.println(a(DIM) + "  Skipped." + a(RST)); return; }

        printHeader("Decomposition Engine");

        try {
            Path sourcePath = sourceDirectory.toPath();

            if (classesDirectory == null || !classesDirectory.isDirectory()) {
                throw new MojoExecutionException(
                        "Compiled classes not found at " + classesDirectory
                        + " — run 'mvn compile' before 'mvn fractalx:decompose'.");
            }
            out.println(a(DIM) + "  Analysing compiled classes at " + a(RST)
                    + classesDirectory.getAbsolutePath());
            out.println();
            // Build the full compile classpath so the URLClassLoader can resolve transitive
            // framework types (Spring Data JpaRepository, Spring Security interfaces, etc.).
            // Without these, Class.forName() throws NoClassDefFoundError on any service class
            // that injects a JPA repository or Spring bean — silently skipping @DecomposableModule.
            URL[] compileClasspath = buildCompileClasspath();

            List<FractalModule> modules = new ReflectiveModuleAnalyzer()
                    .analyzeProject(classesDirectory.toPath(), sourcePath,
                                    getClass().getClassLoader(), compileClasspath);

            if (modules.isEmpty()) {
                warn("No @DecomposableModule classes found.");
                return;
            }

            out.println("  " + a(DIM) + "Found " + modules.size() + " module(s)" + a(RST));
            out.println();
            for (FractalModule m : modules) {
                String deps = m.getDependencies().isEmpty() ? ""
                        : a(DIM) + "  \u2192  " + a(RST) + String.join(", ", m.getDependencies());
                out.println("  " + a(GRN) + "\u25AA" + a(RST)
                        + "  " + a(BLD) + m.getServiceName() + a(RST)
                        + a(DIM) + "  :" + m.getPort() + a(RST) + deps);
            }
            out.println();

            // ── Pre-generation validation ─────────────────────────────────────
            Partition validation =
                    new DecompositionValidator().validate(modules, sourcePath).partition();
            for (ValidationIssue w : validation.warnings()) {
                warn("[" + w.ruleId() + "] " + w.moduleName() + ": " + w.message());
                warn("    Fix: " + w.fix());
            }
            if (!validation.errors().isEmpty()) {
                String sep = "\u2500".repeat(58);
                out.println();
                error(sep);
                error(" FractalX Decomposition Validation \u2014 "
                        + validation.errors().size() + " error(s) found");
                error(sep);
                for (ValidationIssue e : validation.errors()) {
                    error("[" + e.ruleId() + "] " + e.moduleName() + ": " + e.message());
                    error("    Fix: " + e.fix());
                }
                error(sep);
                error("Fix the issue(s) above and re-run: mvn fractalx:decompose");
                throw new MojoExecutionException(
                        validation.errors().size()
                        + " decomposition validation error(s). See output above.");
            }
            // ─────────────────────────────────────────────────────────────────

            if (generate) {
                boolean canAsk = ansi && interactive;
                DecompositionConfig config = canAsk
                        ? runQuestionnaire(modules)
                        : DecompositionConfig.defaults(modules.size() > 1);

                // Resolve final output directory from questionnaire answer
                outputDirectory = project.getBasedir().toPath()
                        .resolve(config.folderName()).toFile();

                runGeneration(sourcePath, modules, config, t0);
            } else {
                out.println(a(DIM) + "  Generation skipped (fractalx.generate=false)." + a(RST));
            }

        } catch (MojoExecutionException e) {
            throw e;
        } catch (Exception e) {
            throw new MojoExecutionException("Decomposition failed", e);
        }
    }

    // =========================================================================
    /**
     * Builds a URL array covering the project's full compile classpath.
     * This allows the {@link ReflectiveModuleAnalyzer}'s URLClassLoader to resolve
     * transitive framework types (e.g. {@code JpaRepository}, Spring Security interfaces)
     * that are referenced by service classes but not present in {@code target/classes/}.
     */
    private URL[] buildCompileClasspath() {
        List<URL> urls = new ArrayList<>();
        try {
            for (String element : project.getCompileClasspathElements()) {
                urls.add(new File(element).toURI().toURL());
            }
        } catch (DependencyResolutionRequiredException | java.net.MalformedURLException e) {
            getLog().warn("Could not resolve compile classpath — some @DecomposableModule classes "
                    + "may be skipped if they reference unresolvable types: " + e.getMessage());
        }
        return urls.toArray(URL[]::new);
    }

    // Questionnaire — runs on the normal screen before the dashboard
    // =========================================================================

    private DecompositionConfig runQuestionnaire(List<FractalModule> modules) {
        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));

        section("Configuration");

        String folderName = ask(reader,
                "Output folder name",
                outputDirectory.getName().isEmpty() ? "microservices" : outputDirectory.getName());

        boolean gitPresent   = project.getBasedir().toPath().resolve(".git").toFile().exists();
        boolean addGitIgnore = gitPresent && askYesNo(reader, "Add output folder to .gitignore", true);

        boolean gateway = modules.size() > 1 && askYesNo(reader, "Generate API Gateway", true);
        boolean admin   = askYesNo(reader, "Generate Admin dashboard", true);
        boolean docker  = askYesNo(reader, "Generate Docker / docker-compose", true);

        out.println();
        return new DecompositionConfig(folderName, gitPresent, addGitIgnore, gateway, admin, docker);
    }

    private String ask(BufferedReader r, String question, String defaultVal) {
        out.print("  " + a(CYN) + "?" + a(RST)
                + "  " + question
                + "  " + a(DIM) + "[" + defaultVal + "]" + a(RST)
                + "  \u203A  ");
        out.flush();
        try {
            String line = r.readLine();
            return (line == null || line.isBlank()) ? defaultVal : line.trim();
        } catch (IOException e) {
            return defaultVal;
        }
    }

    private boolean askYesNo(BufferedReader r, String question, boolean defaultYes) {
        String hint = defaultYes ? "Y/n" : "y/N";
        out.print("  " + a(CYN) + "?" + a(RST)
                + "  " + question
                + "  " + a(DIM) + "[" + hint + "]" + a(RST)
                + "  \u203A  ");
        out.flush();
        try {
            String line = r.readLine();
            if (line == null || line.isBlank()) return defaultYes;
            String l = line.trim().toLowerCase();
            return l.startsWith("y") || (defaultYes && !l.startsWith("n"));
        } catch (IOException e) {
            return defaultYes;
        }
    }

    private void addToGitIgnore(String folderName) {
        Path gitIgnore = project.getBasedir().toPath().resolve(".gitignore");
        String entry   = "/" + folderName + "/";
        try {
            if (Files.exists(gitIgnore)) {
                String content = Files.readString(gitIgnore);
                if (content.lines().anyMatch(l -> l.trim().equals(entry))) {
                    info(entry + " already present in .gitignore");
                    return;
                }
                String append = (content.endsWith("\n") ? "" : "\n") + entry + "\n";
                Files.writeString(gitIgnore, content + append);
            } else {
                Files.writeString(gitIgnore, "# FractalX generated output\n" + entry + "\n");
            }
            info("Added " + entry + " to .gitignore");
        } catch (IOException e) {
            warn("Could not update .gitignore: " + e.getMessage());
        }
    }

    private void gitAddOutputFolder(String folderName) {
        try {
            Process p = new ProcessBuilder("git", "add", folderName)
                    .directory(project.getBasedir())
                    .redirectErrorStream(true)
                    .start();
            int exit = p.waitFor();
            if (exit == 0) {
                info("Staged " + folderName + "/ via git add");
            } else {
                warn("git add " + folderName + " exited with code " + exit);
            }
        } catch (Exception e) {
            warn("Could not run git add: " + e.getMessage());
        }
    }

    // =========================================================================
    // Generation — dashboard lives on the alternate screen
    // =========================================================================

    private void runGeneration(Path srcPath, List<FractalModule> modules,
                                DecompositionConfig config, long t0) throws Exception {

        List<String> labels = new ArrayList<>();
        labels.add("fractalx-registry");
        modules.forEach(m -> labels.add(m.getServiceName()));
        if (modules.size() > 1 && config.generateGateway()) labels.add("fractalx-gateway");
        labels.add(config.generateDocker() ? "docker-compose + scripts" : "start scripts");

        if (ansi) { out.print(ALT_ON); out.flush(); }

        Dashboard        dash   = new Dashboard(labels, out, ansi, "Decomposition Engine");
        ServiceGenerator gen    = new ServiceGenerator(srcPath, outputDirectory.toPath());
        gen.withGateway(config.generateGateway())
           .withDocker(config.generateDocker());
        String[]         active = { null };

        gen.setProgressCallbacks(
            label -> { active[0] = label; dash.onStart(label); },
            label -> { dash.onDone(label); active[0] = null;   }
        );

        dash.render();

        try {
            gen.generateServices(modules);
        } catch (Exception e) {
            String step = active[0] != null ? active[0] : "generation";
            dash.onFail(step, e.getMessage());
            if (ansi) {
                try { Thread.sleep(2500); } catch (InterruptedException ignored) {}
                out.print(ALT_OFF); out.flush();
            }
            throw new MojoExecutionException("Failed at: " + step, e);
        }

        dash.finish();

        if (ansi) {
            try { Thread.sleep(250); } catch (InterruptedException ignored) {}
            out.print(ALT_OFF); out.flush();
        }

        if (config.addToGitIgnore()) {
            addToGitIgnore(config.folderName());
        } else if (config.gitPresent()) {
            gitAddOutputFolder(config.folderName());
        }

        out.println();
        printSummary(modules, outputDirectory.toPath(), config, System.currentTimeMillis() - t0);
    }

    // =========================================================================
    // Summary on the normal screen
    // =========================================================================

    private void printSummary(List<FractalModule> modules, Path outDir,
                               DecompositionConfig config, long totalMs) {
        int pw = Math.max(22, modules.stream()
                .mapToInt(m -> m.getServiceName().length()).max().orElse(0) + 2);

        section("Microservices");
        for (FractalModule m : modules)
            link(pw, m.getServiceName(), "http://localhost:" + m.getPort());
        out.println();

        section("Infrastructure");
        if (modules.size() > 1 && config.generateGateway())
            link(pw, "fractalx-gateway",  "http://localhost:" + GATEWAY_PORT);
        link(pw, "fractalx-registry", "http://localhost:" + REGISTRY_PORT);
        out.println();

        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        section("Get started");
        cmd("cd " + outDir.toAbsolutePath());
        cmd(windows ? "start-all.bat               # start all services locally"
                    : "./start-all.sh               # start all services locally");
        if (config.generateDocker())
            cmd("docker-compose up -d            # start via Docker");
        out.println();
        cmd("mvn fractalx:ps                 # check service status");
        cmd("mvn fractalx:verify             # run post-decomposition checks");
        out.println();
        out.println("  " + a(DIM) + "Docs  \u2192  " + a(RST)
                + outDir.toAbsolutePath() + "/README.md");
        out.println();

        done(totalMs);
    }
}
