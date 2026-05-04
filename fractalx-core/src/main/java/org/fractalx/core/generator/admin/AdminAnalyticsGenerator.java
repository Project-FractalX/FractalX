package org.fractalx.core.generator.admin;

import org.fractalx.core.model.FractalModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Generates the Analytics subsystem for the admin service.
 *
 * <p>Produces 3 classes in {@code org.fractalx.admin.analytics}:
 * <ol>
 *   <li>{@code MetricsHistoryStore}  — thread-safe circular buffer (60 pts × service)</li>
 *   <li>{@code MetricsCollector}     — @Scheduled poller of /actuator/metrics endpoints</li>
 *   <li>{@code AnalyticsController}  — REST API: /api/analytics/**</li>
 * </ol>
 *
 * <p>Metrics collected per service (every 15 s):
 * <ul>
 *   <li>process.cpu.usage         — CPU % (0-100)</li>
 *   <li>jvm.memory.used (heap)    — heap used MB</li>
 *   <li>jvm.memory.max  (heap)    — heap max MB</li>
 *   <li>jvm.threads.live          — live thread count</li>
 *   <li>http.server.requests      — delta RPS + P99 response time</li>
 * </ul>
 */
class AdminAnalyticsGenerator {

    private static final Logger log = LoggerFactory.getLogger(AdminAnalyticsGenerator.class);

    void generate(Path srcMainJava, String basePackage, List<FractalModule> modules) throws IOException {
        Path pkg = AdminPackageUtil.createPackagePath(srcMainJava, basePackage + ".analytics");
        generateMetricsHistoryStore(pkg);
        generateMetricsCollector(pkg);
        generateAnalyticsController(pkg);
        log.debug("Generated admin analytics subsystem ({} modules)", modules.size());
    }

    // -------------------------------------------------------------------------

    private void generateMetricsHistoryStore(Path pkg) throws IOException {
        Files.writeString(pkg.resolve("MetricsHistoryStore.java"), """
                package org.fractalx.admin.analytics;

                import org.springframework.stereotype.Component;
                import java.time.Instant;
                import java.util.*;
                import java.util.concurrent.ConcurrentHashMap;

                /**
                 * Thread-safe circular buffer: up to 60 metric snapshots per service.
                 * At 15-second collection intervals this covers ~15 minutes of history.
                 */
                @Component
                public class MetricsHistoryStore {

                    public record MetricsSnapshot(
                            Instant timestamp,
                            double  cpuPct,
                            double  heapUsedMb,
                            double  heapMaxMb,
                            long    threads,
                            double  rps,
                            double  errorRatePct,
                            double  p99Ms
                    ) {}

                    private static final int MAX_POINTS = 60;
                    private final ConcurrentHashMap<String, ArrayDeque<MetricsSnapshot>> history =
                            new ConcurrentHashMap<>();

                    public void record(String service, MetricsSnapshot snapshot) {
                        history.compute(service, (k, deque) -> {
                            if (deque == null) deque = new ArrayDeque<>(MAX_POINTS + 1);
                            deque.addLast(snapshot);
                            if (deque.size() > MAX_POINTS) deque.pollFirst();
                            return deque;
                        });
                    }

                    public List<MetricsSnapshot> getHistory(String service) {
                        ArrayDeque<MetricsSnapshot> deque = history.get(service);
                        return deque == null ? List.of() : new ArrayList<>(deque);
                    }

                    public MetricsSnapshot getLatest(String service) {
                        ArrayDeque<MetricsSnapshot> deque = history.get(service);
                        return (deque == null || deque.isEmpty()) ? null : deque.peekLast();
                    }

                    public Map<String, MetricsSnapshot> getLatestAll() {
                        Map<String, MetricsSnapshot> out = new LinkedHashMap<>();
                        history.forEach((svc, deque) -> {
                            if (!deque.isEmpty()) out.put(svc, deque.peekLast());
                        });
                        return out;
                    }

                    public Set<String> getTrackedServices() { return history.keySet(); }
                }
                """);
    }

    private void generateMetricsCollector(Path pkg) throws IOException {
        Files.writeString(pkg.resolve("MetricsCollector.java"), """
                package org.fractalx.admin.analytics;

                import org.fractalx.admin.services.ServiceMetaRegistry;
                import org.slf4j.Logger;
                import org.slf4j.LoggerFactory;
                import org.springframework.beans.factory.annotation.Value;
                import org.springframework.http.client.SimpleClientHttpRequestFactory;
                import org.springframework.scheduling.annotation.Scheduled;
                import org.springframework.stereotype.Component;
                import org.springframework.web.client.RestTemplate;

                import java.time.Instant;
                import java.util.List;
                import java.util.Map;
                import java.util.concurrent.ConcurrentHashMap;

                /**
                 * Polls each service's /actuator/metrics endpoints every 15 seconds and records
                 * snapshots in {@link MetricsHistoryStore}.
                 */
                @Component
                public class MetricsCollector {

                    private static final Logger log = LoggerFactory.getLogger(MetricsCollector.class);
                    private static final int    INTERVAL_S = 15;

                    private final MetricsHistoryStore                    store;
                    private final ServiceMetaRegistry                    registry;
                    private final RestTemplate                           rest;
                    private final ConcurrentHashMap<String, double[]>    prevCounts = new ConcurrentHashMap<>();

                    @Value("${fractalx.registry.url:http://localhost:8761}")
                    private String registryUrl;

                    public MetricsCollector(MetricsHistoryStore store, ServiceMetaRegistry registry) {
                        this.store    = store;
                        this.registry = registry;
                        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
                        factory.setConnectTimeout(2_000);
                        factory.setReadTimeout(3_000);
                        this.rest = new RestTemplate(factory);
                    }

                    @Scheduled(fixedDelay = INTERVAL_S * 1000L)
                    public void collect() {
                        for (ServiceMetaRegistry.ServiceMeta svc : registry.getAll()) {
                            if (svc.port() <= 0) continue;
                            String base = resolveBaseUrl(svc.name());
                            if (base == null) continue;
                            try {
                                store.record(svc.name(), collectService(svc.name(), base));
                            } catch (Exception e) {
                                log.debug("Metrics unavailable for {}: {}", svc.name(), e.getMessage());
                            }
                        }
                    }

                    /**
                     * Resolves a service's live base URL ({@code http://host:port}) by looking
                     * up its registration in the FractalX Registry.
                     */
                    @SuppressWarnings("unchecked")
                    private String resolveBaseUrl(String serviceName) {
                        try {
                            Map<String, Object> reg = rest.getForObject(
                                    registryUrl + "/services/" + serviceName, Map.class);
                            if (reg == null) return null;
                            Object host = reg.get("host");
                            Object port = reg.get("port");
                            if (host == null || !(port instanceof Number)) return null;
                            return "http://" + host + ":" + ((Number) port).intValue();
                        } catch (Exception e) {
                            return null;
                        }
                    }

                    private MetricsHistoryStore.MetricsSnapshot collectService(String name, String base) {
                        double cpu      = fetchGauge(base, "process.cpu.usage") * 100.0;
                        double heapUsed = fetchGauge(base, "jvm.memory.used?tag=area:heap") / (1024.0 * 1024.0);
                        double heapMax  = fetchGauge(base, "jvm.memory.max?tag=area:heap")  / (1024.0 * 1024.0);
                        long   threads  = (long) fetchGauge(base, "jvm.threads.live");
                        double p99s     = fetchStat(base, "http.server.requests", "MAX");
                        double totalReq = fetchStat(base, "http.server.requests", "COUNT");
                        double errReq   = fetchStat(base, "http.server.requests?tag=status:5xx", "COUNT");
                        double[] prev   = prevCounts.getOrDefault(name, new double[]{totalReq, errReq});
                        double dTotal   = Math.max(0, totalReq - prev[0]);
                        double dErr     = Math.max(0, errReq   - prev[1]);
                        prevCounts.put(name, new double[]{totalReq, errReq});
                        double rps       = dTotal / INTERVAL_S;
                        double errorRate = dTotal > 0 ? dErr / dTotal * 100.0 : 0.0;
                        return new MetricsHistoryStore.MetricsSnapshot(
                                Instant.now(), cpu, heapUsed, heapMax, threads, rps, errorRate, p99s * 1000.0);
                    }

                    @SuppressWarnings("unchecked")
                    private double fetchGauge(String base, String path) {
                        try {
                            Map<String, Object> body = rest.getForObject(base + "/actuator/metrics/" + path, Map.class);
                            if (body == null) return 0.0;
                            List<Map<String, Object>> m = (List<Map<String, Object>>) body.get("measurements");
                            if (m == null || m.isEmpty()) return 0.0;
                            Object v = m.get(0).get("value");
                            return v instanceof Number n ? n.doubleValue() : 0.0;
                        } catch (Exception e) { return 0.0; }
                    }

                    @SuppressWarnings("unchecked")
                    private double fetchStat(String base, String path, String statistic) {
                        try {
                            Map<String, Object> body = rest.getForObject(base + "/actuator/metrics/" + path, Map.class);
                            if (body == null) return 0.0;
                            List<Map<String, Object>> ms = (List<Map<String, Object>>) body.get("measurements");
                            if (ms == null) return 0.0;
                            for (Map<String, Object> m : ms)
                                if (statistic.equals(m.get("statistic"))) {
                                    Object v = m.get("value");
                                    return v instanceof Number n ? n.doubleValue() : 0.0;
                                }
                            return 0.0;
                        } catch (Exception e) { return 0.0; }
                    }
                }
                """);
    }

    private void generateAnalyticsController(Path pkg) throws IOException {
        Files.writeString(pkg.resolve("AnalyticsController.java"), """
                package org.fractalx.admin.analytics;

                import org.fractalx.admin.services.ServiceMetaRegistry;
                import org.springframework.http.MediaType;
                import org.springframework.web.bind.annotation.*;
                import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

                import java.time.ZoneOffset;
                import java.time.format.DateTimeFormatter;
                import java.util.*;
                import java.util.concurrent.CopyOnWriteArrayList;
                import java.util.concurrent.Executors;
                import java.util.concurrent.ScheduledExecutorService;
                import java.util.concurrent.TimeUnit;

                /**
                 * Analytics REST API for the admin dashboard.
                 *
                 * <ul>
                 *   <li>GET /api/analytics/overview          — aggregate snapshot (rps, cpu, errors, p99)</li>
                 *   <li>GET /api/analytics/realtime          — per-service current metrics</li>
                 *   <li>GET /api/analytics/history/{service} — 60-point time-series for one service</li>
                 *   <li>GET /api/analytics/trends            — multi-service RPS + CPU time-series</li>
                 *   <li>GET /api/analytics/stream            — SSE: live realtime snapshot every 5 s</li>
                 * </ul>
                 */
                @RestController
                @RequestMapping("/api/analytics")
                public class AnalyticsController {

                    private static final DateTimeFormatter FMT =
                            DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneOffset.UTC);

                    private final MetricsHistoryStore      store;
                    private final ServiceMetaRegistry      registry;
                    private final List<SseEmitter>         emitters = new CopyOnWriteArrayList<>();
                    private final ScheduledExecutorService pusher   =
                            Executors.newSingleThreadScheduledExecutor(r -> {
                                Thread t = new Thread(r, "analytics-sse-pusher");
                                t.setDaemon(true);
                                return t;
                            });

                    public AnalyticsController(MetricsHistoryStore store, ServiceMetaRegistry registry) {
                        this.store    = store;
                        this.registry = registry;
                        pusher.scheduleAtFixedRate(this::pushLive, 5, 5, TimeUnit.SECONDS);
                    }

                    @GetMapping("/overview")
                    public Map<String, Object> overview() {
                        Map<String, MetricsHistoryStore.MetricsSnapshot> latest = store.getLatestAll();
                        double totalRps = latest.values().stream().mapToDouble(MetricsHistoryStore.MetricsSnapshot::rps).sum();
                        double avgCpu   = latest.values().stream().mapToDouble(MetricsHistoryStore.MetricsSnapshot::cpuPct).average().orElse(0);
                        double avgErr   = latest.values().stream().mapToDouble(MetricsHistoryStore.MetricsSnapshot::errorRatePct).average().orElse(0);
                        double avgP99   = latest.values().stream().mapToDouble(MetricsHistoryStore.MetricsSnapshot::p99Ms).average().orElse(0);
                        return Map.of(
                            "totalRps",        r2(totalRps),
                            "avgCpuPct",       r1(avgCpu),
                            "avgErrorRatePct", r1(avgErr),
                            "avgP99Ms",        (long) avgP99,
                            "trackedServices", latest.size()
                        );
                    }

                    @GetMapping("/realtime")
                    public Map<String, Object> realtime() {
                        Map<String, Object> out = new LinkedHashMap<>();
                        store.getLatestAll().forEach((svc, s) -> {
                            long heapPct = s.heapMaxMb() > 0 ? Math.round(s.heapUsedMb() / s.heapMaxMb() * 100) : 0;
                            out.put(svc, Map.of(
                                "cpu",       r1(s.cpuPct()),
                                "heapUsed",  (long) s.heapUsedMb(),
                                "heapMax",   (long) s.heapMaxMb(),
                                "heapPct",   heapPct,
                                "threads",   s.threads(),
                                "rps",       r2(s.rps()),
                                "errorRate", r1(s.errorRatePct()),
                                "p99Ms",     (long) s.p99Ms(),
                                "ts",        FMT.format(s.timestamp())
                            ));
                        });
                        return out;
                    }

                    @GetMapping("/history/{service}")
                    public Map<String, Object> history(@PathVariable String service) {
                        List<MetricsHistoryStore.MetricsSnapshot> snaps = store.getHistory(service);
                        List<String> labels = new ArrayList<>();
                        List<Double> cpu    = new ArrayList<>();
                        List<Double> heap   = new ArrayList<>();
                        List<Double> rps    = new ArrayList<>();
                        List<Double> errors = new ArrayList<>();
                        List<Double> p99    = new ArrayList<>();
                        for (MetricsHistoryStore.MetricsSnapshot s : snaps) {
                            labels.add(FMT.format(s.timestamp()));
                            cpu.add(r1(s.cpuPct()));
                            heap.add(s.heapMaxMb() > 0 ? r1(s.heapUsedMb() / s.heapMaxMb() * 100.0) : 0.0);
                            rps.add(r2(s.rps()));
                            errors.add(r1(s.errorRatePct()));
                            p99.add(r1(s.p99Ms()));
                        }
                        return Map.of("service", service, "labels", labels,
                                "cpu", cpu, "heapPct", heap, "rps", rps, "errorRate", errors, "p99Ms", p99);
                    }

                    @GetMapping("/trends")
                    public Map<String, Object> trends() {
                        Map<String, List<MetricsHistoryStore.MetricsSnapshot>> all = new LinkedHashMap<>();
                        for (String svc : store.getTrackedServices()) all.put(svc, store.getHistory(svc));
                        if (all.isEmpty()) return Map.of("labels", List.of(), "datasets", List.of());
                        String ref = all.entrySet().stream()
                                .max(Comparator.comparingInt(e -> e.getValue().size()))
                                .map(Map.Entry::getKey).orElse("");
                        List<String> labels = all.getOrDefault(ref, List.of()).stream()
                                .map(s -> FMT.format(s.timestamp())).toList();
                        List<Map<String, Object>> datasets = new ArrayList<>();
                        all.forEach((svc, snaps) -> datasets.add(Map.of(
                                "service", svc,
                                "rps",     snaps.stream().map(s -> r2(s.rps())).toList(),
                                "cpu",     snaps.stream().map(s -> r1(s.cpuPct())).toList()
                        )));
                        return Map.of("labels", labels, "datasets", datasets);
                    }

                    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
                    public SseEmitter stream() {
                        SseEmitter e = new SseEmitter(300_000L);
                        emitters.add(e);
                        e.onCompletion(() -> emitters.remove(e));
                        e.onTimeout(()    -> emitters.remove(e));
                        return e;
                    }

                    private void pushLive() {
                        if (emitters.isEmpty()) return;
                        Object data = realtime();
                        List<SseEmitter> dead = new ArrayList<>();
                        for (SseEmitter e : emitters) {
                            try { e.send(SseEmitter.event().name("metrics").data(data)); }
                            catch (Exception ex) { dead.add(e); }
                        }
                        emitters.removeAll(dead);
                    }

                    private static double r1(double v) { return Math.round(v * 10.0)   / 10.0; }
                    private static double r2(double v) { return Math.round(v * 100.0)  / 100.0; }
                }
                """);
    }
}
