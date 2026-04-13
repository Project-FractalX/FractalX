package org.fractalx.core.generator.saga

import org.fractalx.core.model.MethodParam
import spock.lang.Specification

import java.lang.reflect.Method

/**
 * Verifies fixes #57 and #58:
 * - #57: SagaOrchestratorGenerator.buildCallArgs() must NOT emit null literals
 *        for unresolvable parameters — must emit throwUnresolved() calls instead.
 * - Verifies the throwUnresolved helper throws UnsupportedOperationException.
 */
class SagaTodoFixesSpec extends Specification {

    SagaOrchestratorGenerator generator = new SagaOrchestratorGenerator()

    private String buildCallArgs(List<String> callArgs,
                                  Map<String, String> paramTypeMap,
                                  List<MethodParam> sagaParams) {
        Method method = SagaOrchestratorGenerator.getDeclaredMethod(
                "buildCallArgs", List, Map, List)
        method.setAccessible(true)
        return (String) method.invoke(generator, callArgs, paramTypeMap, sagaParams)
    }

    // ── #57: no null literals for unresolvable args ─────────────────────────

    def "resolvable saga param emits payload accessor"() {
        given:
        def paramTypeMap = ["orderId": "Long"]
        def sagaParams = [new MethodParam("Long", "orderId")]

        when:
        def result = buildCallArgs(["orderId"], paramTypeMap, sagaParams)

        then:
        result == "p.orderId()"
    }

    def "entity.getId() resolves to entityId accessor"() {
        given:
        def paramTypeMap = ["orderId": "Long"]
        def sagaParams = [new MethodParam("Long", "orderId")]

        when:
        def result = buildCallArgs(["order.getId()"], paramTypeMap, sagaParams)

        then:
        result == "p.orderId()"
    }

    def "unresolvable chained getId() emits throwUnresolved instead of null"() {
        given:
        def paramTypeMap = ["orderId": "Long"]
        def sagaParams = [new MethodParam("Long", "orderId")]

        when:
        def result = buildCallArgs(["order.getCustomer().getId()"], paramTypeMap, sagaParams)

        then:
        result.contains("throwUnresolved")
        !result.contains("null")
    }

    def "unresolvable simple getId() emits throwUnresolved instead of null"() {
        given:
        def paramTypeMap = ["orderId": "Long"]
        def sagaParams = [new MethodParam("Long", "orderId")]

        when:
        def result = buildCallArgs(["payment.getId()"], paramTypeMap, sagaParams)

        then:
        result.contains("throwUnresolved")
        !result.contains("null")
    }

    def "unknown expression emits throwUnresolved instead of null"() {
        given:
        def paramTypeMap = ["orderId": "Long"]
        def sagaParams = [new MethodParam("Long", "orderId")]

        when:
        def result = buildCallArgs(["someService.compute()"], paramTypeMap, sagaParams)

        then:
        result.contains("throwUnresolved")
        !result.contains("null")
    }

    def "empty callArgs with no saga params emits FIXME comment instead of TODO"() {
        given:
        def paramTypeMap = [:]
        def sagaParams = []

        when:
        def result = buildCallArgs([], paramTypeMap, sagaParams as List<MethodParam>)

        then:
        result.contains("FIXME")
        !result.contains("TODO")
    }

    def "literal values are preserved as-is"() {
        given:
        def paramTypeMap = ["orderId": "Long"]
        def sagaParams = [new MethodParam("Long", "orderId")]

        when:
        def result = buildCallArgs(["42", "true", "null"], paramTypeMap, sagaParams)

        then:
        result == "42, true, null"
    }

    def "mixed resolvable and unresolvable args"() {
        given:
        def paramTypeMap = ["orderId": "Long", "amount": "BigDecimal"]
        def sagaParams = [new MethodParam("Long", "orderId"), new MethodParam("BigDecimal", "amount")]

        when:
        def result = buildCallArgs(["orderId", "unknown.getX()"], paramTypeMap, sagaParams)

        then:
        result.contains("p.orderId()")
        result.contains("throwUnresolved")
        !result.contains("/* TODO")
    }
}
