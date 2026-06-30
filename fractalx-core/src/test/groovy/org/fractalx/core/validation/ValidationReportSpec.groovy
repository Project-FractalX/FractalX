package org.fractalx.core.validation

import spock.lang.Specification

class ValidationReportSpec extends Specification {

    private static ValidationIssue error(String ruleId, String module) {
        new ValidationIssue(ValidationSeverity.ERROR, ruleId, module, "msg", "fix")
    }

    private static ValidationIssue warning(String ruleId, String module) {
        new ValidationIssue(ValidationSeverity.WARNING, ruleId, module, "msg", "fix")
    }

    def "isClean returns true when no issues"() {
        expect:
        new ValidationReport([]).isClean()
    }

    def "isClean returns false when issues present"() {
        expect:
        !new ValidationReport([error("RULE", "svc")]).isClean()
    }

    def "hasErrors returns true only when ERROR severity present"() {
        given:
        def report = new ValidationReport([error("R1", "svc")])

        expect:
        report.hasErrors()
    }

    def "hasErrors returns false when only warnings"() {
        given:
        def report = new ValidationReport([warning("W1", "svc")])

        expect:
        !report.hasErrors()
    }

    def "errors() returns only ERROR issues"() {
        given:
        def report = new ValidationReport([
            error("E1", "svc"),
            warning("W1", "svc"),
            error("E2", "svc")
        ])

        expect:
        report.errors().size() == 2
        report.errors().every { it.isError() }
    }

    def "warnings() returns only WARNING issues"() {
        given:
        def report = new ValidationReport([
            error("E1", "svc"),
            warning("W1", "svc"),
            warning("W2", "svc")
        ])

        expect:
        report.warnings().size() == 2
        report.warnings().every { it.isWarning() }
    }

    def "issues() returns all issues"() {
        given:
        def all = [error("E1", "svc"), warning("W1", "svc"), error("E2", "svc")]
        def report = new ValidationReport(all)

        expect:
        report.issues().size() == 3
    }

    def "partition splits errors and warnings in a single pass"() {
        given:
        def report = new ValidationReport([
            error("E1", "svc1"),
            warning("W1", "svc2"),
            error("E2", "svc3"),
            warning("W2", "svc4")
        ])

        when:
        def p = report.partition()

        then:
        p.errors().size() == 2
        p.warnings().size() == 2
        p.errors().every { it.isError() }
        p.warnings().every { it.isWarning() }
    }

    def "formatReport contains separator when issues present"() {
        given:
        def report = new ValidationReport([error("DUP_PORT", "order-service")])

        when:
        def text = report.formatReport()

        then:
        text.contains("FractalX Decomposition Validation")
        text.contains("[ERROR]")
        text.contains("DUP_PORT")
        text.contains("order-service")
    }

    def "formatReport returns clean message when no issues"() {
        expect:
        new ValidationReport([]).formatReport().contains("No decomposition issues found")
    }

    def "formatReport shows error count and warning count"() {
        given:
        def report = new ValidationReport([
            error("E1", "svc"),
            warning("W1", "svc")
        ])

        when:
        def text = report.formatReport()

        then:
        text.contains("1 error(s)")
        text.contains("1 warning(s)")
    }

    def "issues list is immutable"() {
        given:
        def list = [error("E1", "svc")]
        def report = new ValidationReport(list)

        when:
        report.issues().add(error("E2", "svc"))

        then:
        thrown(UnsupportedOperationException)
    }
}
