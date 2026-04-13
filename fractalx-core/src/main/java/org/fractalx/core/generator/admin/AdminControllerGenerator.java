package org.fractalx.core.generator.admin;

import org.fractalx.core.model.FractalModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Generates the dashboard REST controller for the admin service. */
class AdminControllerGenerator {

    private static final Logger log = LoggerFactory.getLogger(AdminControllerGenerator.class);

    void generate(Path srcMainJava, String basePackage, List<FractalModule> modules) throws IOException {
        Path packagePath = AdminPackageUtil.createPackagePath(srcMainJava, basePackage + ".controller");

        String content = """
                package org.fractalx.admin.controller;

                import org.fractalx.admin.model.ServiceInfo;
                import org.springframework.beans.factory.annotation.Value;
                import org.springframework.http.ResponseEntity;
                import org.springframework.stereotype.Controller;
                import org.springframework.ui.Model;
                import org.springframework.web.bind.annotation.GetMapping;
                import org.springframework.web.bind.annotation.RestController;
                import org.springframework.web.client.RestTemplate;

                import java.util.ArrayList;
                import java.util.List;
                import java.util.Map;
                import java.util.LinkedHashMap;

                @Controller
                public class DashboardController {

                    private final RestTemplate restTemplate = new RestTemplate();

                    @Value("${fractalx.registry.url:http://localhost:8761}")
                    private String registryUrl;

                    @GetMapping("/dashboard")
                    public String dashboard(Model model) {
                        List<ServiceInfo> services = getServiceStatuses();
                        model.addAttribute("services", services);
                        model.addAttribute("totalServices", services.size());
                        model.addAttribute("runningServices",
                                services.stream().filter(ServiceInfo::isRunning).count());
                        return "dashboard";
                    }

                    private List<ServiceInfo> getServiceStatuses() {
                        List<ServiceInfo> services = new ArrayList<>();
                        %s
                        return services;
                    }

                    @SuppressWarnings("unchecked")
                    private boolean checkServiceHealth(String url) {
                        try {
                            java.util.Map<String, Object> body =
                                    restTemplate.getForObject(url, java.util.Map.class);
                            return body != null && "UP".equalsIgnoreCase(String.valueOf(body.get("status")));
                        } catch (Exception e) {
                            return false;
                        }
                    }
                }
                """.formatted(buildServiceChecks(modules));

        Files.writeString(packagePath.resolve("DashboardController.java"), content);
        log.debug("Generated DashboardController");
    }

    private String buildServiceChecks(List<FractalModule> modules) {
        StringBuilder sb = new StringBuilder();
        for (FractalModule module : modules) {
            sb.append(String.format(
                    "services.add(new ServiceInfo(\"%s\", \"http://localhost:%d\","
                    + " checkServiceHealth(\"http://localhost:%d/actuator/health\")));%n",
                    module.getServiceName(), module.getPort(), module.getPort()
            ));
        }
        return sb.toString();
    }
}
