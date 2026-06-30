package org.fractalx.core.model

import spock.lang.Specification

class FractalModuleSpec extends Specification {

    def "builder creates module with required fields"() {
        when:
        def m = FractalModule.builder()
            .serviceName("order-service")
            .packageName("com.example.order")
            .port(8081)
            .build()

        then:
        m.getServiceName() == "order-service"
        m.getPackageName() == "com.example.order"
        m.getPort() == 8081
    }

    def "grpcPort is http port plus GRPC_PORT_OFFSET"() {
        given:
        def m = FractalModule.builder()
            .serviceName("order-service")
            .packageName("com.example.order")
            .port(8081)
            .build()

        expect:
        m.grpcPort() == 8081 + FractalModule.GRPC_PORT_OFFSET
        FractalModule.GRPC_PORT_OFFSET == 10000
    }

    def "builder requires serviceName"() {
        when:
        FractalModule.builder().port(8081).build()

        then:
        thrown(IllegalStateException)
    }

    def "builder rejects port 0"() {
        when:
        FractalModule.builder().serviceName("svc").port(0).build()

        then:
        thrown(IllegalArgumentException)
    }

    def "builder rejects port over 65535"() {
        when:
        FractalModule.builder().serviceName("svc").port(70000).build()

        then:
        thrown(IllegalArgumentException)
    }

    def "dependencies list is immutable"() {
        given:
        def m = FractalModule.builder()
            .serviceName("s")
            .packageName("pkg")
            .port(8080)
            .dependencies(["PaymentService"])
            .build()

        when:
        m.getDependencies().add("extra")

        then:
        thrown(UnsupportedOperationException)
    }

    def "detectedImports set is immutable"() {
        given:
        def m = FractalModule.builder()
            .serviceName("s")
            .packageName("pkg")
            .port(8080)
            .detectedImports(["java.util.List"] as Set)
            .build()

        when:
        m.getDetectedImports().add("extra")

        then:
        thrown(UnsupportedOperationException)
    }

    def "independentDeployment defaults to true"() {
        expect:
        FractalModule.builder().serviceName("s").packageName("p").port(8080).build()
            .isIndependentDeployment()
    }

    def "toString includes serviceName and port"() {
        given:
        def m = FractalModule.builder()
            .serviceName("order-service")
            .packageName("com.example.order")
            .port(8081)
            .build()

        expect:
        m.toString().contains("order-service")
        m.toString().contains("8081")
    }

    def "className is optional and defaults to null"() {
        given:
        def m = FractalModule.builder()
            .serviceName("s")
            .packageName("pkg")
            .port(8080)
            .build()

        expect:
        m.getClassName() == null
    }
}
