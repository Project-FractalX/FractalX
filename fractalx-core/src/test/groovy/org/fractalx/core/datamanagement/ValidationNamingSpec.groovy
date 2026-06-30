package org.fractalx.core.datamanagement

import spock.lang.Specification

class ValidationNamingSpec extends Specification {

    def "singleValidateMethod produces validate<Type>Exists"() {
        expect:
        ValidationNaming.singleValidateMethod("Payment") == "validatePaymentExists"
        ValidationNaming.singleValidateMethod("Order")   == "validateOrderExists"
    }

    def "collectionValidateMethod produces validateAll<Type>Exist"() {
        expect:
        ValidationNaming.collectionValidateMethod("Course")   == "validateAllCourseExist"
        ValidationNaming.collectionValidateMethod("Product")  == "validateAllProductExist"
    }

    def "singleValidateMethod capitalizes first letter"() {
        expect:
        ValidationNaming.singleValidateMethod("payment").startsWith("validateP")
    }

    def "collectionValidateMethod capitalizes first letter"() {
        expect:
        ValidationNaming.collectionValidateMethod("item").startsWith("validateAllI")
    }
}
