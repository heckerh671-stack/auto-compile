package dev.zalard.update;

import java.nio.file.*;
import java.util.*;

/**
 * Client-side signed-update tests (Priority 8). Fixtures from tools/test_sign.py --fixtures.
 * Covers verification, rollback, path-traversal, atomic/crash-safe install + recovery, and the download path.
 *   javac -d out src/main/java/dev/zalard/update/*.java src/test/java/dev/zalard/update/UpdateVerifierTest.java
 *   java -cp out dev.zalard.update.UpdateVerifierTest <fixtures-dir>
 */
public class UpdateVerifierTest {
    static int fails = 0;
    static void check(String n, boolean c) { System.out.println((c ? "PASS " : "FAIL ") + n); if (!c) fails++; }
    interface Run { void run() throws Exception; }
    static boolean throwsOn(Run c) { try { c.run(); return false; } catch (Exception e) { return true; } }

    static Path freshStaging(Path fx) throws Exception {
        Path s = Files.createTempDirectory("zstage");
        Files.copy(fx.resolve("good/a.bin"), s.resolve("a.bin"));
        Files.copy(fx.resolve("good/b.bin"), s.resolve("b.bin"));
        return s;
    }

    public static void main(String[] args) throws Exception {
        Path fx = Path.of(args[0]);
        byte[] pub = UpdateVerifier.hex(Files.readString(fx.resolve("pub.hex")).trim());
        String manifest = Files.readString(fx.resolve("manifest.txt"));          // version 0.2.0

        // 1. valid update -> staged under versions/0.2.0 and made active
        Path root = Files.createTempDirectory("zroot");
        String v = UpdateVerifier.applyUpdate(manifest, pub, "0.1.0", freshStaging(fx), root);
        check("valid update applies, returns new version", "0.2.0".equals(v));
        check("active version is now 0.2.0", "0.2.0".equals(UpdateVerifier.activeVersion(root)));
        check("both files present in versions/0.2.0",
                Files.isRegularFile(root.resolve("0.2.0/a.bin")) && Files.isRegularFile(root.resolve("0.2.0/b.bin")));

        // 2. tampered staged file -> rejected, active version untouched (recovery)
        Path root2 = Files.createTempDirectory("zroot2");
        UpdateVerifier.activate(root2, "0.1.0");                                  // pretend 0.1.0 is live
        Path bad = freshStaging(fx); Files.writeString(bad.resolve("a.bin"), "corrupt");
        check("tampered file -> rejected", throwsOn(() -> UpdateVerifier.applyUpdate(manifest, pub, "0.1.0", bad, root2)));
        check("recovery: active version still 0.1.0 after a bad update", "0.1.0".equals(UpdateVerifier.activeVersion(root2)));

        // 3. INTERRUPTED install: stageVersion succeeds but the process "crashes" before activate().
        //    The active version must NOT change (the commit is the single atomic flip).
        Path root3 = Files.createTempDirectory("zroot3");
        UpdateVerifier.activate(root3, "0.1.0");
        String staged = UpdateVerifier.stageVersion(manifest, pub, "0.1.0", freshStaging(fx), root3);  // no activate()
        check("staged new version dir exists", Files.isDirectory(root3.resolve("0.2.0")));
        check("interrupted-before-commit: active still 0.1.0", "0.1.0".equals(UpdateVerifier.activeVersion(root3)));
        UpdateVerifier.activate(root3, staged);                                   // now commit
        check("after commit: active is 0.2.0, no partial state", "0.2.0".equals(UpdateVerifier.activeVersion(root3)));

        // 4. bad signature / rollback / traversal / wrong key
        check("bad signature -> rejected", throwsOn(() -> UpdateVerifier.verifyManifest(Files.readString(fx.resolve("manifest_badsig.txt")), pub)));
        check("rollback older version -> rejected", throwsOn(() -> UpdateVerifier.applyUpdate(Files.readString(fx.resolve("manifest_rollback.txt")), pub, "0.2.0", freshStaging(fx), Files.createTempDirectory("zr4"))));
        check("path traversal -> rejected", throwsOn(() -> UpdateVerifier.verifyManifest(Files.readString(fx.resolve("manifest_traversal.txt")), pub)));
        byte[] wrong = new byte[32]; wrong[0] = 1;
        check("wrong publisher key -> rejected", throwsOn(() -> UpdateVerifier.verifyManifest(manifest, wrong)));

        // 5. isNewer sanity
        check("isNewer 0.2.0 > 0.1.9", UpdateVerifier.isNewer("0.2.0", "0.1.9"));
        check("isNewer rejects equal", !UpdateVerifier.isNewer("0.2.0", "0.2.0"));
        check("isNewer rejects older", !UpdateVerifier.isNewer("0.1.0", "0.2.0"));

        // 6. Updater download path with an in-memory fetcher
        byte[] a = Files.readAllBytes(fx.resolve("good/a.bin")), b = Files.readAllBytes(fx.resolve("good/b.bin"));
        Map<String, byte[]> server = new HashMap<>();
        server.put("manifest.txt", manifest.getBytes());
        server.put("a.bin", a); server.put("b.bin", b);
        Updater.Fetcher fetcher = p -> { if (!server.containsKey(p)) throw new java.io.IOException("404 " + p); return server.get(p); };

        Path root6 = Files.createTempDirectory("zroot6");
        String got = Updater.checkAndApply(fetcher, pub, root6);
        check("Updater downloads + installs a newer release", "0.2.0".equals(got) && "0.2.0".equals(UpdateVerifier.activeVersion(root6)));
        check("Updater returns null when already current", Updater.checkAndApply(fetcher, pub, root6) == null);
        check("Updater fail-closed on empty publisher key", throwsOn(() -> Updater.checkAndApply(fetcher, new byte[0], Files.createTempDirectory("zr7"))));

        // 7. production-integration failure modes — the active version must never change on any failure
        // failed download of a file (404)
        Updater.Fetcher drop = p -> { if (p.equals("b.bin")) throw new java.io.IOException("404"); return server.get(p); };
        Path root7 = Files.createTempDirectory("zroot7"); UpdateVerifier.activate(root7, "0.1.0");
        check("failed download -> rejected", throwsOn(() -> Updater.checkAndApply(drop, pub, root7)));
        check("failed download: active still 0.1.0", "0.1.0".equals(UpdateVerifier.activeVersion(root7)));

        // corrupted file content (hash mismatch)
        Updater.Fetcher corrupt = p -> p.equals("a.bin") ? "corrupted".getBytes() : server.get(p);
        Path root8 = Files.createTempDirectory("zroot8"); UpdateVerifier.activate(root8, "0.1.0");
        check("corrupted download -> rejected (hash)", throwsOn(() -> Updater.checkAndApply(corrupt, pub, root8)));
        check("corrupted download: active still 0.1.0", "0.1.0".equals(UpdateVerifier.activeVersion(root8)));

        // missing/invalid manifest
        Updater.Fetcher noManifest = p -> { if (p.equals("manifest.txt")) throw new java.io.IOException("404"); return server.get(p); };
        check("missing manifest -> rejected", throwsOn(() -> Updater.checkAndApply(noManifest, pub, Files.createTempDirectory("zr9"))));

        // tampered manifest served by the fetcher (invalid signature over the wire)
        byte[] badManifest = Files.readString(fx.resolve("manifest_badsig.txt")).getBytes();
        Updater.Fetcher badSig = p -> p.equals("manifest.txt") ? badManifest : server.get(p);
        Path root10 = Files.createTempDirectory("zroot10"); UpdateVerifier.activate(root10, "0.1.0");
        check("invalid signature over the wire -> rejected", throwsOn(() -> Updater.checkAndApply(badSig, pub, root10)));
        check("invalid signature: active still 0.1.0", "0.1.0".equals(UpdateVerifier.activeVersion(root10)));

        System.out.println();
        if (fails > 0) { System.out.println(fails + " test(s) FAILED"); System.exit(1); }
        System.out.println("all update-verifier tests passed");
    }
}
