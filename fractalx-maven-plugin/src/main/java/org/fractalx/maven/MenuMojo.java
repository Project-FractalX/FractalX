package org.fractalx.maven;

import org.fractalx.core.FractalxVersion;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Scanner;

/**
 * Interactive CLI menu — shows the FRACTALX banner, lets the user navigate
 * commands with arrow keys, executes the selected one, then returns to ask
 * what's next. Start / stop / restart show a service sub-picker first.
 * Choose Exit (or press q / Ctrl-C) to leave.
 *
 * <pre>
 *   mvn fractalx:menu
 * </pre>
 */
@Mojo(name = "menu")
public class MenuMojo extends FractalxBaseMojo {

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    @Parameter(property = "fractalx.sourceDirectory",
               defaultValue = "${project.build.sourceDirectory}")
    private File sourceDirectory;

    @Parameter(property = "fractalx.outputDirectory",
               defaultValue = "${project.basedir}/microservices")
    private File outputDirectory;

    // ── Menu options (Exit rendered separately as the last item) ──────────────

    private static final String[] NAMES = {
        "decompose", "verify", "smoke-test", "diff-test", "endpoint-verify",
        "start", "stop", "restart", "ps", "services"
    };

    private static final String[] DESCS = {
        "Decompose monolith into microservices",
        "Verify decomposition output",
        "Build + start + health-check + CRUD lifecycle",
        "Diff monolith vs decomposed responses",
        "Static endpoint discovery + HTTP probing",
        "Start generated services",
        "Stop running services",
        "Restart services",
        "Show service process status",
        "List all generated services"
    };

    private static final int NAME_W =
            Arrays.stream(NAMES).mapToInt(String::length).max().orElse(8) + 3;

    /** Sentinel index meaning "Exit". */
    private static final int EXIT_IDX = NAMES.length;
    /** Total selectable rows = commands + Exit. */
    private static final int TOTAL    = NAMES.length + 1;

    // ── Execute ───────────────────────────────────────────────────────────────

    @Override
    public void execute() throws MojoExecutionException {
        initCli();

        if (!ansi || isWindows()) {
            runNumberedMenuLoop();
            return;
        }

        enableRawMode();
        Runtime.getRuntime().addShutdownHook(new Thread(MenuMojo::restoreTerminal));

        try (FileInputStream tty = new FileInputStream("/dev/tty")) {
            int selected = 0;

            outer:
            while (true) {

                // ── Main menu on alt screen ────────────────────────────────────
                out.print(ALT_ON);
                out.flush();
                drawMenu(selected);

                // ── Navigate ──────────────────────────────────────────────────
                inner:
                while (true) {
                    int b = tty.read();

                    if (b == 27) {
                        try { Thread.sleep(10); } catch (InterruptedException ignored) {}
                        if (tty.available() > 0) {
                            int b2 = tty.read();
                            if (b2 == '[' && tty.available() > 0) {
                                int b3 = tty.read();
                                if      (b3 == 'A') selected = (selected - 1 + TOTAL) % TOTAL;
                                else if (b3 == 'B') selected = (selected + 1) % TOTAL;
                            }
                        } else {
                            out.print(ALT_OFF); out.flush();
                            break outer;                    // bare ESC → exit menu
                        }
                    } else if (b == '\r' || b == '\n') {
                        break inner;                        // confirmed
                    } else if (b == 'q' || b == 3 || b == 4) {
                        out.print(ALT_OFF); out.flush();
                        break outer;
                    } else if (b == 'k') selected = (selected - 1 + TOTAL) % TOTAL;
                    else if  (b == 'j') selected = (selected + 1) % TOTAL;

                    drawMenu(selected);
                }

                // ── Exit chosen ────────────────────────────────────────────────
                if (selected == EXIT_IDX) {
                    out.print(ALT_OFF); out.flush();
                    break;
                }

                String commandName = NAMES[selected];

                // ── Service sub-picker for start / stop / restart ──────────────
                String service = "";
                if (isServiceCommand(commandName)) {
                    // Still on alt screen — draw the sub-picker over it.
                    String picked = pickService(tty, commandName);
                    if (picked == null) {
                        // User backed out → redraw main menu (continue outer re-enters ALT_ON)
                        continue outer;
                    }
                    service = picked;
                }

                // ── Exit alt screen, run command on main screen ────────────────
                out.print(ALT_OFF);
                out.flush();

                try {
                    invokeCommand(commandName, service);
                } catch (MojoExecutionException e) {
                    out.println();
                    warn("Command failed: " + e.getMessage());
                }

                // Sub-mojos may restore the terminal; re-enable raw mode for the
                // "press any key" read and the next menu loop.
                enableRawMode();

                out.println();
                out.print("  " + a(DIM) + "\u21B5  press any key to return to menu" + a(RST));
                out.flush();
                int k = tty.read();
                out.println();
                if (k == 'q' || k == 3 || k == 4) break;
            }

        } catch (IOException ignored) {
        } finally {
            restoreTerminal();
        }
    }

    // ── Main menu renderer ────────────────────────────────────────────────────

    private void drawMenu(int selected) {
        StringBuilder sb = new StringBuilder();
        sb.append(HOME);
        sb.append("\r\033[2K\r\n");

        for (int i = 0; i < BANNER.length; i++) {
            String color = i < BANNER_COLORS.length ? BANNER_COLORS[i] : "";
            sb.append("\r\033[2K").append(color).append(BANNER[i]).append(RST).append("\r\n");
        }

        sb.append("\r\033[2K\r\n");
        sb.append("\r\033[2K  ").append(DIM)
          .append("FractalX CLI  ").append(FractalxVersion.get()).append(RST).append("\r\n");
        sb.append("\r\033[2K\r\n");
        sb.append("\r\033[2K  ").append(DIM)
          .append("\u2191\u2193 navigate   Enter select   q quit").append(RST).append("\r\n");
        sb.append("\r\033[2K\r\n");

        for (int i = 0; i < NAMES.length; i++) {
            boolean sel    = (i == selected);
            String  cursor = sel ? GRN + "\u25B6" + RST : " ";
            String  name   = sel ? BLD + pad(NAMES[i], NAME_W) + RST
                                 : DIM + pad(NAMES[i], NAME_W) + RST;
            String  desc   = sel ? DESCS[i] : DIM + DESCS[i] + RST;
            sb.append("\r\033[2K  ").append(cursor).append("  ")
              .append(name).append("  ").append(desc).append("\r\n");
        }

        // Separator + Exit
        sb.append("\r\033[2K\r\n");
        boolean exitSel    = (selected == EXIT_IDX);
        String  exitCursor = exitSel ? RED + "\u25B6" + RST : " ";
        String  exitName   = exitSel ? RED + BLD + pad("exit", NAME_W) + RST
                                     : DIM + pad("exit", NAME_W) + RST;
        String  exitDesc   = exitSel ? RED + "Exit interactive CLI" + RST
                                     : DIM + "Exit interactive CLI" + RST;
        sb.append("\r\033[2K  ").append(exitCursor).append("  ")
          .append(exitName).append("  ").append(exitDesc).append("\r\n");

        sb.append(CLR_END);
        out.print(sb);
        out.flush();
    }

    // ── Service sub-picker (drawn on the already-active alt screen) ───────────

    private record SvcEntry(String name, boolean running) {}

    /**
     * Shows a service selector on the alt screen.
     *
     * <ul>
     *   <li>start   — all services shown; running ones are dim/non-selectable</li>
     *   <li>restart — only running services, no "All services" row</li>
     *   <li>stop    — only running services + "All services" row</li>
     * </ul>
     *
     * @return "" for "All services" (stop only), a service name, or null if backed out.
     */
    private String pickService(FileInputStream tty, String commandName) throws IOException {
        List<SvcEntry> allEntries = discoverServiceEntries();
        boolean isStart = commandName.equals("start");
        boolean isStop  = commandName.equals("stop");

        // start shows all (running=disabled); stop+restart show only running
        List<SvcEntry> entries = isStart
                ? allEntries
                : allEntries.stream().filter(SvcEntry::running).toList();

        // Build list of selectable positions: -1 = "All services", >=0 = index into entries
        List<Integer> selectable = new ArrayList<>();
        if (isStop) selectable.add(-1);
        for (int i = 0; i < entries.size(); i++) {
            if (!isStart || !entries.get(i).running()) selectable.add(i);
        }

        int optW = Math.max(
                entries.stream().mapToInt(e -> e.name().length()).max().orElse(8),
                isStop ? "All services".length() : 0) + 3;
        String title = Character.toUpperCase(commandName.charAt(0))
                     + commandName.substring(1) + " \u2014 select service";

        if (selectable.isEmpty()) {
            drawSubPickerEmpty(title, isStart ? "No stopped services to start"
                                              : "No running services");
            tty.read();
            return null;
        }

        int selIdx = 0;
        int total  = selectable.size();
        drawSubPicker(entries, selectable.get(selIdx), optW, title, isStop, isStart);

        while (true) {
            int b = tty.read();

            if (b == 27) {
                try { Thread.sleep(10); } catch (InterruptedException ignored) {}
                if (tty.available() > 0) {
                    int b2 = tty.read();
                    if (b2 == '[' && tty.available() > 0) {
                        int b3 = tty.read();
                        if      (b3 == 'A') selIdx = (selIdx - 1 + total) % total;
                        else if (b3 == 'B') selIdx = (selIdx + 1) % total;
                    }
                } else {
                    return null;
                }
            } else if (b == '\r' || b == '\n') {
                break;
            } else if (b == 'q' || b == 3 || b == 4) {
                return null;
            } else if (b == 'k') selIdx = (selIdx - 1 + total) % total;
            else if  (b == 'j') selIdx = (selIdx + 1) % total;

            drawSubPicker(entries, selectable.get(selIdx), optW, title, isStop, isStart);
        }

        int chosen = selectable.get(selIdx);
        return chosen == -1 ? "" : entries.get(chosen).name();
    }

    /**
     * @param selectedEntryIdx  index into entries currently highlighted, or -1 for "All services"
     * @param showAll           show the "All services" row (stop only)
     * @param showDisabled      render running services as non-selectable dim rows (start only)
     */
    private void drawSubPicker(List<SvcEntry> entries, int selectedEntryIdx, int optW,
                               String title, boolean showAll, boolean showDisabled) {
        final String DOT_UP   = GRN + "\u25CF" + RST;
        final String DOT_DOWN = DIM + "\u25CB" + RST;

        StringBuilder sb = new StringBuilder();
        sb.append(HOME).append("\r\033[2K\r\n");

        for (int i = 0; i < BANNER.length; i++) {
            String color = i < BANNER_COLORS.length ? BANNER_COLORS[i] : "";
            sb.append("\r\033[2K").append(color).append(BANNER[i]).append(RST).append("\r\n");
        }

        sb.append("\r\033[2K\r\n");
        sb.append("\r\033[2K  ").append(DIM)
          .append(title).append("  ").append(FractalxVersion.get()).append(RST).append("\r\n");
        sb.append("\r\033[2K\r\n");
        sb.append("\r\033[2K  ").append(DIM)
          .append("\u2191\u2193 navigate   Enter select   ESC back   ")
          .append(GRN).append("\u25CF").append(DIM).append(" running  ")
          .append("\u25CB").append(" stopped").append(RST).append("\r\n");
        sb.append("\r\033[2K\r\n");

        // "All services" row — stop only
        if (showAll) {
            boolean isSel = (selectedEntryIdx == -1);
            String cursor = isSel ? GRN + "\u25B6" + RST : " ";
            String name   = isSel ? BLD + pad("All services", optW) + RST
                                  : DIM + pad("All services", optW) + RST;
            sb.append("\r\033[2K  ").append(cursor).append("  ").append(name).append("\r\n");
        }

        for (int i = 0; i < entries.size(); i++) {
            SvcEntry e       = entries.get(i);
            boolean disabled = showDisabled && e.running();
            boolean isSel    = !disabled && (selectedEntryIdx == i);
            String dot       = e.running() ? DOT_UP : DOT_DOWN;
            String status    = e.running() ? GRN + "running" + RST : DIM + "stopped" + RST;
            String cursor, name;
            if (disabled) {
                cursor = DIM + "-" + RST;
                name   = DIM + pad(e.name(), optW) + RST;
            } else {
                cursor = isSel ? GRN + "\u25B6" + RST : " ";
                name   = isSel ? BLD + pad(e.name(), optW) + RST
                               : (e.running() ? pad(e.name(), optW)
                                              : DIM + pad(e.name(), optW) + RST);
            }
            sb.append("\r\033[2K  ").append(cursor).append("  ")
              .append(dot).append(" ").append(name).append("  ").append(status).append("\r\n");
        }

        sb.append(CLR_END);
        out.print(sb);
        out.flush();
    }

    private void drawSubPickerEmpty(String title, String message) {
        StringBuilder sb = new StringBuilder();
        sb.append(HOME).append("\r\033[2K\r\n");
        for (int i = 0; i < BANNER.length; i++) {
            String color = i < BANNER_COLORS.length ? BANNER_COLORS[i] : "";
            sb.append("\r\033[2K").append(color).append(BANNER[i]).append(RST).append("\r\n");
        }
        sb.append("\r\033[2K\r\n");
        sb.append("\r\033[2K  ").append(DIM).append(title).append("  ")
          .append(FractalxVersion.get()).append(RST).append("\r\n");
        sb.append("\r\033[2K\r\n");
        sb.append("\r\033[2K  ").append(YLW).append(message).append(RST).append("\r\n");
        sb.append("\r\033[2K\r\n");
        sb.append("\r\033[2K  ").append(DIM).append("Press any key to go back").append(RST).append("\r\n");
        sb.append(CLR_END);
        out.print(sb);
        out.flush();
    }

    // ── Numbered fallback ─────────────────────────────────────────────────────

    private void runNumberedMenuLoop() throws MojoExecutionException {
        try (Scanner sc = new Scanner(System.in)) {
            while (true) {
                printHeader("Interactive CLI");
                for (int i = 0; i < NAMES.length; i++) {
                    out.println("  [" + (i + 1) + "] " + pad(NAMES[i], NAME_W) + "  " + DESCS[i]);
                }
                out.println("  [0] exit              Exit interactive CLI");
                out.println();
                out.print("  Select (0 to exit): ");
                out.flush();

                if (!sc.hasNextInt()) break;
                int choice = sc.nextInt();
                if (choice == 0) break;
                if (choice < 1 || choice > NAMES.length) continue;

                String name    = NAMES[choice - 1];
                String service = "";

                if (isServiceCommand(name)) {
                    List<SvcEntry> allSvcs = discoverServiceEntries();
                    boolean isStart = name.equals("start");
                    boolean isStop  = name.equals("stop");
                    // start: only stopped services selectable; stop+restart: only running
                    List<SvcEntry> svcs = isStart
                            ? allSvcs.stream().filter(e -> !e.running()).toList()
                            : allSvcs.stream().filter(SvcEntry::running).toList();
                    out.println();
                    if (svcs.isEmpty()) {
                        out.println("  " + (isStart ? "No stopped services to start."
                                                    : "No running services."));
                        out.println();
                        continue;
                    }
                    if (isStop) out.println("  [0] All services");
                    for (int i = 0; i < svcs.size(); i++) {
                        String status = svcs.get(i).running() ? " [running]" : " [stopped]";
                        out.println("  [" + (i + 1) + "] " + svcs.get(i).name() + status);
                    }
                    out.println();
                    out.print(isStop ? "  Select service (0 for all): " : "  Select service: ");
                    out.flush();
                    if (sc.hasNextInt()) {
                        int s = sc.nextInt();
                        if (isStop && s == 0) {
                            service = "";   // All services
                        } else if (s >= 1 && s <= svcs.size()) {
                            service = svcs.get(s - 1).name();
                        }
                    }
                }

                try {
                    invokeCommand(name, service);
                } catch (MojoExecutionException e) {
                    warn("Command failed: " + e.getMessage());
                }
                out.println();
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static boolean isServiceCommand(String name) {
        return name.equals("start") || name.equals("stop") || name.equals("restart");
    }

    private List<SvcEntry> discoverServiceEntries() {
        if (outputDirectory == null || !outputDirectory.exists()) return List.of();
        List<SvcEntry> result = new ArrayList<>();
        try (var stream = Files.list(outputDirectory.toPath())) {
            stream.filter(Files::isDirectory)
                  .filter(d -> Files.exists(d.resolve("pom.xml")))
                  .sorted()
                  .forEach(d -> {
                      String name = d.getFileName().toString();
                      int port = readPortFromDir(d);
                      boolean running = port > 0 && isPortOpen(port);
                      result.add(new SvcEntry(name, running));
                  });
        } catch (IOException ignored) {}
        return result;
    }

    private int readPortFromDir(Path svcDir) {
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

    private boolean isPortOpen(int port) {
        try (Socket s = new Socket("localhost", port)) {
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    // ── Raw-mode helpers ──────────────────────────────────────────────────────

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    private static boolean enableRawMode() {
        try {
            new ProcessBuilder("sh", "-c", "stty -echo -icanon min 1 < /dev/tty")
                    .redirectErrorStream(true).start().waitFor();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static void restoreTerminal() {
        try {
            new ProcessBuilder("sh", "-c", "stty echo icanon < /dev/tty")
                    .redirectErrorStream(true).start().waitFor();
        } catch (Exception ignored) {}
    }

    // ── Mojo dispatcher ───────────────────────────────────────────────────────

    private void invokeCommand(String name, String service) throws MojoExecutionException {
        FractalxBaseMojo mojo = switch (name) {
            case "decompose"       -> new DecomposeMojo();
            case "verify"          -> new VerifyMojo();
            case "smoke-test"      -> new SmokeTestMojo();
            case "diff-test"       -> new DiffTestMojo();
            case "endpoint-verify" -> new EndpointVerifyMojo();
            case "start"           -> new StartMojo();
            case "stop"            -> new StopMojo();
            case "restart"         -> new RestartMojo();
            case "ps"              -> new PsMojo();
            case "services"        -> new ServicesMojo();
            default -> throw new MojoExecutionException("Unknown command: " + name);
        };

        inject(mojo, "colorParam",         true);
        inject(mojo, "project",            project);
        inject(mojo, "outputDirectory",    outputDirectory);
        inject(mojo, "sourceDirectory",    sourceDirectory);
        inject(mojo, "service",            service);        // "" = all, name = specific
        inject(mojo, "monolithDirectory",  sourceDirectory); // diff-test uses monolith root
        try {
            mojo.execute();
        } catch (MojoFailureException e) {
            throw new MojoExecutionException("Command failed: " + name, e);
        }
    }

    private static void inject(Object target, String fieldName, Object value) {
        if (value == null) return;
        Class<?> cls = target.getClass();
        while (cls != null && cls != Object.class) {
            try {
                Field f = cls.getDeclaredField(fieldName);
                f.setAccessible(true);
                f.set(target, value);
                return;
            } catch (NoSuchFieldException e) {
                cls = cls.getSuperclass();
            } catch (Exception e) {
                break;
            }
        }
    }
}
