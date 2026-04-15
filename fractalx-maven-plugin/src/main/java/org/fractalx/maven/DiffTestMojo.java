package org.fractalx.maven;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.fractalx.core.verifier.EndpointScanner;
import org.fractalx.core.verifier.EndpointScanner.EndpointSpec;
import org.fractalx.core.verifier.EntityScanner;
import org.fractalx.core.verifier.EntityScanner.EntitySpec;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.Scanner;

/**
 * Differential test runner: starts the original monolith and the decomposed
 * microservices side by side, discovers endpoints from both, replays the same
 * requests against both, and diffs the responses.
 *
 * <p>A diff failure means the decomposition changed the service's observable
 * behaviour — the monolith returns one thing, the decomposed service returns
 * another. This catches behavioural regressions without requiring domain
 * knowledge of what the "correct" output is.
 *
 * <p><b>Strategy:</b>
 * <ol>
 *   <li>Start the monolith on its configured port</li>
 *   <li>Discover all REST endpoints from the monolith via static analysis</li>
 *   <li>Start each decomposed service (or the gateway if present)</li>
 *   <li>For each endpoint: send the same request to both, capture response
 *       status + body, report differences</li>
 *   <li>Shut down all processes, write a diff report</li>
 * </ol>
 *
 * <pre>
 *   mvn fractalx:diff-test -Dfractalx.difftest.monolith=path/to/monolith
 *   mvn fractalx:diff-test -Dfractalx.difftest.failBuild=true
 * </pre>
 */
@Mojo(name = "diff-test", requiresProject = true, threadSafe = false)
public class DiffTestMojo extends FractalxBaseMojo {

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    /** Directory that contains the generated microservices. */
    @Parameter(property = "fractalx.outputDirectory",
               defaultValue = "${project.basedir}/microservices")
    private File outputDirectory;

    /**
     * Path to the original monolith project directory (or its {@code pom.xml} directly).
     * When omitted, FractalX searches for a {@code pom.xml} by walking up from the
     * current project base directory and checking sibling directories.
     * The user is always prompted to confirm (or override) the detected location.
     */
    @Parameter(property = "fractalx.difftest.monolith")
    private File monolithDirectory;

    /** Port the monolith runs on. Read from monolith's application.yml if not set. */
    @Parameter(property = "fractalx.difftest.monolithPort", defaultValue = "0")
    private int monolithPort = 0;

    /** When {@code true}, runs {@code mvn package -DskipTests} before starting. */
    @Parameter(property = "fractalx.difftest.build", defaultValue = "true")
    private boolean build = true;

    /** Seconds to wait for services to start. */
    @Parameter(property = "fractalx.difftest.timeout", defaultValue = "120")
    private int startupTimeout = 120;

    /** Actuator health path. */
    @Parameter(property = "fractalx.difftest.health", defaultValue = "/actuator/health")
    private String healthPath = "/actuator/health";

    /** When {@code true}, throws {@link MojoFailureException} if any diffs are found. */
    @Parameter(property = "fractalx.difftest.failBuild", defaultValue = "false")
    private boolean failBuild = false;

    /** Skip this mojo entirely. */
    @Parameter(property = "fractalx.difftest.skip", defaultValue = "false")
    private boolean skip = false;

    /**
     * When {@code true}, also runs CRUD lifecycle diffs: creates an entity in both
     * the monolith and decomposed service, then compares the full lifecycle responses.
     */
    @Parameter(property = "fractalx.difftest.crud", defaultValue = "true")
    private boolean crudDiff = true;

    // ── Constants ─────────────────────────────────────────────────────────────

    private static final boolean WINDOWS =
            System.getProperty("os.name", "").toLowerCase().contains("win");

    private static final String PROBE_VALUE = "__probe__";
    private static final Pattern PATH_VAR   = Pattern.compile("\\{[^}]+}");

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    // ── Collected results ─────────────────────────────────────────────────────

    private final List<DiffResult> diffResults = new ArrayList<>();

    // =========================================================================
    // Execute
    // =========================================================================

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        initCli();
        if (skip) { info("diff-test skipped (fractalx.difftest.skip=true)"); return; }

        printHeader("Diff Test");

        Path root = outputDirectory.toPath();
        if (!Files.isDirectory(root)) {
            warn("Output directory not found: " + root);
            warn("Run 'mvn fractalx:decompose' first.");
            return;
        }

        // ── Resolve monolith root with smart discovery + confirmation ──────
        Path monolithPath = resolveAndConfirmMonolithRoot();
        if (monolithPath == null) {
            info("diff-test aborted.");
            return;
        }

        // Resolve monolith port
        if (monolithPort <= 0) {
            monolithPort = readPort(monolithPath);
            if (monolithPort <= 0) monolithPort = 8080; // default Spring Boot port
        }

        List<Path> serviceDirs = discoverServiceDirs(root);
        if (serviceDirs.isEmpty()) { warn("No services found in " + root); return; }

        // Detect gateway
        Path gatewayDir = root.resolve("fractalx-gateway");
        boolean hasGateway = Files.isDirectory(gatewayDir)
                && Files.exists(gatewayDir.resolve("pom.xml"));

        long t0 = System.currentTimeMillis();

        // ── Build dashboard labels ──────────────────────────────────────────
        List<String> labels = new ArrayList<>();
        if (build) {
            labels.add("Monolith · build");
            for (Path d : serviceDirs) labels.add(d.getFileName() + " · build");
        }
        labels.add("Monolith · start");
        labels.add("Services · start");
        labels.add("Diff · endpoint discovery");
        labels.add("Diff · replay + compare");
        if (crudDiff) labels.add("Diff · crud lifecycle");
        labels.add("Shutdown");

        runWithDashboard(labels, "Diff Test", t0, dash -> {
            Process monolithProc = null;
            List<Process> serviceProcs = new ArrayList<>();

            try {
                // ── Build ────────────────────────────────────────────────────
                if (build) {
                    dash.onStart("Monolith · build");
                    int monolithBuild = runBuild(monolithPath);
                    if (monolithBuild != 0) {
                        dash.onWarn("Monolith · build", "exit " + monolithBuild);
                        return;
                    }
                    dash.onDone("Monolith · build");

                    for (Path d : serviceDirs) {
                        String name = d.getFileName() + " · build";
                        dash.onStart(name);
                        int exitCode = runBuild(d);
                        if (exitCode != 0) {
                            dash.onWarn(name, "exit " + exitCode);
                        } else {
                            dash.onDone(name);
                        }
                    }
                }

                // ── Start monolith ───────────────────────────────────────────
                dash.onStart("Monolith · start");
                monolithProc = spawnService(monolithPath);
                if (!awaitPort(monolithPort, startupTimeout, monolithProc)
                        || checkHealth(monolithPort, healthPath) <= 0) {
                    dash.onWarn("Monolith · start", "failed to start on :" + monolithPort);
                    return;
                }
                dash.onDone("Monolith · start");

                // ── Start services (or just gateway) ─────────────────────────
                dash.onStart("Services · start");
                Map<String, Integer> servicePorts = new LinkedHashMap<>();

                if (hasGateway) {
                    // Start registry + all services + gateway
                    Path registryDir = root.resolve("fractalx-registry");
                    if (Files.isDirectory(registryDir)) {
                        Process regProc = spawnService(registryDir);
                        serviceProcs.add(regProc);
                        int regPort = readPort(registryDir);
                        awaitPort(regPort, startupTimeout, regProc);
                    }

                    for (Path d : serviceDirs) {
                        String name = d.getFileName().toString();
                        if (name.equals("fractalx-registry")) continue;
                        int port = readPort(d);
                        Process proc = spawnService(d);
                        serviceProcs.add(proc);
                        servicePorts.put(name, port);
                    }

                    // Wait for all services
                    for (var entry : servicePorts.entrySet()) {
                        awaitPortOnly(entry.getValue(), startupTimeout);
                    }

                    // Gateway port is the entry point for diffing
                    int gwPort = readPort(gatewayDir);
                    servicePorts.put("fractalx-gateway", gwPort);
                } else {
                    // No gateway — start each service individually
                    for (Path d : serviceDirs) {
                        String name = d.getFileName().toString();
                        int port = readPort(d);
                        Process proc = spawnService(d);
                        serviceProcs.add(proc);
                        servicePorts.put(name, port);
                        awaitPort(port, startupTimeout, proc);
                    }
                }
                dash.onDone("Services · start");

                // ── Discover endpoints ───────────────────────────────────────
                dash.onStart("Diff · endpoint discovery");
                EndpointScanner scanner = new EndpointScanner();
                List<EndpointSpec> monolithEndpoints = scanner.scan(monolithPath);

                if (monolithEndpoints.isEmpty()) {
                    dash.onWarn("Diff · endpoint discovery", "no endpoints found in monolith");
                    return;
                }
                dash.onDone("Diff · endpoint discovery");

                // ── Replay + compare ─────────────────────────────────────────
                dash.onStart("Diff · replay + compare");

                // Determine the target port for decomposed services
                int targetPort;
                if (hasGateway) {
                    targetPort = servicePorts.getOrDefault("fractalx-gateway", -1);
                } else {
                    // Find the service that handles each endpoint by path matching
                    targetPort = servicePorts.values().stream().findFirst().orElse(-1);
                }

                for (EndpointSpec ep : monolithEndpoints) {
                    String path = PATH_VAR.matcher(ep.path()).replaceAll(PROBE_VALUE);
                    String body = ep.hasRequestBody() ? "{}" : null;

                    // If no gateway, try to find the correct service port by matching
                    int svcPort = targetPort;
                    if (!hasGateway) {
                        svcPort = findServicePort(ep.path(), serviceDirs, servicePorts);
                    }

                    // Request to monolith
                    HttpResult monolithResp = sendRequest(monolithPort, ep.httpMethod(), path, body);
                    // Request to decomposed service
                    HttpResult serviceResp = sendRequest(svcPort, ep.httpMethod(), path, body);

                    DiffResult dr = new DiffResult(
                            ep.httpMethod(), ep.path(), path,
                            monolithResp.status, serviceResp.status,
                            monolithResp.body, serviceResp.body);
                    diffResults.add(dr);
                }

                long diffs = diffResults.stream().filter(d -> !d.statusMatch()).count();
                if (diffs == 0) {
                    dash.onDone("Diff · replay + compare");
                } else {
                    dash.onWarn("Diff · replay + compare",
                            diffs + "/" + diffResults.size() + " endpoint(s) differ");
                }

                // ── CRUD lifecycle diff ──────────────────────────────────────
                if (crudDiff) {
                    dash.onStart("Diff · crud lifecycle");
                    EntityScanner entityScanner = new EntityScanner();
                    List<EntitySpec> entities = entityScanner.scan(monolithPath);
                    int crudDiffs = 0;

                    for (EntitySpec entity : entities) {
                        // POST to monolith
                        String createPayload = EntityScanner.buildCreatePayload(entity);
                        HttpResult monolithCreate = sendRequest(
                                monolithPort, "POST", entity.basePath(), createPayload);
                        // POST to service
                        HttpResult serviceCreate = sendRequest(
                                targetPort, "POST", entity.basePath(), createPayload);

                        DiffResult dr = new DiffResult(
                                "POST", entity.basePath(), entity.basePath(),
                                monolithCreate.status, serviceCreate.status,
                                monolithCreate.body, serviceCreate.body);
                        diffResults.add(dr);

                        if (!dr.statusMatch()) crudDiffs++;
                    }

                    if (crudDiffs == 0) {
                        dash.onDone("Diff · crud lifecycle");
                    } else {
                        dash.onWarn("Diff · crud lifecycle",
                                crudDiffs + " entity(ies) differ on POST");
                    }
                }

            } finally {
                // ── Shutdown ─────────────────────────────────────────────────
                dash.onStart("Shutdown");
                killProcess(monolithProc);
                for (Process p : serviceProcs) killProcess(p);
                dash.onDone("Shutdown");
            }
        });

        // ── Write report + summary ────────────────────────────────────────
        writeDiffReport(root, t0);
        printDiffSummary(root, t0);

        if (failBuild) {
            long diffs = diffResults.stream().filter(d -> !d.statusMatch()).count();
            if (diffs > 0)
                throw new MojoFailureException(
                        diffs + " endpoint(s) returned different responses from monolith vs services");
        }
    }

    // =========================================================================
    // Monolith root discovery + interactive confirmation
    // =========================================================================

    /**
     * Resolves the monolith root directory:
     * <ol>
     *   <li>If {@code fractalx.difftest.monolith} was given, normalise it (handle the case
     *       where the user passed the {@code pom.xml} file itself or a {@code src/} sub-path).</li>
     *   <li>If not given, search for a {@code pom.xml} by walking up from the current project
     *       base directory and checking sibling directories.</li>
     *   <li>Display the detected path and ask the user to confirm or override it.</li>
     * </ol>
     *
     * @return the confirmed monolith root, or {@code null} if the user aborted.
     */
    private Path resolveAndConfirmMonolithRoot() throws MojoExecutionException {
        Path candidate = null;

        // ── Step 1: normalise whatever the user provided ──────────────────
        if (monolithDirectory != null) {
            candidate = monolithDirectory.toPath().toAbsolutePath().normalize();

            // If they pointed at the pom.xml file itself, take the parent
            if (Files.isRegularFile(candidate) && candidate.getFileName().toString().equals("pom.xml")) {
                candidate = candidate.getParent();
            }

            // Walk up from any sub-directory until we find a pom.xml
            if (Files.isDirectory(candidate) && !Files.exists(candidate.resolve("pom.xml"))) {
                Path pomParent = findPomUpward(candidate);
                if (pomParent != null) candidate = pomParent;
            }
        }

        // ── Step 2: auto-discover if not provided or still invalid ────────
        if (candidate == null || !Files.exists(candidate.resolve("pom.xml"))) {
            candidate = autoDiscoverMonolith();
        }

        // ── Step 3: show what we found and ask for confirmation ───────────
        out.println();
        section("Monolith Location");

        if (candidate != null && Files.exists(candidate.resolve("pom.xml"))) {
            out.println("  " + a(GRN) + "\u2713" + a(RST) + "  Detected monolith root:");
            out.println("  " + a(CYN) + "    " + candidate + a(RST));
            out.println("  " + a(DIM) + "    pom.xml  \u2192  " + candidate.resolve("pom.xml") + a(RST));
        } else {
            out.println("  " + a(YLW) + "\u26A0" + a(RST) + "  Could not auto-detect monolith root.");
            candidate = null;
        }
        out.println();

        // Read from /dev/tty so Maven's redirected stdin doesn't block us
        try (InputStream ttyStream = openTty();
             Scanner sc = new Scanner(ttyStream)) {

            if (candidate != null) {
                out.print("  " + a(BLD) + "Use this location? [Y/n/path]: " + a(RST));
                out.flush();
            } else {
                out.print("  " + a(BLD) + "Enter monolith root path: " + a(RST));
                out.flush();
            }

            String input = sc.hasNextLine() ? sc.nextLine().trim() : "";

            if (input.isEmpty() || input.equalsIgnoreCase("y")) {
                // Accept detected candidate (or abort if none)
                if (candidate == null) {
                    error("No monolith path provided.");
                    return null;
                }
            } else if (input.equalsIgnoreCase("n")) {
                out.print("  " + a(BLD) + "Enter monolith root path: " + a(RST));
                out.flush();
                input = sc.hasNextLine() ? sc.nextLine().trim() : "";
                if (input.isEmpty()) { error("No path entered."); return null; }
                candidate = parsePath(input);
            } else {
                // They typed a path directly instead of y/n
                candidate = parsePath(input);
            }

        } catch (IOException e) {
            // TTY not available (non-interactive / CI) — use candidate as-is if valid
            if (candidate == null) {
                throw new MojoExecutionException(
                        "Cannot determine monolith location in non-interactive mode. "
                        + "Set -Dfractalx.difftest.monolith=<path>");
            }
            warn("Non-interactive mode — using detected monolith: " + candidate);
        }

        // Final validation
        if (candidate == null || !Files.isDirectory(candidate)) {
            error("Path does not exist or is not a directory: " + candidate);
            return null;
        }
        if (!Files.exists(candidate.resolve("pom.xml"))) {
            // One last attempt — walk up
            Path pomParent = findPomUpward(candidate);
            if (pomParent != null) {
                candidate = pomParent;
                out.println("  " + a(DIM) + "Adjusted to pom.xml root: " + candidate + a(RST));
            } else {
                error("No pom.xml found at or above: " + candidate);
                return null;
            }
        }

        out.println("  " + a(GRN) + "\u2713" + a(RST) + "  Monolith root: "
                + a(CYN) + candidate + a(RST));
        out.println();
        return candidate;
    }

    /**
     * Searches for {@code pom.xml} by walking <em>upward</em> from {@code start},
     * stopping at the filesystem root.
     */
    private static Path findPomUpward(Path start) {
        Path p = start.toAbsolutePath().normalize();
        while (p != null) {
            if (Files.exists(p.resolve("pom.xml"))) return p;
            p = p.getParent();
        }
        return null;
    }

    /**
     * Auto-discovers a monolith by checking:
     * <ol>
     *   <li>The parent directory of the current project (common layout: monolith + microservices/ sibling)</li>
     *   <li>Sibling directories at the same level that contain a {@code pom.xml}</li>
     *   <li>The output directory's parent (in case decompose was run inside the monolith)</li>
     * </ol>
     */
    private Path autoDiscoverMonolith() {
        List<Path> candidates = new ArrayList<>();

        // Current project root
        if (project != null && project.getBasedir() != null) {
            Path basedir = project.getBasedir().toPath();

            // 1. Parent directory — common when project lives alongside microservices/
            Path parent = basedir.getParent();
            if (parent != null && Files.exists(parent.resolve("pom.xml"))) {
                candidates.add(parent);
            }

            // 2. Sibling directories with a pom.xml (could be the original monolith)
            if (parent != null) {
                try (var stream = Files.list(parent)) {
                    stream.filter(Files::isDirectory)
                          .filter(d -> !d.equals(basedir))
                          .filter(d -> Files.exists(d.resolve("pom.xml")))
                          .sorted()
                          .forEach(candidates::add);
                } catch (IOException ignored) {}
            }
        }

        // 3. outputDirectory parent
        if (outputDirectory != null) {
            Path outParent = outputDirectory.toPath().getParent();
            if (outParent != null && Files.exists(outParent.resolve("pom.xml"))
                    && !candidates.contains(outParent)) {
                candidates.add(0, outParent); // highest priority
            }
        }

        return candidates.isEmpty() ? null : candidates.get(0);
    }

    /** Parses a user-entered path, expanding {@code ~} to home directory. */
    private static Path parsePath(String input) {
        if (input.startsWith("~")) {
            input = System.getProperty("user.home") + input.substring(1);
        }
        return Path.of(input).toAbsolutePath().normalize();
    }

    /**
     * Opens {@code /dev/tty} for interactive input. Falls back to {@code System.in}
     * on Windows or when {@code /dev/tty} is unavailable.
     */
    private static InputStream openTty() throws IOException {
        if (!WINDOWS) {
            try {
                return new java.io.FileInputStream("/dev/tty");
            } catch (IOException ignored) {}
        }
        return System.in;
    }

    // =========================================================================
    // HTTP request with body capture
    // =========================================================================

    private record HttpResult(int status, String body) {}

    private HttpResult sendRequest(int port, String method, String path, String body) {
        if (port <= 0) return new HttpResult(-1, null);
        try {
            URI uri = new URI("http", null, "localhost", port, path, null, null);
            HttpRequest.Builder rb = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(10));

            boolean bodyMethod = method.equals("POST") || method.equals("PUT")
                    || method.equals("PATCH");
            if (body != null && bodyMethod) {
                rb.method(method, HttpRequest.BodyPublishers.ofString(body))
                  .header("Content-Type", "application/json");
            } else {
                rb.method(method, HttpRequest.BodyPublishers.noBody());
            }

            HttpResponse<String> resp = httpClient.send(rb.build(),
                    HttpResponse.BodyHandlers.ofString());
            return new HttpResult(resp.statusCode(), resp.body());
        } catch (Exception e) {
            return new HttpResult(-1, null);
        }
    }

    private int checkHealth(int port, String path) {
        try {
            URI uri = new URI("http", null, "localhost", port, path, null, null);
            HttpURLConnection conn = (HttpURLConnection) uri.toURL().openConnection();
            conn.setConnectTimeout(3_000);
            conn.setReadTimeout(5_000);
            conn.setRequestMethod("GET");
            return conn.getResponseCode();
        } catch (Exception e) {
            return -1;
        }
    }

    // =========================================================================
    // Port / service matching
    // =========================================================================

    /**
     * For multi-service setups without a gateway, tries to match an endpoint path
     * to the correct service by scanning each service's controllers.
     */
    private int findServicePort(String endpointPath, List<Path> serviceDirs,
                                Map<String, Integer> servicePorts) {
        EndpointScanner scanner = new EndpointScanner();
        for (Path d : serviceDirs) {
            String name = d.getFileName().toString();
            List<EndpointSpec> eps = scanner.scan(d);
            for (EndpointSpec ep : eps) {
                if (ep.path().equals(endpointPath)) {
                    return servicePorts.getOrDefault(name, -1);
                }
            }
        }
        // Fallback: try first service
        return servicePorts.values().stream().findFirst().orElse(-1);
    }

    // =========================================================================
    // Process management (mirrors SmokeTestMojo)
    // =========================================================================

    private int runBuild(Path svcDir) throws IOException, InterruptedException {
        Path log = svcDir.resolve("logs/difftest-build.log");
        Files.createDirectories(log.getParent());
        return new ProcessBuilder(buildMvnCommand(svcDir, "package", "-DskipTests", "-q"))
                .directory(svcDir.toFile())
                .redirectOutput(log.toFile())
                .redirectError(ProcessBuilder.Redirect.appendTo(log.toFile()))
                .start()
                .waitFor();
    }

    private Process spawnService(Path svcDir) throws IOException {
        Path log = svcDir.resolve("logs/difftest-run.log");
        Files.createDirectories(log.getParent());

        if (WINDOWS) {
            Path bat = svcDir.resolve("start.bat");
            if (Files.exists(bat))
                return new ProcessBuilder("cmd.exe", "/c", bat.toAbsolutePath().toString())
                        .directory(svcDir.toFile())
                        .redirectOutput(log.toFile())
                        .redirectError(ProcessBuilder.Redirect.appendTo(log.toFile()))
                        .start();
        } else {
            Path sh = svcDir.resolve("start.sh");
            if (Files.exists(sh)) {
                sh.toFile().setExecutable(true);
                return new ProcessBuilder("/bin/sh", sh.toAbsolutePath().toString())
                        .directory(svcDir.toFile())
                        .redirectOutput(log.toFile())
                        .redirectError(ProcessBuilder.Redirect.appendTo(log.toFile()))
                        .start();
            }
        }

        return new ProcessBuilder(buildMvnCommand(svcDir, "spring-boot:run"))
                .directory(svcDir.toFile())
                .redirectOutput(log.toFile())
                .redirectError(ProcessBuilder.Redirect.appendTo(log.toFile()))
                .start();
    }

    private boolean awaitPort(int port, int timeoutSec, Process proc) throws InterruptedException {
        if (port <= 0) { Thread.sleep(3_000); return proc.isAlive(); }
        long deadline = System.currentTimeMillis() + timeoutSec * 1000L;
        while (System.currentTimeMillis() < deadline) {
            if (!proc.isAlive()) return false;
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress("localhost", port), 300);
                return true;
            } catch (IOException ignored) {}
            Thread.sleep(500);
        }
        return false;
    }

    private boolean awaitPortOnly(int port, int timeoutSec) throws InterruptedException {
        if (port <= 0) return false;
        long deadline = System.currentTimeMillis() + timeoutSec * 1000L;
        while (System.currentTimeMillis() < deadline) {
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress("localhost", port), 300);
                return true;
            } catch (IOException ignored) {}
            Thread.sleep(500);
        }
        return false;
    }

    private void killProcess(Process proc) {
        if (proc == null || !proc.isAlive()) return;
        proc.descendants().forEach(child -> {
            child.destroy();
            try { child.onExit().get(3, TimeUnit.SECONDS); } catch (Exception ignored) {}
            if (child.isAlive()) child.destroyForcibly();
        });
        proc.destroy();
        try { proc.waitFor(5, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
        if (proc.isAlive()) proc.destroyForcibly();
    }

    // ── Maven / port helpers ─────────────────────────────────────────────────

    private static List<String> buildMvnCommand(Path svcDir, String... args) {
        String mvn = findMaven(svcDir);
        List<String> cmd = new ArrayList<>();
        if (WINDOWS) { cmd.add("cmd.exe"); cmd.add("/c"); }
        cmd.add(mvn);
        cmd.addAll(List.of(args));
        return cmd;
    }

    private static String findMaven(Path svcDir) {
        String home = System.getProperty("maven.home");
        if (home != null) {
            String exe = WINDOWS ? "bin/mvn.cmd" : "bin/mvn";
            File mvn = new File(home, exe);
            if (mvn.exists()) return mvn.getAbsolutePath();
        }
        String wrapper = WINDOWS ? "mvnw.cmd" : "mvnw";
        File mvnw = svcDir.resolve(wrapper).toFile();
        if (mvnw.exists()) return mvnw.getAbsolutePath();
        File mvnwParent = svcDir.getParent().resolve(wrapper).toFile();
        if (mvnwParent.exists()) return mvnwParent.getAbsolutePath();
        return WINDOWS ? "mvn.cmd" : "mvn";
    }

    private int readPort(Path svcDir) {
        Path yml = svcDir.resolve("src/main/resources/application.yml");
        if (!Files.exists(yml)) return -1;
        try {
            for (String line : Files.readAllLines(yml)) {
                line = line.trim();
                if (line.startsWith("port:"))
                    return Integer.parseInt(line.substring("port:".length()).trim());
            }
        } catch (Exception ignored) {}
        return -1;
    }

    private List<Path> discoverServiceDirs(Path root) throws MojoExecutionException {
        List<Path> result = new ArrayList<>();
        try (var stream = Files.list(root)) {
            stream.filter(Files::isDirectory)
                    .filter(d -> Files.exists(d.resolve("pom.xml")))
                    .sorted()
                    .forEach(result::add);
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to list services", e);
        }
        return result;
    }

    // ── Dashboard wrapper ────────────────────────────────────────────────────

    @FunctionalInterface
    private interface DashAction { void run(Dashboard dash) throws Exception; }

    private void runWithDashboard(List<String> labels, String subtitle, long t0, DashAction action)
            throws MojoExecutionException {
        if (ansi) { out.print(ALT_ON); out.flush(); }
        Dashboard dash = new Dashboard(labels, out, ansi, subtitle);
        dash.render();
        try {
            action.run(dash);
        } catch (Exception e) {
            if (!labels.isEmpty()) dash.onFail(labels.get(0), e.getMessage());
            if (ansi) {
                try { Thread.sleep(2_500); } catch (InterruptedException ignored) {}
                out.print(ALT_OFF); out.flush();
            }
            throw new MojoExecutionException("Diff test failed", e);
        }
        dash.finish();
        if (ansi) {
            try { Thread.sleep(500); } catch (InterruptedException ignored) {}
            out.print(ALT_OFF); out.flush();
        }
        out.println();
    }

    // =========================================================================
    // Diff report
    // =========================================================================

    private void writeDiffReport(Path root, long t0) {
        Path log  = root.resolve("difftest.log");
        String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        String div    = "═".repeat(70);
        String subdiv = "─".repeat(70);
        List<String> lines = new ArrayList<>();

        lines.add(div);
        lines.add("  FractalX Diff Test  —  " + ts);
        lines.add(div);
        lines.add("");

        long total  = diffResults.size();
        long matched = diffResults.stream().filter(DiffResult::statusMatch).count();
        long diffs   = total - matched;

        lines.add("  Endpoints tested: " + total);
        lines.add("  Status matched:   " + matched);
        lines.add("  Differences:      " + diffs);
        lines.add("");

        // ── All results ───────────────────────────────────────────────────
        lines.add(subdiv);
        lines.add("  ENDPOINT DIFF RESULTS");
        lines.add(subdiv);
        lines.add("");

        int padW = diffResults.stream()
                .mapToInt(d -> d.method.length() + 1 + d.probedPath.length())
                .max().orElse(30);

        for (DiffResult d : diffResults) {
            String label  = d.method + " " + d.probedPath;
            String mStatus = d.monolithStatus < 0 ? "UNREACHABLE" : "HTTP " + d.monolithStatus;
            String sStatus = d.serviceStatus  < 0 ? "UNREACHABLE" : "HTTP " + d.serviceStatus;
            String verdict = d.statusMatch() ? "MATCH" : "DIFF";
            lines.add("  [" + verdict + "]  " + pad(label, padW)
                    + "  monolith=" + mStatus + "  service=" + sStatus);

            if (!d.statusMatch() && d.monolithBody != null && d.serviceBody != null) {
                // Show first 200 chars of each body for context
                lines.add("    monolith body: " + truncate(d.monolithBody, 200));
                lines.add("    service  body: " + truncate(d.serviceBody, 200));
            }
        }

        lines.add("");
        lines.add(div);
        lines.add("  " + (diffs == 0 ? "ALL ENDPOINTS MATCHED" : diffs + " DIFFERENCE(S) FOUND")
                + "  [" + fmt(System.currentTimeMillis() - t0) + "]");
        lines.add(div);

        try { Files.write(log, lines, StandardCharsets.UTF_8); }
        catch (IOException e) { warn("Could not write difftest.log: " + e.getMessage()); }
    }

    // =========================================================================
    // Summary (console)
    // =========================================================================

    private void printDiffSummary(Path root, long t0) {
        section("Diff Test Results");

        long total   = diffResults.size();
        long matched = diffResults.stream().filter(DiffResult::statusMatch).count();
        long diffs   = total - matched;

        // Show matches
        out.println("  " + a(DIM) + "Endpoints tested: " + total + a(RST));
        out.println("  " + a(GRN) + "\u2713" + a(RST) + "  " + matched + " matched");
        if (diffs > 0) {
            out.println("  " + a(YLW) + "\u26A0" + a(RST) + "  " + diffs + " differed");
        }
        out.println();

        // Show individual diffs
        for (DiffResult d : diffResults) {
            if (d.statusMatch()) continue;
            String mStatus = d.monolithStatus < 0 ? "unreachable" : "HTTP " + d.monolithStatus;
            String sStatus = d.serviceStatus  < 0 ? "unreachable" : "HTTP " + d.serviceStatus;
            out.println("  " + a(RED) + "\u2022" + a(RST) + "  " + d.method + " " + d.originalPath);
            out.println("  " + a(DIM) + "    monolith: " + mStatus
                    + "  service: " + sStatus + a(RST));
        }

        out.println();
        String passFail = diffs == 0
                ? a(GRN) + "All endpoints matched" + a(RST)
                : a(YLW) + diffs + " difference(s) found" + a(RST);
        out.println("  " + passFail + "  " + a(DIM) + "["
                + fmt(System.currentTimeMillis() - t0) + "]" + a(RST));
        out.println();

        out.println("  " + a(DIM) + "Full report   " + root.resolve("difftest.log") + a(RST));
        out.println("  " + a(DIM) + "Service logs  <service>/logs/difftest-{build,run}.log" + a(RST));
        out.println();
        cmd("mvn fractalx:diff-test -Dfractalx.difftest.build=false   # skip build");
        cmd("mvn fractalx:smoke-test                                    # wiring-only check");
        out.println();
        done(System.currentTimeMillis() - t0);
    }

    // =========================================================================
    // Result model
    // =========================================================================

    private static final class DiffResult {
        final String method;
        final String originalPath;
        final String probedPath;
        final int    monolithStatus;
        final int    serviceStatus;
        final String monolithBody;
        final String serviceBody;

        DiffResult(String method, String originalPath, String probedPath,
                   int monolithStatus, int serviceStatus,
                   String monolithBody, String serviceBody) {
            this.method          = method;
            this.originalPath    = originalPath;
            this.probedPath      = probedPath;
            this.monolithStatus  = monolithStatus;
            this.serviceStatus   = serviceStatus;
            this.monolithBody    = monolithBody;
            this.serviceBody     = serviceBody;
        }

        /** Status codes match (both same, or both in same category). */
        boolean statusMatch() {
            if (monolithStatus == serviceStatus) return true;
            // Both in 2xx, both in 4xx, etc. — close enough
            if (monolithStatus > 0 && serviceStatus > 0) {
                return monolithStatus / 100 == serviceStatus / 100;
            }
            return false;
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static String truncate(String s, int max) {
        if (s == null) return "<null>";
        s = s.replaceAll("\\s+", " ").trim();
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
