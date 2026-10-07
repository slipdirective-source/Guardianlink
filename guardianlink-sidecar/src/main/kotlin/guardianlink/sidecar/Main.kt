package guardianlink.sidecar

import java.nio.file.Path

/**
 * GuardianLink sidecar: REST+JSON adapter over the governed engine.
 *
 * Configuration (environment):
 *   GUARDIANLINK_HMAC_SECRET  REQUIRED. Hex-encoded pre-shared key used
 *       for the assent binding (verifyAssent) and caller authentication
 *       (verifySignature). The server refuses to start without it — a
 *       sidecar with no assent verifier is deployment-unsafe.
 *   GUARDIANLINK_PORT         HTTP port. Default 8080.
 *   GUARDIANLINK_DATA_DIR     Anchor + clock state directory.
 *       Default ./sidecar-data.
 *
 * Verifier posture: assent and caller identity are HMAC-bound under the
 * pre-shared key (real enforcement of the binding contract). Zero-
 * knowledge proofs are NOT enforced at this layer; governance policy
 * (boundary/policy/meta-loop) and the revocation feed default to
 * permissive/empty via SidecarPolicy — supply real ones in code for
 * production. See README.md.
 */
fun main() {
    val secretHex = System.getenv("GUARDIANLINK_HMAC_SECRET")
        ?: fail("GUARDIANLINK_HMAC_SECRET must be set (hex-encoded pre-shared key). Refusing to start without an assent verifier.")
    val secret = secretHex.hexToBytes()
        ?: fail("GUARDIANLINK_HMAC_SECRET is not valid hex. Refusing to start.")
    if (secret.size < 16) fail("GUARDIANLINK_HMAC_SECRET must be at least 16 bytes (32 hex chars). Refusing to start.")

    val port = System.getenv("GUARDIANLINK_PORT")?.toIntOrNull() ?: 8080
    val dataDir = Path.of(System.getenv("GUARDIANLINK_DATA_DIR") ?: "./sidecar-data")

    val engine = GuardianLinkEngine(dataDir, secret)
    val server = SidecarServer(engine, port)
    server.start()
    println("GuardianLink sidecar listening on port ${server.port} (data dir: $dataDir)")
    println("Assent binding: HMAC-SHA256, pre-shared key grade. Voice: GMM-UBM + challenge liveness (demo enrollment). See README.md.")
}

private fun fail(message: String): Nothing {
    System.err.println("FATAL: $message")
    kotlin.system.exitProcess(1)
}
