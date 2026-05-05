package org.fractalx.core.validation;

import org.fractalx.core.config.FractalxConfig;
import org.fractalx.core.config.FractalxConfigReader;
import org.fractalx.core.model.FractalModule;
import org.fractalx.core.validation.rules.CircularDependencyRule;
import org.fractalx.core.validation.rules.ControllerOwnershipConflictRule;
import org.fractalx.core.validation.rules.DuplicatePortRule;
import org.fractalx.core.validation.rules.DuplicateServiceNameRule;
import org.fractalx.core.validation.rules.InfrastructurePortConflictRule;
import org.fractalx.core.validation.rules.LombokConstructorRule;
import org.fractalx.core.validation.rules.RepositoryBoundaryRule;
import org.fractalx.core.validation.rules.SagaIntegrityRule;
import org.fractalx.core.validation.rules.ServiceNameFormatRule;
import org.fractalx.core.validation.rules.UnresolvedDependencyRule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Pre-generation validation orchestrator.
 *
 * <p>Runs all registered {@link ValidationRule}s against the module list produced
 * by {@code ModuleAnalyzer} and returns a {@link ValidationReport} summarising
 * every issue found.
 *
 * <p>Rules run in registration order. All rules are always executed (no
 * fail-fast) so developers see the complete set of problems in one pass.
 *
 * <p>If any rule's {@link ValidationRule#validate} method throws an unexpected
 * exception the rule is skipped (with a WARN log) to ensure a broken rule
 * never silently blocks a valid decomposition.
 *
 * <h3>Adding a new rule</h3>
 * Create a class that implements {@link ValidationRule} in the {@code rules/}
 * sub-package and add it to {@link #buildRules()}.  No other change is needed.
 */
public class DecompositionValidator {

    private static final Logger log = LoggerFactory.getLogger(DecompositionValidator.class);

    private final List<ValidationRule> rules;

    /** Constructs the validator with the default rule set. */
    public DecompositionValidator() {
        this.rules = buildRules();
    }

    /** Package-private constructor for testing with a custom rule set. */
    DecompositionValidator(List<ValidationRule> rules) {
        this.rules = List.copyOf(rules);
    }

    // -------------------------------------------------------------------------

    /**
     * Validates all modules and returns the aggregated report.
     *
     * @param modules    modules returned by {@code ModuleAnalyzer.analyzeProject()}
     * @param sourceRoot monolith source root ({@code src/main/java})
     * @return report containing all found issues (never {@code null})
     * @throws IOException if config reading or source-tree inspection fails
     */
    public ValidationReport validate(List<FractalModule> modules, Path sourceRoot)
            throws IOException {
        // sourceRoot is src/main/java; resources are at ../resources; project root is ../../..
        Path resourcesDir = sourceRoot.resolveSibling("resources");
        Path p1 = sourceRoot.getParent();
        Path p2 = p1 != null ? p1.getParent() : null;
        Path projectRoot  = p2 != null ? p2.getParent() : null;
        FractalxConfig config = new FractalxConfigReader().read(
                java.nio.file.Files.isDirectory(resourcesDir) ? resourcesDir : sourceRoot,
                projectRoot != null && java.nio.file.Files.isDirectory(projectRoot) ? projectRoot : sourceRoot);
        ValidationContext ctx = new ValidationContext(modules, sourceRoot, config);

        List<ValidationIssue> all = new ArrayList<>();
        for (ValidationRule rule : rules) {
            try {
                List<ValidationIssue> found = rule.validate(ctx);
                all.addAll(found);
                if (!found.isEmpty()) {
                    log.debug("Rule [{}] found {} issue(s)", rule.ruleId(), found.size());
                }
            } catch (Exception e) {
                // A failing rule must never block a valid decomposition — skip and warn.
                log.warn("Validation rule [{}] threw an unexpected error (skipping): {}",
                        rule.ruleId(), e.getMessage(), e);
            }
        }
        return new ValidationReport(all);
    }

    // -------------------------------------------------------------------------

    /**
     * Returns the ordered list of rules to run.
     * Rules are run in registration order; earlier rules appear first in the report.
     */
    private static List<ValidationRule> buildRules() {
        return List.of(
                // Structural / naming — fast, no I/O
                new ServiceNameFormatRule(),
                new DuplicateServiceNameRule(),
                new DuplicatePortRule(),
                new InfrastructurePortConflictRule(),
                // Graph analysis — no I/O
                new CircularDependencyRule(),
                // Resolution check — no I/O
                new UnresolvedDependencyRule(),
                // Source-tree analysis — requires I/O
                new LombokConstructorRule(),
                new RepositoryBoundaryRule(),
                new ControllerOwnershipConflictRule(),
                new SagaIntegrityRule()
        );
    }
}
