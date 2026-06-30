package org.fractalx.core.validation

import spock.lang.Specification

class ValidationIssueSpec extends Specification {

    def "isError() returns true for ERROR severity"() {
        given:
        def issue = new ValidationIssue(ValidationSeverity.ERROR, "DUP_PORT", "svc", "msg", "fix")

        expect:
        issue.isError()
        !issue.isWarning()
    }

    def "isWarning() returns true for WARNING severity"() {
        given:
        def issue = new ValidationIssue(ValidationSeverity.WARNING, "UNRES_DEP", "svc", "msg", "fix")

        expect:
        issue.isWarning()
        !issue.isError()
    }

    def "all record fields are accessible"() {
        given:
        def issue = new ValidationIssue(ValidationSeverity.ERROR, "RULE_ID", "my-service", "problem", "do this")

        expect:
        issue.severity()    == ValidationSeverity.ERROR
        issue.ruleId()      == "RULE_ID"
        issue.moduleName()  == "my-service"
        issue.message()     == "problem"
        issue.fix()         == "do this"
    }

    def "ValidationSeverity has ERROR and WARNING"() {
        expect:
        ValidationSeverity.ERROR   != null
        ValidationSeverity.WARNING != null
        ValidationSeverity.values().length == 2
    }

    def "ValidationContext record stores modules sourceRoot and config"() {
        given:
        def modules = [FractalModule_buildHelper("svc")]
        def ctx = new ValidationContext(modules, null, null)

        expect:
        ctx.modules().size() == 1
        ctx.sourceRoot() == null
        ctx.config() == null
    }

    // minimal helper to build a module without needing full builder chain in this spec
    private static org.fractalx.core.model.FractalModule FractalModule_buildHelper(String name) {
        org.fractalx.core.model.FractalModule.builder()
            .serviceName(name).packageName("com.example").port(8080).build()
    }
}
