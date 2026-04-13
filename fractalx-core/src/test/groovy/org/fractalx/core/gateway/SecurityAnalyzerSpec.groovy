package org.fractalx.core.gateway

import com.github.javaparser.JavaParser
import com.github.javaparser.ast.expr.StringLiteralExpr
import spock.lang.Specification
import spock.lang.Unroll

class SecurityAnalyzerSpec extends Specification {

    // ── Issue #55: Bearer/Authorization detection must be case-insensitive ───

    @Unroll
    def "Bearer literal '#literal' is detected case-insensitively"() {
        given:
        def cu = new JavaParser().parse("""
            public class MyFilter extends OncePerRequestFilter {
                void doFilter() {
                    String h = request.getHeader("${literal}");
                }
            }
        """).getResult().get()

        when:
        boolean found = cu.findAll(StringLiteralExpr).stream()
                .anyMatch(s -> s.asString().toLowerCase().contains("bearer ")
                           || s.asString().equalsIgnoreCase("authorization"))

        then:
        found == expected

        where:
        literal           || expected
        "Authorization"   || true
        "authorization"   || true
        "AUTHORIZATION"   || true
        "Bearer token"    || true
        "bearer xyz"      || true
        "Content-Type"    || false
    }
}
