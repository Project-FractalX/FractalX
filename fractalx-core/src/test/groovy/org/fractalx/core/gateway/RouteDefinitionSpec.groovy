package org.fractalx.core.gateway

import spock.lang.Specification

class RouteDefinitionSpec extends Specification {

    def "constructor sets all primary fields"() {
        given:
        def rd = new RouteDefinition("order-service", "/api/orders", "GET", 8081)

        expect:
        rd.getServiceName() == "order-service"
        rd.getPath()        == "/api/orders"
        rd.getMethod()      == "GET"
        rd.getServicePort() == 8081
    }

    def "id is derived from serviceName path and method"() {
        given:
        def rd = new RouteDefinition("order-service", "/api/orders", "GET", 8081)

        expect:
        rd.getId().contains("order-service")
        rd.getId().toLowerCase().contains("get")
    }

    def "destinationPath defaults to path"() {
        given:
        def rd = new RouteDefinition("svc", "/api/v1/items", "POST", 8080)

        expect:
        rd.getDestinationPath() == "/api/v1/items"
    }

    def "setters update fields"() {
        given:
        def rd = new RouteDefinition("svc", "/old", "GET", 8080)

        when:
        rd.setPath("/new")
        rd.setMethod("POST")
        rd.setServicePort(9090)
        rd.setServiceName("new-svc")
        rd.setDestinationPath("/dest")
        rd.setId("custom-id")
        rd.setFilters(["/StripPrefix=1"])

        then:
        rd.getPath()            == "/new"
        rd.getMethod()          == "POST"
        rd.getServicePort()     == 9090
        rd.getServiceName()     == "new-svc"
        rd.getDestinationPath() == "/dest"
        rd.getId()              == "custom-id"
        rd.getFilters()         == ["/StripPrefix=1"]
    }

    def "filters list defaults to empty"() {
        given:
        def rd = new RouteDefinition("svc", "/api", "GET", 8080)

        expect:
        rd.getFilters().isEmpty()
    }

    def "toString contains method, path and serviceName"() {
        given:
        def rd = new RouteDefinition("order-service", "/api/orders", "GET", 8081)

        expect:
        rd.toString().contains("GET")
        rd.toString().contains("/api/orders")
        rd.toString().contains("order-service")
    }
}
