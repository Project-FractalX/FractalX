package org.fractalx.maven;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.fractalx.core.verifier.EndpointScanner;
import org.fractalx.core.verifier.EndpointScanner.EndpointSpec;

import java.io.File;
import java.io.IOException;
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
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Comprehensive smoke-test runner for generated microservices.
 *
 * <p>Executes up to four phases of testing, each opt-in or opt-out:
 *
 * <ol>
 *   <li><b>Per-Service</b> (always) — build, start, health-check, endpoint probe, shutdown.
 *       Tests each service in isolation.</li>
 *   <li><b>Integration</b> (default: on) — starts the registry + all business services
 *       simultaneously, verifies service discovery, tests gateway routing, and probes saga
 *       orchestrator endpoints. Skipped automatically when {@code fractalx-registry} is absent.
 *       Disable with {@code -Dfractalx.smoketest.integration=false}.</li>
 *   <li><b>Compose</b> (default: off) — runs {@code docker compose up}, waits for container
 *       health, probes the gateway, and tears down.
 *       Enable with {@code -Dfractalx.smoketest.compose=true}.</li>
 * </ol>
 *
 * <p>Two log files are written to {@code <outputDirectory>/}:
 * <ul>
 *   <li>{@code smoketest.log} — full report for every phase and service</li>
 *   <li>{@code smoketest-errors.log} — only the failures with context for quick triage</li>
 * </ul>
 *
 * <pre>
 *   mvn fractalx:smoke-test                                        # per-service + integration
 *   mvn fractalx:smoke-test -Dfractalx.smoketest.endpoints=false   # skip endpoint probing
 *   mvn fractalx:smoke-test -Dfractalx.smoketest.integration=false # skip integration phase
 *   mvn fractalx:smoke-test -Dfractalx.smoketest.compose=true      # + docker compose phase
 *   mvn fractalx:smoke-test -Dfractalx.smoketest.failBuild=true    # fail build on any error
 * </pre>
 */
@Mojo(name = "smoke-test", requiresProject = true, threadSafe = false)
public class SmokeTestMojo extends FractalxBaseMojo {

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    /** Directory that contains the generated microservices. */
    @Parameter(property = "fractalx.outputDirectory",
               defaultValue = "${project.basedir}/microservices")
    private File outputDirectory;

    /**
     * When {@code true} (default), runs {@code mvn package -DskipTests} before starting each service.
     * <p>Java initialisers match Maven defaults so values are correct when this Mojo is invoked
     * programmatically (e.g. from {@code MenuMojo}) without Maven parameter injection.
     */
    @Parameter(property = "fractalx.smoketest.build", defaultValue = "true")
    private boolean build = true;

    /** Seconds to wait for the HTTP port to open before declaring a startup failure. */
    @Parameter(property = "fractalx.smoketest.timeout", defaultValue = "120")
    private int startupTimeout = 120;

    /** Actuator endpoint polled after the port opens. Any HTTP response = Spring context loaded. */
    @Parameter(property = "fractalx.smoketest.health", defaultValue = "/actuator/health")
    private String healthPath = "/actuator/health";

    /** Name of a single service to test. When blank (default) all services are tested. */
    @Parameter(property = "fractalx.smoketest.service", defaultValue = "")
    private String service = "";

    /** When {@code true}, throws {@link MojoFailureException} if any phase has failures. */
    @Parameter(property = "fractalx.smoketest.failBuild", defaultValue = "false")
    private boolean failBuild = false;

    /** Skip this mojo entirely. */
    @Parameter(property = "fractalx.smoketest.skip", defaultValue = "false")
    private boolean skip = false;

    // ── Phase toggles ─────────────────────────────────────────────────────────

    /** Probe all REST endpoints per-service (while service is running). */
    @Parameter(property = "fractalx.smoketest.endpoints", defaultValue = "true")
    private boolean endpoints = true;

    /**
     * Multi-service integration tests: registry discovery, gateway routing, saga endpoints.
     * Starts all services simultaneously after per-service tests complete.
     * Runs automatically when {@code fractalx-registry} is present; no-op otherwise.
     */
    @Parameter(property = "fractalx.smoketest.integration", defaultValue = "true")
    private boolean integration = true;

    /**
     * Docker Compose full-stack test: {@code docker compose up}, container health checks,
     * gateway probe, and {@code docker compose down}.
     * Disabled by default. Enable with {@code -Dfractalx.smoketest.compose=true}.
     */
    @Parameter(property = "fractalx.smoketest.compose", defaultValue = "false")
    private boolean compose = false;

    // ── Constants ─────────────────────────────────────────────────────────────

    private static final boolean WINDOWS =
            System.getProperty("os.name", "").toLowerCase().contains("win");

    private static final String PROBE_VALUE = "__probe__";
    private static final Pattern PATH_VAR   = Pattern.compile("\\{[^}]+}");

    /** Well-known infrastructure directory names. */
    private static final Set<String> INFRA_DIRS = Set.of(
            "fractalx-registry", "fractalx-gateway",
            "admin-service", "logger-service", "fractalx-saga-orchestrator");

    /** HTTP client for endpoint probing (supports PATCH unlike HttpURLConnection). */
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    // ── Collected results ─────────────────────────────────────────────────────

    private final List<SmokeResult>     perServiceResults  = new ArrayList<>();
    private IntegrationResult           integrationResult  = null;
    private ComposeResult               composeResult      = null;

    // =========================================================================
    // Execute
    // =========================================================================

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        initCli();
        if (skip) { info("smoke-test skipped (fractalx.smoketest.skip=true)"); return; }

        printHeader("Smoke Test");

        Path root = outputDirectory.toPath();
        if (!Files.isDirectory(root)) {
            warn("Output directory not found: " + root);
            warn("Run 'mvn fractalx:decompose' first.");
            return;
        }

        List<Path> discovered = discoverServiceDirs(root);
        if (!service.isBlank()) {
            discovered = discovered.stream()
                    .filter(d -> d.getFileName().toString().equals(service.trim()))
                    .toList();
            if (discovered.isEmpty())
                throw new MojoExecutionException("Service not found: " + service.trim());
        }
        if (discovered.isEmpty()) { warn("No services found in " + root); return; }

        final List<Path> dirs = List.copyOf(discovered);
        long t0 = System.currentTimeMillis();

        // ── Detect infrastructure ──────────────────────────────────────────
        Path registryDir = root.resolve("fractalx-registry");
        Path gatewayDir  = root.resolve("fractalx-gateway");
        Path sagaDir     = root.resolve("fractalx-saga-orchestrator");
        Path composeFile = root.resolve("docker-compose.yml");

        boolean hasRegistry = Files.isDirectory(registryDir) && Files.exists(registryDir.resolve("pom.xml"));
        boolean hasGateway  = Files.isDirectory(gatewayDir)  && Files.exists(gatewayDir.resolve("pom.xml"));
        boolean hasSaga     = Files.isDirectory(sagaDir)     && Files.exists(sagaDir.resolve("pom.xml"));
        boolean hasCompose  = Files.exists(composeFile);

        List<Path> businessDirs = dirs.stream()
                .filter(d -> !INFRA_DIRS.contains(d.getFileName().toString()))
                .toList();

        // ── Build dashboard labels ─────────────────────────────────────────
        List<String> labels = new ArrayList<>();

        // Phase 1: per-service
        if (build) for (Path d : dirs) labels.add(d.getFileName() + " · build");
        for (Path d : dirs) labels.add(d.getFileName() + " · start + health");
        if (endpoints) for (Path d : dirs) labels.add(d.getFileName() + " · endpoints");

        // Phase 2: integration
        if (integration && hasRegistry) {
            labels.add("Integration · start services");
            labels.add("Integration · registry discovery");
            if (hasGateway) labels.add("Integration · gateway routing");
            if (hasSaga) labels.add("Integration · saga endpoints");
            labels.add("Integration · shutdown");
        }

        // Phase 3: compose
        if (compose && hasCompose) {
            labels.add("Compose · up");
            labels.add("Compose · health check");
            labels.add("Compose · shutdown");
        }

        // ── Run all phases ─────────────────────────────────────────────────
        runWithDashboard(labels, "Smoke Test", t0, dash -> {
            // Phase 1: Per-service (build → start → health → endpoints → kill)
            runPerServicePhase(dash, dirs, root);

            // Ensure all ports from Phase 1 are fully released before Phase 2.
            // killProcess() now kills the entire process tree, but the OS may keep
            // sockets in TIME_WAIT briefly. Polling here avoids BindException in Phase 2.
            // Both HTTP and gRPC ports (HTTP + 10000) must be free.
            if ((integration && hasRegistry) || (compose && hasCompose)) {
                for (Path d : dirs) {
                    int p = readPort(d);
                    if (p > 0) {
                        awaitPortFree(p, 10);
                        awaitPortFree(p + 10000, 10);   // gRPC port
                    }
                }
            }

            // Phase 2: Integration (registry + all services + gateway + saga)
            if (integration && hasRegistry) {
                runIntegrationPhase(dash, dirs, businessDirs,
                        registryDir, gatewayDir, sagaDir,
                        hasGateway, hasSaga);

                // Wait for ports to be free before Compose phase
                if (compose && hasCompose) {
                    for (Path d : dirs) {
                        int p = readPort(d);
                        if (p > 0) {
                            awaitPortFree(p, 10);
                            awaitPortFree(p + 10000, 10);
                        }
                    }
                }
            }

            // Phase 3: Compose (docker compose up/down)
            if (compose && hasCompose) {
                runComposePhase(dash, composeFile, gatewayDir, hasGateway);
            }
        });

        // ── Write logs ─────────────────────────────────────────────────────
        writeConsolidatedLog(root, dirs, t0);
        writeErrorLog(root, t0);

        printSummary(root, t0);

        if (failBuild) {
            int totalFail = countTotalFailures();
            if (totalFail > 0)
                throw new MojoFailureException(totalFail + " failure(s) across all smoke-test phases");
        }
    }

    // =========================================================================
    // Phase 1: Per-Service Tests
    // =========================================================================

    private void runPerServicePhase(Dashboard dash, List<Path> dirs, Path root) throws Exception {
        EndpointScanner scanner = endpoints ? new EndpointScanner() : null;

        for (Path svcDir : dirs) {
            String name = svcDir.getFileName().toString();
            SmokeResult r = new SmokeResult(name);

            // ── Build ──────────────────────────────────────────────────────
            if (build) {
                String buildLabel = name + " · build";
                dash.onStart(buildLabel);
                int exitCode;
                try {
                    exitCode = runBuild(svcDir);
                } catch (IOException | InterruptedException e) {
                    exitCode = -1;
                    r.buildDetail = e.getMessage();
                }
                r.buildPassed = (exitCode == 0);
                if (r.buildPassed) {
                    dash.onDone(buildLabel);
                } else {
                    if (r.buildDetail == null) r.buildDetail = "exit " + exitCode;
                    dash.onWarn(buildLabel, r.buildDetail);
                    printLogTail(svcDir, "smoketest-build.log", 20);
                    dash.onWarn(name + " · start + health", "skipped (build failed)");
                    if (endpoints) dash.onWarn(name + " · endpoints", "skipped (build failed)");
                    perServiceResults.add(r);
                    continue;
                }
            } else {
                r.buildPassed = true;
            }

            // ── Start + Health ─────────────────────────────────────────────
            String runLabel = name + " · start + health";
            dash.onStart(runLabel);
            int port = readPort(svcDir);
            Process proc = null;
            try {
                proc = spawnService(svcDir);
                boolean portOpen = awaitPort(port, startupTimeout, proc);
                if (!portOpen) {
                    r.startDetail = proc.isAlive()
                            ? "timeout waiting for :" + port
                            : "process exited (code " + proc.exitValue() + ") — see logs";
                    dash.onWarn(runLabel, r.startDetail);
                    printLogTail(svcDir, "smoketest-run.log", 20);
                    if (endpoints) dash.onWarn(name + " · endpoints", "skipped (start failed)");
                } else {
                    r.startPassed = true;
                    int httpStatus = checkHealth(port, healthPath);
                    r.httpStatus = httpStatus;
                    if (httpStatus > 0) {
                        r.healthPassed = true;
                        dash.onDone(runLabel);

                        // ── Endpoint Probe (while service is still running) ──
                        if (endpoints) {
                            String epLabel = name + " · endpoints";
                            dash.onStart(epLabel);
                            r.endpointSpecs = scanner.scan(svcDir);

                            for (EndpointSpec spec : r.endpointSpecs) {
                                String probedPath = PATH_VAR.matcher(spec.path()).replaceAll(PROBE_VALUE);
                                int status = probeEndpoint(port, spec.httpMethod(),
                                        probedPath, spec.hasRequestBody());
                                r.endpointProbes.add(new EndpointProbe(
                                        spec.httpMethod(), spec.path(), probedPath, status));
                            }

                            long epFailed = r.endpointProbes.stream()
                                    .filter(p -> !p.passed()).count();
                            if (r.endpointSpecs.isEmpty() || epFailed == 0) {
                                dash.onDone(epLabel);
                            } else {
                                dash.onWarn(epLabel, epFailed + "/" + r.endpointProbes.size()
                                        + " returned 5xx");
                            }
                        }
                    } else {
                        r.startDetail = "actuator unreachable after port opened";
                        dash.onWarn(runLabel, r.startDetail);
                        if (endpoints) dash.onWarn(name + " · endpoints", "skipped (unhealthy)");
                    }
                }
            } catch (IOException e) {
                r.startDetail = e.getMessage();
                dash.onWarn(runLabel, r.startDetail);
                if (endpoints) dash.onWarn(name + " · endpoints", "skipped (IOException)");
            } finally {
                killProcess(proc);
            }
            perServiceResults.add(r);
        }
    }

    // =========================================================================
    // Phase 2: Integration Tests
    // =========================================================================

    private void runIntegrationPhase(Dashboard dash, List<Path> allDirs, List<Path> businessDirs,
                                     Path registryDir, Path gatewayDir, Path sagaDir,
                                     boolean hasGateway, boolean hasSaga) throws Exception {

        integrationResult = new IntegrationResult();
        List<Process> tracked = new ArrayList<>();

        try {
            // ── Start registry first ───────────────────────────────────────
            dash.onStart("Integration · start services");
            int registryPort = readPort(registryDir);

            Process regProc = spawnService(registryDir);
            tracked.add(regProc);
            if (!awaitPort(registryPort, startupTimeout, regProc)) {
                integrationResult.errors.add("Registry failed to start on :" + registryPort);
                dash.onWarn("Integration · start services", "registry failed to start");
                dash.onWarn("Integration · registry discovery", "skipped");
                if (hasGateway) dash.onWarn("Integration · gateway routing", "skipped");
                if (hasSaga) dash.onWarn("Integration · saga endpoints", "skipped");
                dash.onWarn("Integration · shutdown", "cleaning up");
                return;
            }
            if (checkHealth(registryPort, healthPath) <= 0) {
                integrationResult.errors.add("Registry started but actuator unreachable");
                dash.onWarn("Integration · start services", "registry unhealthy");
                return;
            }
            integrationResult.registryStarted = true;

            // ── Start all business services ────────────────────────────────
            Map<String, Integer> servicePorts = new LinkedHashMap<>();
            for (Path svcDir : businessDirs) {
                String name = svcDir.getFileName().toString();
                int port = readPort(svcDir);
                Process proc = spawnService(svcDir);
                tracked.add(proc);
                servicePorts.put(name, port);
            }

            // Wait for all ports
            boolean allUp = true;
            for (var entry : servicePorts.entrySet()) {
                // Reuse a dummy process check — we just poll the port
                boolean portUp = awaitPortOnly(entry.getValue(), startupTimeout);
                if (!portUp) {
                    integrationResult.errors.add(entry.getKey() + " failed to start on :"
                            + entry.getValue());
                    allUp = false;
                }
            }

            if (!allUp) {
                dash.onWarn("Integration · start services",
                        integrationResult.errors.size() + " service(s) failed to start");
            } else {
                dash.onDone("Integration · start services");
            }

            // ── Registry discovery ─────────────────────────────────────────
            dash.onStart("Integration · registry discovery");
            // Give services a moment to register
            Thread.sleep(3_000);

            String registryBody = httpGetBody(registryPort, "/services");
            int discovered = 0;
            int expected   = 0;
            for (String name : servicePorts.keySet()) {
                expected++;
                boolean registered = registryBody != null && registryBody.contains(name);
                integrationResult.serviceRegistrations.put(name, registered);
                if (registered) discovered++;
                else integrationResult.errors.add("Service not registered: " + name);
            }

            if (discovered == expected) {
                dash.onDone("Integration · registry discovery");
            } else {
                dash.onWarn("Integration · registry discovery",
                        (expected - discovered) + "/" + expected + " not registered");
            }

            // ── Gateway routing ────────────────────────────────────────────
            if (hasGateway) {
                dash.onStart("Integration · gateway routing");
                int gwPort = readPort(gatewayDir);
                Process gwProc = spawnService(gatewayDir);
                tracked.add(gwProc);

                if (!awaitPort(gwPort, startupTimeout, gwProc)
                        || checkHealth(gwPort, healthPath) <= 0) {
                    integrationResult.errors.add("Gateway failed to start or unhealthy");
                    dash.onWarn("Integration · gateway routing", "gateway failed to start");
                } else {
                    integrationResult.gatewayStarted = true;

                    // Probe gateway actuator routes endpoint
                    int routeStatus = httpProbe(gwPort, "GET",
                            "/actuator/gateway/routes", false);
                    integrationResult.gatewayRoutesStatus = routeStatus;

                    // Probe each service through the gateway
                    int gwFails = 0;
                    for (String name : servicePorts.keySet()) {
                        // Try common route patterns
                        int status = httpProbe(gwPort, "GET",
                                "/api/" + name + "/actuator/health", false);
                        if (status <= 0) {
                            status = httpProbe(gwPort, "GET",
                                    "/" + name + "/actuator/health", false);
                        }
                        integrationResult.gatewayRoutes.put(name, status);
                        if (status <= 0 || status >= 500) gwFails++;
                    }

                    if (gwFails == 0 && routeStatus > 0 && routeStatus < 500) {
                        dash.onDone("Integration · gateway routing");
                    } else {
                        dash.onWarn("Integration · gateway routing",
                                gwFails + " route(s) unreachable");
                    }
                }
            }

            // ── Saga endpoints ─────────────────────────────────────────────
            if (hasSaga) {
                dash.onStart("Integration · saga endpoints");
                int sagaPort = readPort(sagaDir);
                boolean sagaRunning = servicePorts.containsKey("fractalx-saga-orchestrator")
                        || awaitPortOnly(sagaPort, 5);

                if (!sagaRunning) {
                    // Start it if not already among business services
                    Process sagaProc = spawnService(sagaDir);
                    tracked.add(sagaProc);
                    sagaRunning = awaitPort(sagaPort, startupTimeout, sagaProc)
                            && checkHealth(sagaPort, healthPath) > 0;
                }

                if (!sagaRunning) {
                    integrationResult.errors.add("Saga orchestrator failed to start");
                    dash.onWarn("Integration · saga endpoints", "orchestrator unreachable");
                } else {
                    integrationResult.sagaStarted = true;

                    // Probe saga endpoints
                    int listStatus = httpProbe(sagaPort, "GET", "/saga", false);
                    integrationResult.sagaListStatus = listStatus;

                    if (listStatus > 0 && listStatus < 500) {
                        dash.onDone("Integration · saga endpoints");
                    } else {
                        integrationResult.errors.add("Saga /saga returned HTTP " + listStatus);
                        dash.onWarn("Integration · saga endpoints", "HTTP " + listStatus);
                    }
                }
            }

        } finally {
            // ── Shutdown all ───────────────────────────────────────────────
            String shutdownLabel = "Integration · shutdown";
            dash.onStart(shutdownLabel);
            killAll(tracked);
            dash.onDone(shutdownLabel);
        }
    }

    // =========================================================================
    // Phase 3: Compose Tests
    // =========================================================================

    private void runComposePhase(Dashboard dash, Path composeFile, Path gatewayDir,
                                 boolean hasGateway) throws Exception {
        composeResult = new ComposeResult();
        String composeCmd = detectDockerCompose();

        if (composeCmd == null) {
            composeResult.errors.add("Docker Compose not found (tried 'docker compose' and 'docker-compose')");
            dash.onWarn("Compose · up", "docker compose not found");
            dash.onWarn("Compose · health check", "skipped");
            dash.onWarn("Compose · shutdown", "skipped");
            return;
        }

        Path composeDir = composeFile.getParent();

        try {
            // ── docker compose up ──────────────────────────────────────────
            dash.onStart("Compose · up");
            int upExit = runShellCommand(composeDir,
                    composeCmd.split(" ")[0],
                    composeArgs(composeCmd, composeFile, "up", "-d"));
            if (upExit != 0) {
                composeResult.errors.add("docker compose up failed with exit " + upExit);
                dash.onWarn("Compose · up", "exit " + upExit);
                dash.onWarn("Compose · health check", "skipped");
                return;
            }
            composeResult.composeUp = true;
            dash.onDone("Compose · up");

            // ── Health check ───────────────────────────────────────────────
            dash.onStart("Compose · health check");

            // Poll for container health (up to startupTimeout seconds)
            boolean healthy = false;
            if (hasGateway) {
                int gwPort = readPort(gatewayDir);
                healthy = awaitPortOnly(gwPort, startupTimeout)
                        && checkHealth(gwPort, healthPath) > 0;
                composeResult.gatewayHealthStatus = healthy
                        ? checkHealth(gwPort, healthPath) : -1;
            } else {
                // No gateway — just wait and check a known port
                Thread.sleep(10_000);
                healthy = true;
            }

            if (healthy) {
                dash.onDone("Compose · health check");
            } else {
                composeResult.errors.add("Containers not healthy within " + startupTimeout + "s");
                dash.onWarn("Compose · health check", "unhealthy after timeout");
            }

        } finally {
            // ── docker compose down ────────────────────────────────────────
            dash.onStart("Compose · shutdown");
            int downExit = runShellCommand(composeDir,
                    composeCmd.split(" ")[0],
                    composeArgs(composeCmd, composeFile, "down"));
            composeResult.composeDown = (downExit == 0);
            dash.onDone("Compose · shutdown");
        }
    }

    // =========================================================================
    // HTTP probing (shared)
    // =========================================================================

    /**
     * Sends an HTTP request using {@link HttpClient}. Supports all HTTP methods
     * including PATCH (which {@link HttpURLConnection} rejects).
     *
     * @return the HTTP status code, or {@code -1} if unreachable
     */
    private int probeEndpoint(int port, String method, String path, boolean hasBody) {
        return httpProbe(port, method, path, hasBody);
    }

    private int httpProbe(int port, String method, String path, boolean sendBody) {
        try {
            URI uri = new URI("http", null, "localhost", port, path, null, null);
            HttpRequest.Builder rb = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(5));

            boolean bodyMethod = method.equals("POST") || method.equals("PUT") || method.equals("PATCH");
            if (sendBody && bodyMethod) {
                rb.method(method, HttpRequest.BodyPublishers.ofString("{}"))
                  .header("Content-Type", "application/json");
            } else {
                rb.method(method, HttpRequest.BodyPublishers.noBody());
            }

            return httpClient.send(rb.build(), HttpResponse.BodyHandlers.discarding())
                             .statusCode();
        } catch (Exception e) {
            return -1;
        }
    }

    /** GET with response body — used for registry discovery. */
    private String httpGetBody(int port, String path) {
        try {
            URI uri = new URI("http", null, "localhost", port, path, null, null);
            HttpRequest req = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(5))
                    .GET().build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            return resp.statusCode() >= 200 && resp.statusCode() < 300 ? resp.body() : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** Simple health check via {@link HttpURLConnection} (matches SmokeTestMojo v1 behaviour). */
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
    // Process management
    // =========================================================================

    private int runBuild(Path svcDir) throws IOException, InterruptedException {
        Path log = svcDir.resolve("logs/smoketest-build.log");
        Files.createDirectories(log.getParent());
        return new ProcessBuilder(buildMvnCommand(svcDir, "package", "-DskipTests", "-q"))
                .directory(svcDir.toFile())
                .redirectOutput(log.toFile())
                .redirectError(ProcessBuilder.Redirect.appendTo(log.toFile()))
                .start()
                .waitFor();
    }

    private Process spawnService(Path svcDir) throws IOException {
        Path log = svcDir.resolve("logs/smoketest-run.log");
        Files.createDirectories(log.getParent());

        if (WINDOWS) {
            Path startBat = svcDir.resolve("start.bat");
            if (Files.exists(startBat))
                return new ProcessBuilder("cmd.exe", "/c", startBat.toAbsolutePath().toString())
                        .directory(svcDir.toFile())
                        .redirectOutput(log.toFile())
                        .redirectError(ProcessBuilder.Redirect.appendTo(log.toFile()))
                        .start();
        } else {
            Path startSh = svcDir.resolve("start.sh");
            if (Files.exists(startSh)) {
                startSh.toFile().setExecutable(true);
                return new ProcessBuilder("/bin/sh", startSh.toAbsolutePath().toString())
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

    /** Polls a port without a process reference — used when process is tracked separately. */
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

    /**
     * Kills a process and its entire process tree.
     *
     * <p>{@code mvn spring-boot:run} spawns the Spring Boot JVM as a child process.
     * {@link Process#destroy()} only signals the parent Maven process — the child JVM
     * keeps running and holds the port. We must walk the process tree and kill descendants
     * first to guarantee the port is released.
     */
    private void killProcess(Process proc) {
        if (proc == null || !proc.isAlive()) return;

        // Kill the entire process tree (children first, then parent)
        proc.descendants().forEach(child -> {
            child.destroy();
            try { child.onExit().get(3, TimeUnit.SECONDS); } catch (Exception ignored) {}
            if (child.isAlive()) child.destroyForcibly();
        });

        proc.destroy();
        try { proc.waitFor(5, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
        if (proc.isAlive()) proc.destroyForcibly();
    }

    private void killAll(List<Process> procs) {
        for (Process p : procs) killProcess(p);
    }

    /**
     * Waits until the given port is free (connection refused). Used between test phases
     * to ensure ports from Phase 1 are fully released before Phase 2 starts.
     */
    private boolean awaitPortFree(int port, int timeoutSec) throws InterruptedException {
        if (port <= 0) return true;
        long deadline = System.currentTimeMillis() + timeoutSec * 1000L;
        while (System.currentTimeMillis() < deadline) {
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress("localhost", port), 200);
                // Port is still in use — wait and retry
                Thread.sleep(500);
            } catch (IOException e) {
                // Connection refused → port is free
                return true;
            }
        }
        return false;
    }

    // =========================================================================
    // Docker Compose helpers
    // =========================================================================

    private String detectDockerCompose() {
        try {
            Process p = new ProcessBuilder("docker", "compose", "version")
                    .redirectErrorStream(true).start();
            if (p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0)
                return "docker compose";
        } catch (Exception ignored) {}
        try {
            Process p = new ProcessBuilder("docker-compose", "version")
                    .redirectErrorStream(true).start();
            if (p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0)
                return "docker-compose";
        } catch (Exception ignored) {}
        return null;
    }

    private String[] composeArgs(String composeCmd, Path composeFile, String... args) {
        List<String> cmd = new ArrayList<>();
        if (composeCmd.equals("docker compose")) {
            cmd.add("compose");
        }
        cmd.add("-f");
        cmd.add(composeFile.toAbsolutePath().toString());
        cmd.addAll(List.of(args));
        return cmd.toArray(String[]::new);
    }

    private int runShellCommand(Path workDir, String exe, String... args) throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>();
        cmd.add(exe);
        cmd.addAll(List.of(args));
        Path log = workDir.resolve("logs/smoketest-compose.log");
        Files.createDirectories(log.getParent());
        Process proc = new ProcessBuilder(cmd)
                .directory(workDir.toFile())
                .redirectOutput(log.toFile())
                .redirectError(ProcessBuilder.Redirect.appendTo(log.toFile()))
                .start();
        boolean finished = proc.waitFor(10, TimeUnit.MINUTES);
        if (!finished) {
            killProcess(proc);
            return -1;
        }
        return proc.exitValue();
    }

    // =========================================================================
    // Consolidated log (full report)
    // =========================================================================

    private void writeConsolidatedLog(Path root, List<Path> dirs, long t0) {
        Path log  = root.resolve("smoketest.log");
        String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        String div    = "═".repeat(70);
        String subdiv = "─".repeat(70);
        List<String> lines = new ArrayList<>();

        lines.add(div);
        lines.add("  FractalX Smoke Test  —  " + ts);
        lines.add(div);
        lines.add("");

        // ── Phase 1: Per-Service ───────────────────────────────────────────
        lines.add(div);
        lines.add("  PHASE 1: PER-SERVICE TESTS");
        lines.add(div);
        lines.add("");

        for (int i = 0; i < dirs.size() && i < perServiceResults.size(); i++) {
            Path        svcDir = dirs.get(i);
            SmokeResult r      = perServiceResults.get(i);
            String status = r.passed() ? "PASSED" : (r.buildPassed ? "START FAILED" : "BUILD FAILED");

            lines.add(subdiv);
            lines.add("  " + r.name + "  [" + status + "]");
            lines.add(subdiv);

            if (build) {
                lines.add("  BUILD: " + (r.buildPassed ? "PASSED" : "FAILED — " + r.buildDetail));
                appendFileLines(lines, svcDir.resolve("logs/smoketest-build.log"));
            }

            String startStatus = !r.buildPassed ? "SKIPPED"
                    : r.healthPassed ? "PASSED (HTTP " + r.httpStatus + ")"
                    : r.startPassed  ? "FAILED — " + r.startDetail
                    :                  "FAILED — " + (r.startDetail != null ? r.startDetail : "did not start");
            lines.add("  START + HEALTH: " + startStatus);

            if (endpoints && r.healthPassed) {
                lines.add("  ENDPOINTS: " + r.endpointSpecs.size() + " discovered, "
                        + r.endpointProbes.size() + " probed");
                for (EndpointProbe ep : r.endpointProbes) {
                    String st = ep.httpStatus < 0 ? "UNREACHABLE" : "HTTP " + ep.httpStatus;
                    lines.add("    [" + (ep.passed() ? "PASS" : "FAIL") + "]  "
                            + ep.method + " " + ep.probedPath + "  " + st);
                }
            }
            lines.add("");
        }

        // ── Phase 2: Integration ───────────────────────────────────────────
        if (integrationResult != null) {
            lines.add(div);
            lines.add("  PHASE 2: INTEGRATION TESTS");
            lines.add(div);
            lines.add("");

            lines.add("  Registry: " + (integrationResult.registryStarted ? "STARTED" : "FAILED"));

            if (!integrationResult.serviceRegistrations.isEmpty()) {
                lines.add("  Service Discovery:");
                for (var entry : integrationResult.serviceRegistrations.entrySet()) {
                    lines.add("    " + (entry.getValue() ? "[PASS]" : "[FAIL]") + "  " + entry.getKey());
                }
            }

            if (integrationResult.gatewayStarted) {
                lines.add("  Gateway Routes (HTTP status via /actuator/gateway/routes): "
                        + integrationResult.gatewayRoutesStatus);
                for (var entry : integrationResult.gatewayRoutes.entrySet()) {
                    int st = entry.getValue();
                    lines.add("    [" + (st > 0 && st < 500 ? "PASS" : "FAIL") + "]  "
                            + entry.getKey() + "  HTTP " + st);
                }
            }

            if (integrationResult.sagaStarted) {
                lines.add("  Saga Orchestrator: /saga → HTTP " + integrationResult.sagaListStatus);
            }

            if (!integrationResult.errors.isEmpty()) {
                lines.add("");
                lines.add("  Errors:");
                for (String err : integrationResult.errors) lines.add("    ✗ " + err);
            }
            lines.add("");
        }

        // ── Phase 3: Compose ───────────────────────────────────────────────
        if (composeResult != null) {
            lines.add(div);
            lines.add("  PHASE 3: COMPOSE TESTS");
            lines.add(div);
            lines.add("");
            lines.add("  docker compose up: " + (composeResult.composeUp ? "PASSED" : "FAILED"));
            lines.add("  Health check: " + (composeResult.gatewayHealthStatus > 0
                    ? "PASSED (HTTP " + composeResult.gatewayHealthStatus + ")"
                    : "FAILED"));
            lines.add("  docker compose down: " + (composeResult.composeDown ? "PASSED" : "FAILED"));
            if (!composeResult.errors.isEmpty()) {
                lines.add("  Errors:");
                for (String err : composeResult.errors) lines.add("    ✗ " + err);
            }
            lines.add("");
        }

        // ── Summary footer ─────────────────────────────────────────────────
        int totalFail = countTotalFailures();
        lines.add(div);
        lines.add("  " + (totalFail == 0 ? "ALL PHASES PASSED" : totalFail + " FAILURE(S)")
                + "  [" + fmt(System.currentTimeMillis() - t0) + "]");
        lines.add(div);

        try { Files.write(log, lines, StandardCharsets.UTF_8); }
        catch (IOException e) { warn("Could not write smoketest.log: " + e.getMessage()); }
    }

    // =========================================================================
    // Error log (failures only)
    // =========================================================================

    private void writeErrorLog(Path root, long t0) {
        Path log  = root.resolve("smoketest-errors.log");
        String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        String div    = "═".repeat(70);
        String subdiv = "─".repeat(70);
        List<String> lines = new ArrayList<>();

        lines.add(div);
        lines.add("  FractalX Smoke Test — Error Report  —  " + ts);
        lines.add(div);
        lines.add("");

        boolean hasErrors = false;

        // ── Per-Service errors ─────────────────────────────────────────────
        List<String> phase1Errors = new ArrayList<>();
        for (SmokeResult r : perServiceResults) {
            if (!r.buildPassed) {
                phase1Errors.add("  " + r.name + " · build");
                phase1Errors.add("    " + (r.buildDetail != null ? r.buildDetail : "unknown error"));
                phase1Errors.add("    See: " + r.name + "/logs/smoketest-build.log");
                phase1Errors.add("");
            } else if (!r.healthPassed) {
                phase1Errors.add("  " + r.name + " · start + health");
                phase1Errors.add("    " + (r.startDetail != null ? r.startDetail : "unknown error"));
                phase1Errors.add("    See: " + r.name + "/logs/smoketest-run.log");
                phase1Errors.add("");
            }
            for (EndpointProbe ep : r.endpointProbes) {
                if (!ep.passed()) {
                    phase1Errors.add("  " + r.name + " · endpoints");
                    String st = ep.httpStatus < 0 ? "unreachable" : "HTTP " + ep.httpStatus;
                    phase1Errors.add("    " + ep.method + " " + ep.path + "  →  " + st);
                    phase1Errors.add("");
                }
            }
        }
        if (!phase1Errors.isEmpty()) {
            hasErrors = true;
            lines.add(subdiv);
            lines.add("  Per-Service");
            lines.add(subdiv);
            lines.addAll(phase1Errors);
        }

        // ── Integration errors ─────────────────────────────────────────────
        if (integrationResult != null && !integrationResult.errors.isEmpty()) {
            hasErrors = true;
            lines.add(subdiv);
            lines.add("  Integration");
            lines.add(subdiv);
            for (String err : integrationResult.errors) {
                lines.add("    ✗ " + err);
            }
            // Include unregistered services
            for (var entry : integrationResult.serviceRegistrations.entrySet()) {
                if (!entry.getValue())
                    lines.add("    ✗ Not registered with registry: " + entry.getKey());
            }
            // Include failed gateway routes
            for (var entry : integrationResult.gatewayRoutes.entrySet()) {
                int st = entry.getValue();
                if (st <= 0 || st >= 500)
                    lines.add("    ✗ Gateway route failed: " + entry.getKey() + " → HTTP " + st);
            }
            lines.add("");
        }

        // ── Compose errors ─────────────────────────────────────────────────
        if (composeResult != null && !composeResult.errors.isEmpty()) {
            hasErrors = true;
            lines.add(subdiv);
            lines.add("  Compose");
            lines.add(subdiv);
            for (String err : composeResult.errors) {
                lines.add("    ✗ " + err);
            }
            lines.add("");
        }

        // ── No errors ─────────────────────────────────────────────────────
        if (!hasErrors) {
            lines.add("  No errors detected. All phases passed.");
            lines.add("");
        }

        // ── Footer ────────────────────────────────────────────────────────
        int totalFail = countTotalFailures();
        lines.add(div);
        lines.add("  " + (totalFail == 0 ? "CLEAN" : totalFail + " error(s)")
                + "  [" + fmt(System.currentTimeMillis() - t0) + "]");
        lines.add(div);

        try { Files.write(log, lines, StandardCharsets.UTF_8); }
        catch (IOException e) { warn("Could not write smoketest-errors.log: " + e.getMessage()); }
    }

    // =========================================================================
    // Summary (console)
    // =========================================================================

    private void printSummary(Path root, long t0) {
        int labelW = perServiceResults.stream()
                .mapToInt(r -> r.name.length()).max().orElse(12);

        // ── Phase 1 ────────────────────────────────────────────────────────
        section("Per-Service");
        for (SmokeResult r : perServiceResults) {
            boolean ok = r.passed();
            String icon   = ok ? a(GRN) + "\u2713" + a(RST) : a(YLW) + "\u26A0" + a(RST);
            String detail;

            if (!r.buildPassed) {
                detail = a(DIM) + "build failed" + a(RST);
            } else if (!r.healthPassed) {
                detail = a(DIM) + "start failed"
                        + (r.startDetail != null ? ": " + r.startDetail : "") + a(RST);
            } else {
                String base = "build + start + health"
                        + (r.httpStatus > 0 ? " (HTTP " + r.httpStatus + ")" : "");
                if (endpoints && !r.endpointProbes.isEmpty()) {
                    long epPass = r.endpointProbes.stream().filter(EndpointProbe::passed).count();
                    base += " · " + epPass + "/" + r.endpointProbes.size() + " endpoints";
                }
                detail = a(DIM) + base + a(RST);
            }
            out.println("  " + icon + "  " + pad(r.name, labelW) + "  " + detail);

            // List failing endpoints
            for (EndpointProbe ep : r.endpointProbes) {
                if (!ep.passed()) {
                    String st = ep.httpStatus < 0 ? "unreachable" : "HTTP " + ep.httpStatus;
                    out.println("  " + a(RED) + "    \u2022 " + a(RST)
                            + a(DIM) + ep.method + " " + ep.path + "  (" + st + ")" + a(RST));
                }
            }
        }
        out.println();

        // ── Phase 2 ────────────────────────────────────────────────────────
        if (integrationResult != null) {
            section("Integration");
            printIntegrationLine("Registry", integrationResult.registryStarted, null);
            for (var entry : integrationResult.serviceRegistrations.entrySet()) {
                printIntegrationLine("  " + entry.getKey(), entry.getValue(),
                        entry.getValue() ? null : "not registered");
            }
            if (integrationResult.gatewayStarted) {
                printIntegrationLine("Gateway", true, null);
                for (var entry : integrationResult.gatewayRoutes.entrySet()) {
                    int st = entry.getValue();
                    printIntegrationLine("  route " + entry.getKey(),
                            st > 0 && st < 500,
                            st <= 0 ? "unreachable" : st >= 500 ? "HTTP " + st : null);
                }
            }
            if (integrationResult.sagaStarted) {
                printIntegrationLine("Saga orchestrator",
                        integrationResult.sagaListStatus > 0 && integrationResult.sagaListStatus < 500,
                        null);
            }
            out.println();
        }

        // ── Phase 3 ────────────────────────────────────────────────────────
        if (composeResult != null) {
            section("Compose");
            printIntegrationLine("docker compose up", composeResult.composeUp, null);
            printIntegrationLine("Container health",
                    composeResult.gatewayHealthStatus > 0,
                    composeResult.gatewayHealthStatus <= 0 ? "unhealthy" : null);
            printIntegrationLine("docker compose down", composeResult.composeDown, null);
            out.println();
        }

        // ── Totals ─────────────────────────────────────────────────────────
        int totalFail = countTotalFailures();
        String passFail = totalFail == 0
                ? a(GRN) + "All phases passed" + a(RST)
                : a(YLW) + totalFail + " failure(s)" + a(RST);
        out.println("  " + passFail + "  " + a(DIM) + "["
                + fmt(System.currentTimeMillis() - t0) + "]" + a(RST));
        out.println();

        out.println("  " + a(DIM) + "Full report   " + root.resolve("smoketest.log") + a(RST));
        out.println("  " + a(DIM) + "Error report  " + root.resolve("smoketest-errors.log") + a(RST));
        out.println("  " + a(DIM) + "Service logs  <service>/logs/smoketest-{build,run}.log" + a(RST));
        out.println();
        cmd("mvn fractalx:smoke-test -Dfractalx.smoketest.build=false              # skip build");
        cmd("mvn fractalx:smoke-test -Dfractalx.smoketest.integration=true         # + integration");
        cmd("mvn fractalx:smoke-test -Dfractalx.smoketest.compose=true             # + compose");
        cmd("mvn fractalx:verify                                                    # static checks");
        out.println();
        done(System.currentTimeMillis() - t0);
    }

    private void printIntegrationLine(String label, boolean passed, String detail) {
        String icon = passed ? a(GRN) + "\u2713" + a(RST) : a(YLW) + "\u26A0" + a(RST);
        out.print("  " + icon + "  " + label);
        if (detail != null) out.print("  " + a(DIM) + detail + a(RST));
        out.println();
    }

    // =========================================================================
    // Failure counting
    // =========================================================================

    private int countTotalFailures() {
        int count = 0;
        // Per-service
        for (SmokeResult r : perServiceResults) {
            if (!r.passed()) count++;
            count += r.endpointProbes.stream().filter(p -> !p.passed()).count();
        }
        // Integration
        if (integrationResult != null) count += integrationResult.errors.size();
        // Compose
        if (composeResult != null) count += composeResult.errors.size();
        return count;
    }

    // =========================================================================
    // Utility
    // =========================================================================

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

    private void printLogTail(Path svcDir, String logFileName, int maxLines) {
        Path log = svcDir.resolve("logs/" + logFileName);
        if (!Files.exists(log)) return;
        try {
            List<String> lines = Files.readAllLines(log);
            int from = Math.max(0, lines.size() - maxLines);
            out.println();
            out.println("  " + a(YLW) + "\u26A0" + a(RST)
                    + "  " + a(BLD) + svcDir.getFileName() + a(RST)
                    + "  " + a(DIM) + log + a(RST));
            out.println();
            for (int i = from; i < lines.size(); i++)
                out.println("  " + a(DIM) + lines.get(i) + a(RST));
            out.println();
        } catch (IOException ignored) {}
    }

    private static void appendFileLines(List<String> dest, Path path) {
        if (!Files.exists(path)) return;
        try { dest.addAll(Files.readAllLines(path, StandardCharsets.UTF_8)); }
        catch (IOException ignored) {}
    }

    // ── Dashboard wrapper ─────────────────────────────────────────────────────

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
            throw new MojoExecutionException("Smoke test failed", e);
        }
        dash.finish();
        if (ansi) {
            try { Thread.sleep(500); } catch (InterruptedException ignored) {}
            out.print(ALT_OFF); out.flush();
        }
        out.println();
    }

    // =========================================================================
    // Result models
    // =========================================================================

    /** Per-service test result (Phase 1). */
    private static final class SmokeResult {
        final String name;
        boolean buildPassed  = false;
        String  buildDetail  = null;
        boolean startPassed  = false;
        boolean healthPassed = false;
        int     httpStatus   = -1;
        String  startDetail  = null;
        List<EndpointSpec>   endpointSpecs  = List.of();
        final List<EndpointProbe> endpointProbes = new ArrayList<>();

        SmokeResult(String name) { this.name = name; }

        boolean passed() { return buildPassed && startPassed && healthPassed; }
    }

    /** Single endpoint probe result. */
    private record EndpointProbe(String method, String path, String probedPath, int httpStatus) {
        boolean passed() {
            if (httpStatus <= 0) return false;
            // Fallback endpoints are circuit-breaker stubs that intentionally return 503 —
            // treat as passed since they are behaving correctly.
            if (path.startsWith("/fallback/") && httpStatus == 503) return true;
            return httpStatus < 500;
        }
    }

    /** Integration phase result (Phase 2). */
    private static final class IntegrationResult {
        boolean registryStarted = false;
        final Map<String, Boolean>  serviceRegistrations = new LinkedHashMap<>();
        boolean gatewayStarted  = false;
        int     gatewayRoutesStatus = -1;
        final Map<String, Integer>  gatewayRoutes = new LinkedHashMap<>();
        boolean sagaStarted     = false;
        int     sagaListStatus  = -1;
        final List<String> errors = new ArrayList<>();
    }

    /** Compose phase result (Phase 3). */
    private static final class ComposeResult {
        boolean composeUp            = false;
        int     gatewayHealthStatus  = -1;
        boolean composeDown          = false;
        final List<String> errors    = new ArrayList<>();
    }
}
