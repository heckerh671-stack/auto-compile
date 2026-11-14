package dev.zalard.auth;

/**
 * Standalone tests for Auth's production endpoint resolution (Priority 4). No JUnit dependency — run with:
 *   javac -d out src/main/java/dev/zalard/auth/Auth.java src/test/java/dev/zalard/auth/AuthConfigTest.java
 *   java -cp out dev.zalard.auth.AuthConfigTest
 * Exit 0 = all pass.
 */
public class AuthConfigTest {
    static int fails = 0;
    static final String REAL = "https://zalard-auth.myname.workers.dev";
    static final String PLACEHOLDER = "https://zalard-auth.example.workers.dev";

    static void check(String name, boolean ok) {
        System.out.println((ok ? "PASS " : "FAIL ") + name);
        if (!ok) fails++;
    }

    static boolean throwsOn(boolean dev, String compiled, String sysOverride, String fileBase) {
        try { Auth.validateBase(dev, compiled, sysOverride, fileBase); return false; }
        catch (SecurityException e) { return true; }
    }

    // Detects whether the ACTUAL compiled constant is still the example placeholder.
    static boolean prodConstantIsPlaceholder() { return Auth.AUTH_BASE.equals(PLACEHOLDER); }

    public static void main(String[] args) {
        boolean release = args.length > 0 && args[0].equals("--release");

        // Check the REAL compiled constant (not a hardcoded literal). In --release this is a hard gate:
        // a production build must not ship with the placeholder AUTH_BASE.
        if (release) {
            if (prodConstantIsPlaceholder()) {
                System.out.println("FAIL release gate: Auth.AUTH_BASE is still the placeholder (" + Auth.AUTH_BASE + ")");
                System.out.println("  -> set your real https Worker URL in Auth.AUTH_BASE before a production build");
                System.exit(1);
            }
            try { Auth.validateBase(false, Auth.AUTH_BASE, null, null); }
            catch (SecurityException e) { System.out.println("FAIL release gate: AUTH_BASE invalid: " + e.getMessage()); System.exit(1); }
            System.out.println("PASS release gate: Auth.AUTH_BASE is configured (" + Auth.AUTH_BASE + ")");
            return;
        }
        System.out.println((prodConstantIsPlaceholder() ? "NOTE " : "PASS ")
                + "compiled AUTH_BASE " + (prodConstantIsPlaceholder() ? "is still the placeholder (set it before release)" : "is configured"));

        // PROD: a real configured URL is accepted
        check("prod accepts a real https URL", Auth.validateBase(false, REAL, null, null).equals(REAL));
        // PROD: still-placeholder URL fails closed
        check("prod rejects the placeholder", throwsOn(false, PLACEHOLDER, null, null));
        // PROD: http is rejected (TLS required)
        check("prod rejects http", throwsOn(false, "http://zalard-auth.myname.workers.dev", null, null));
        // PROD: a stray -Dzalard.auth.base override is IGNORED (anti-footgun), still uses compiled URL
        check("prod ignores sys override", Auth.validateBase(false, REAL, "https://evil.example", null).equals(REAL));
        // PROD: a config `base=` is likewise ignored
        check("prod ignores config base", Auth.validateBase(false, REAL, null, "https://evil.example").equals(REAL));
        // PROD: trailing slash trimmed
        check("prod trims trailing slash", Auth.validateBase(false, REAL + "/", null, null).equals(REAL));

        // DEV: override is honoured
        check("dev honours sys override", Auth.validateBase(true, REAL, "https://staging.example", null).equals("https://staging.example"));
        // DEV: http://localhost allowed
        check("dev allows http://localhost", Auth.validateBase(true, REAL, "http://localhost:8787", null).equals("http://localhost:8787"));
        // DEV: arbitrary http (non-localhost) rejected
        check("dev rejects http://evil", throwsOn(true, REAL, "http://evil.example", null));

        System.out.println();
        if (fails > 0) { System.out.println(fails + " test(s) FAILED"); System.exit(1); }
        System.out.println("all auth-config tests passed");
    }
}
