package guardianlink.sidecar

import java.nio.file.Path

/**
 * GuardianLink sidecar: REST+JSON adapter over the governed engine.
 *
 * Configuration (environment):
 *   GUARDIANLINK_HMAC_SECRET  REQUIRED. Hex-encoded pre-shared key used
 *       for caller authentication (verifySignature) and — only as a
 *       legacy fallback — the assent binding. The server refuses to
 *       start without it.
 *   GUARDIANLINK_ASSENT_SECRET  RECOMMENDED. Hex-encoded key held by
 *       the person's side ONLY: when set, human assent verifies against
 *       this key and the caller key can no longer produce assent. When
 *       unset, assent falls back to the shared caller key and a warning
 *       is printed at startup — in that posture anyone who can call the
 *       API can also sign "human" assent.
 *   GUARDIANLINK_DEMO         "1" enables the demo posture: fabricated
 *       gate evidence, pass-through ZK verification, and an optional
 *       revocation feed. OFF by default. Outside demo mode the sidecar
 *       fails closed: Gate 0/Gate 3 halt for lack of real adapters and
 *       Gate 8 halts when no revocation feed is wired in code.
 *   GUARDIANLINK_PORT         HTTP port. Default 8080.
 *   GUARDIANLINK_DATA_DIR     Anchor + clock state directory.
 *       Default ./sidecar-data.
 *
 * Verifier posture: assent and caller identity are HMAC-bound (real
 * enforcement of the binding contract), with assent separated onto its
 * own key when GUARDIANLINK_ASSENT_SECRET is set. Zero-knowledge
 * proofs are NOT enforced at this layer (Gate 3 halts outside demo
 * mode); governance policy (boundary/policy/meta-loop) defaults via
 * SidecarPolicy and the revocation feed must be wired in code for
 * production. See README.md.
 */
fun main() {
    val secretHex = System.getenv("GUARDIANLINK_HMAC_SECRET")
        ?: fail("GUARDIANLINK_HMAC_SECRET must be set (hex-encoded pre-shared key). Refusing to start without an assent verifier.")
    val secret = secretHex.hexToBytes()
        ?: fail("GUARDIANLINK_HMAC_SECRET is not valid hex. Refusing to start.")
    if (secret.size < 16) fail("GUARDIANLINK_HMAC_SECRET must be at least 16 bytes (32 hex chars). Refusing to start.")

    val assentSecret = System.getenv("GUARDIANLINK_ASSENT_SECRET")?.let { hex ->
        val bytes = hex.hexToBytes()
            ?: fail("GUARDIANLINK_ASSENT_SECRET is not valid hex. Refusing to start.")
        if (bytes.size < 16) fail("GUARDIANLINK_ASSENT_SECRET must be at least 16 bytes (32 hex chars). Refusing to start.")
        bytes
    }
    val demoMode = System.getenv("GUARDIANLINK_DEMO") == "1"

    val port = System.getenv("GUARDIANLINK_PORT")?.toIntOrNull() ?: 8080
    val dataDir = Path.of(System.getenv("GUARDIANLINK_DATA_DIR") ?: "./sidecar-data")

    val engine = GuardianLinkEngine(dataDir, secret, assentSecret = assentSecret, demoMode = demoMode)
    val server = SidecarServer(engine, port)
    server.start()
    println("GuardianLink sidecar listening on port ${server.port} (data dir: $dataDir)")
    println("Assent binding: HMAC-SHA256, pre-shared key grade. Voice: GMM-UBM + challenge liveness (demo enrollment). See README.md.")
    if (assentSecret != null) {
        println("Assent separation: ON — assent verifies against GUARDIANLINK_ASSENT_SECRET; the caller key cannot sign assent.")
    } else {
        System.err.println(
            "WARNING: GUARDIANLINK_ASSENT_SECRET is not set. Assent falls back to the shared caller key: " +
                "anyone who can call this API can also sign human assent. Set GUARDIANLINK_ASSENT_SECRET to separate them."
        )
    }
    if (demoMode) {
        System.err.println(
            "WARNING: GUARDIANLINK_DEMO=1 — demo posture: gate evidence is fabricated, ZK verification passes, " +
                "and the revocation feed is optional. Do not deploy in this posture."
        )
    } else {
        println("Posture: fail-closed (demo off). Assents halt at Gate 0/Gate 3 without real adapters, and at Gate 8 unless a revocation feed is wired via SidecarPolicy in code.")
    }
}

private fun fail(message: String): Nothing {
    System.err.println("FATAL: $message")
    kotlin.system.exitProcess(1)
}
