package org.fractalx.core.util

import com.github.javaparser.JavaParser
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration
import spock.lang.Specification
import spock.lang.Unroll

class SpringDataUtilsSpec extends Specification {

    // ── Issue #52: isRepositoryInterface — exact match, not contains ─────────

    def "detects JpaRepository as a repository interface"() {
        given:
        def cu = new JavaParser().parse("""
            public interface OrderRepository extends JpaRepository<Order, Long> {}
        """).getResult().get()
        def decl = cu.findAll(ClassOrInterfaceDeclaration).first()

        expect:
        SpringDataUtils.isRepositoryInterface(decl)
    }

    def "detects CrudRepository as a repository interface"() {
        given:
        def cu = new JavaParser().parse("""
            public interface UserRepo extends CrudRepository<User, Long> {}
        """).getResult().get()
        def decl = cu.findAll(ClassOrInterfaceDeclaration).first()

        expect:
        SpringDataUtils.isRepositoryInterface(decl)
    }

    def "detects MongoRepository as a repository interface"() {
        given:
        def cu = new JavaParser().parse("""
            public interface DocStore extends MongoRepository<Doc, String> {}
        """).getResult().get()
        def decl = cu.findAll(ClassOrInterfaceDeclaration).first()

        expect:
        SpringDataUtils.isRepositoryInterface(decl)
    }

    def "does NOT flag RepositoryFactory as a repository (no extends)"() {
        given:
        def cu = new JavaParser().parse("""
            public class RepositoryFactory {}
        """).getResult().get()
        def decl = cu.findAll(ClassOrInterfaceDeclaration).first()

        expect:
        !SpringDataUtils.isRepositoryInterface(decl)
    }

    def "does NOT flag interface extending custom DocumentRepositoryPattern"() {
        given:
        def cu = new JavaParser().parse("""
            public interface MyRepo extends DocumentRepositoryPattern {}
        """).getResult().get()
        def decl = cu.findAll(ClassOrInterfaceDeclaration).first()

        expect:
        !SpringDataUtils.isRepositoryInterface(decl)
    }

    // ── Issue #43: isRepositoryQueryMethod — all Spring Data prefixes ────────

    @Unroll
    def "isRepositoryQueryMethod('#method') == #expected"() {
        expect:
        SpringDataUtils.isRepositoryQueryMethod(method) == expected

        where:
        method                     || expected
        "findByCustomerId"         || true
        "getByEmail"               || true
        "queryAllActive"           || true
        "searchByName"             || true
        "readAllActiveUsers"       || true     // 'read' prefix — was missing before
        "streamByStatus"           || true     // 'stream' prefix
        "countByStatus"            || true     // 'count' prefix
        "existsByEmail"            || true     // 'exists' prefix
        "deleteByExpired"          || true     // 'delete' prefix
        "removeByCustomerId"       || true     // 'remove' prefix
        "retrieveByCustomerId"     || true     // 'retrieve' — was missing before
        "fetchByOrderId"           || true
        "loadById"                 || true
        "processPayment"           || false    // not a query method
        "cancelOrder"              || false
        ""                         || false
        null                       || false
    }

    // ── Issue #42: isRepositoryFetchExpression ───────────────────────────────

    @Unroll
    def "isRepositoryFetchExpression('#expr') == #expected"() {
        expect:
        SpringDataUtils.isRepositoryFetchExpression(expr) == expected

        where:
        expr                                          || expected
        "orderRepository.findById(id).orElseThrow()"  || true
        "repo.getByEmail(email)"                      || true
        "repo.retrieveByCustomerId(cid)"              || true    // 'retrieve' prefix
        "repo.readById(id).orElse(null)"              || true
        "repo.findAll()"                              || true
        "repo.findOne(spec)"                          || true
        "repo.countByStatus(status)"                  || true
        "new Order()"                                 || false
        "paymentService.process()"                    || false
        ""                                            || false
        null                                          || false
    }
}
