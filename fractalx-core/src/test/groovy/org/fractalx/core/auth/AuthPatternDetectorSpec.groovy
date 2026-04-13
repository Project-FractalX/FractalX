package org.fractalx.core.auth

import spock.lang.Specification
import spock.lang.TempDir

import java.nio.file.Files
import java.nio.file.Path

class AuthPatternDetectorSpec extends Specification {

    @TempDir Path tempDir

    /** The detector expects projectRoot/src/main/java/... layout */
    private Path srcMain

    def setup() {
        srcMain = tempDir.resolve("src/main/java")
        Files.createDirectories(srcMain)
        // Write a minimal application.yml with jwt.secret so detected=true is possible
        def resources = tempDir.resolve("src/main/resources")
        Files.createDirectories(resources)
        Files.writeString(resources.resolve("application.yml"), "jwt:\n  secret: test-secret-key\n")
    }

    // ── Issue #48: expanded auth endpoint detection ──────────────────────────

    def "detects auth controller with /api/auth mapping"() {
        given:
        writeJava("com/example/auth/AuthController.java", """
            package com.example.auth;
            @RestController
            @RequestMapping("/api/auth")
            public class AuthController {
                @PostMapping("/login")
                public void login() {}
            }
        """)
        writeUserDetailsEntity()

        when:
        def result = new AuthPatternDetector(tempDir).detect()

        then:
        result.authPackage() == "com.example.auth"
    }

    def "detects auth controller with /login mapping (not just /api/auth)"() {
        given:
        writeJava("com/example/identity/LoginController.java", """
            package com.example.identity;
            @RestController
            public class LoginController {
                @PostMapping("/login")
                public void doLogin() {}
            }
        """)
        writeUserDetailsEntity()

        when:
        def result = new AuthPatternDetector(tempDir).detect()

        then:
        result.authPackage() == "com.example.identity"
    }

    def "detects auth controller with /oauth mapping"() {
        given:
        writeJava("com/example/sso/OAuthController.java", """
            package com.example.sso;
            @RestController
            public class OAuthController {
                @GetMapping("/oauth/authorize")
                public void authorize() {}
            }
        """)
        writeUserDetailsEntity()

        when:
        def result = new AuthPatternDetector(tempDir).detect()

        then:
        result.authPackage() == "com.example.sso"
    }

    // ── Issue #47: detect via Spring Security annotations ────────────────────

    def "detects SecurityFilterChain bean as auth class"() {
        given:
        writeJava("com/example/config/SecurityConfig.java", """
            package com.example.config;
            public class SecurityConfig {
                public SecurityFilterChain filterChain() { return null; }
            }
        """)
        writeUserDetailsEntity()

        when:
        def result = new AuthPatternDetector(tempDir).detect()

        then:
        result.authPackage() == "com.example.config"
    }

    // ── Issue #50: annotation resolution with fully-qualified names ──────────

    def "detects UserDetails entity with simple @Entity annotation"() {
        given:
        writeJava("com/example/user/AppUser.java", """
            package com.example.user;
            @Entity
            public class AppUser implements UserDetails {
                private String username;
                private String password;
                private String customField;
            }
        """)
        // Also need an auth controller for detected=true
        writeJava("com/example/auth/AuthController.java", """
            package com.example.auth;
            @RestController
            @RequestMapping("/api/auth")
            public class AuthController {}
        """)

        when:
        def result = new AuthPatternDetector(tempDir).detect()

        then:
        result.userPackage() == "com.example.user"
    }

    def "detects UserDetails entity with fully-qualified @jakarta.persistence.Entity"() {
        given:
        writeJava("com/example/user/AppUser.java", """
            package com.example.user;
            @jakarta.persistence.Entity
            public class AppUser implements UserDetails {
                private String username;
                private String password;
            }
        """)
        writeJava("com/example/auth/AuthController.java", """
            package com.example.auth;
            @RestController
            @RequestMapping("/api/auth")
            public class AuthController {}
        """)

        when:
        def result = new AuthPatternDetector(tempDir).detect()

        then:
        result.userPackage() == "com.example.user"
    }

    private void writeJava(String relPath, String content) {
        def file = srcMain.resolve(relPath)
        Files.createDirectories(file.parent)
        Files.writeString(file, content)
    }

    private void writeUserDetailsEntity() {
        writeJava("com/example/user/AppUser.java", """
            package com.example.user;
            @Entity
            public class AppUser implements UserDetails {
                private String username;
                private String password;
            }
        """)
    }
}
