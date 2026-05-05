package org.fractalx.core.generator.transformation;

import org.fractalx.core.datamanagement.RelationshipDecoupler;
import org.fractalx.core.generator.GenerationContext;
import org.fractalx.core.generator.ServiceFileGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * Facade that applies all AST-based code transformations to a copied service directory.
 *
 * <p>Steps in order:
 * <ol>
 *   <li>Remove FractalX annotations ({@link AnnotationRemover})</li>
 *   <li>Decouple cross-service entity relationships ({@link RelationshipDecoupler})</li>
 *   <li>Ensure Spring imports are present ({@link ImportPreserver})</li>
 *   <li>Remove unused imports ({@link ImportCleaner})</li>
 * </ol>
 */
public class CodeTransformer implements ServiceFileGenerator {

    private static final Logger log = LoggerFactory.getLogger(CodeTransformer.class);

    private final AnnotationRemover annotationRemover;
    private final RelationshipDecoupler relationshipDecoupler;
    private final ImportPreserver importPreserver;
    private final ImportCleaner importCleaner;

    public CodeTransformer(AnnotationRemover annotationRemover,
                           RelationshipDecoupler relationshipDecoupler,
                           ImportPreserver importPreserver,
                           ImportCleaner importCleaner) {
        this.annotationRemover    = annotationRemover;
        this.relationshipDecoupler = relationshipDecoupler;
        this.importPreserver      = importPreserver;
        this.importCleaner        = importCleaner;
    }

    @Override
    public void generate(GenerationContext context) throws IOException {
        log.info("Transforming code for: {}", context.getModule().getServiceName());

        annotationRemover.processServiceDirectory(context.getServiceRoot());
        relationshipDecoupler.transform(context.getServiceRoot(), context.getModule(), context.servicePackage(),
                context.getDependencyGraph());
        importPreserver.generate(context);
        importCleaner.generate(context);

        log.info("Code transformation complete for {}", context.getModule().getServiceName());
    }
}
