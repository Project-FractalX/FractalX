package org.fractalx.core.datamanagement;

import org.fractalx.core.model.FractalModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Generates a service-specific DATA_README.md file.
 * Documents the database strategy, decoupling, and dependencies for a SINGLE service.
 */
public class DataReadmeGenerator {

    private static final Logger log = LoggerFactory.getLogger(DataReadmeGenerator.class);

    public void generateServiceDataReadme(FractalModule module, Path serviceRoot,
                                          String driverClassName) {
        log.info("   📝 Generating Data README for '{}'...", module.getServiceName());

        StringBuilder md = new StringBuilder();

        String dbType = detectDbType(driverClassName);
        String dbUrlDisplay = detectDbUrlPattern(module, dbType);

        // ── Header ────────────────────────────────────────────────────────────
        md.append("# 🗄️ Data Architecture: ").append(module.getServiceName()).append("\n\n");
        md.append("This service has been upgraded by FractalX to support **Distributed Data Isolation**.\n\n");

        // ── 1. Database Configuration ─────────────────────────────────────────
        md.append("## 1. Database Configuration\n");
        md.append("This service follows the **Database-per-Service** pattern.\n\n");
        md.append("| Property | Value |\n");
        md.append("| :--- | :--- |\n");
        md.append("| **Database Type** | ").append(dbType).append(" |\n");
        md.append("| **Connection URL** | `").append(dbUrlDisplay).append("` |\n");
        md.append("| **Schema Strategy** | `ddl-auto: update` (Hibernate managed) |\n\n");

        if (dbType.contains("H2")) {
            md.append("> **⚠️ Note:** This service uses an **In-Memory H2 Database** (Default).\n");
            md.append("> - Data is lost when the service stops.\n");
            md.append("> - To use MySQL/Postgres, see the **Configuration Guide** below.\n\n");
        } else {
            md.append("> **✅ Production Mode:** This service is configured to use an external database (")
              .append(dbType).append(").\n\n");
        }

        // ── 2. Decoupling Strategy ────────────────────────────────────────────
        md.append("## 2. Decoupling Strategy\n");
        md.append("Cross-service relationships have been transformed at the Java level to remove hard Foreign Key constraints.\n\n");
        md.append("- **Local Entities**: Relationships inside this service (e.g., `@OneToMany`) are preserved. Foreign Keys exist.\n");
        md.append("- **Remote Entities**: Relationships to other services were converted to IDs (e.g., `Customer customer` → `String customerId`).\n\n");

        // ── 3. Injected Dependencies ──────────────────────────────────────────
        md.append("## 3. Injected Dependencies\n");
        md.append("FractalX automatically injected the following driver into `pom.xml`:\n");
        md.append("- **Driver Class**: `").append(driverClassName != null ? driverClassName : "org.h2.Driver").append("`\n\n");

        // ── 4. How to Verify ──────────────────────────────────────────────────
        md.append("## 4. How to Verify\n");
        md.append("1. Start the service: `mvn spring-boot:run`\n");
        md.append("2. Check logs for `Hibernate: create table ...`\n");
        if (dbType.contains("H2")) {
            md.append("3. Access H2 Console: `http://localhost:").append(module.getPort()).append("/h2-console`\n");
            md.append("   - JDBC URL: `").append(dbUrlDisplay).append("`\n");
        }
        md.append("\n");

        // ── 5. Configuration Guide ────────────────────────────────────────────
        md.append("## 5. Configuration Guide\n");
        md.append("To switch this service to a physical database (MySQL/PostgreSQL), add the following block to your **Monolith's** `application.yml` before decomposition:\n\n");
        md.append("```yaml\n");
        md.append("fractalx:\n");
        md.append("  modules:\n");
        md.append("    # Must match the service name exactly\n");
        md.append("    ").append(module.getServiceName()).append(":\n");
        md.append("      datasource:\n");
        md.append("        url: jdbc:mysql://localhost:3306/").append(module.getServiceName().replace("-", "_")).append("_db\n");
        md.append("        username: root\n");
        md.append("        password: <your_password>\n");
        md.append("        driver-class-name: com.mysql.cj.jdbc.Driver\n");
        md.append("      jpa:\n");
        md.append("        hibernate:\n");
        md.append("          ddl-auto: update\n");
        md.append("```\n");

        // Write file inside the SERVICE ROOT
        try {
            Files.writeString(serviceRoot.resolve("DATA_README.md"), md.toString());
        } catch (IOException e) {
            log.error("Failed to generate Data README for " + module.getServiceName(), e);
        }
    }

    private String detectDbType(String driver) {
        if (driver == null) return "H2 (Default)";
        if (driver.contains("mysql")) return "MySQL";
        if (driver.contains("postgresql")) return "PostgreSQL";
        if (driver.contains("h2")) return "H2 (In-Memory)";
        return "Custom SQL (" + driver + ")";
    }

    private String detectDbUrlPattern(FractalModule module, String dbType) {
        if (dbType.contains("H2")) {
            return "jdbc:h2:mem:" + module.getServiceName().replace("-", "_");
        }
        return "See src/main/resources/application.yml";
    }
}
