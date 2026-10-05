package guardianlink.mfa

import java.math.BigInteger
import java.security.SecureRandom

/**
 * Shamir (m, n) secret sharing over a prime field, binding MFA factor
 * categories to shares (Codex v2.2, Gate 1 support).
 *
 * ADVERSARIAL NOTES — every item below is fail-closed:
 *
 * - Every share is range-checked BEFORE any field arithmetic:
 *   x ∈ [1, prime), y ∈ [0, prime), no duplicate x. An x=0 share would
 *   force Lagrange interpolation at 0 to return its own y (CRITICAL —
 *   an attacker share dictates the "secret"); duplicate x makes
 *   modInverse throw; out-of-field values break the field arithmetic.
 * - The hardware-biometric binding is MANDATORY. There is deliberately no
 *   caller flag to disable it — a previous version exposed
 *   `requireHardwareBiometric=false`, a public bypass; it is removed.
 * - m=1 is rejected: 1-of-n "sharing" is the secret itself, not sharing.
 * - The threshold is EXPLICIT at reconstruction. Fewer shares than the
 *   threshold fail loudly instead of silently returning a wrong value
 *   (the old code interpolated anyway and returned garbage).
 *
 * TRUST BOUNDARY: share CATEGORY labels are self-asserted by whoever
 * supplies the shares — a software share can claim HARDWARE_BIOMETRIC.
 * Authenticating categories (hardware attestation binding a share's x to
 * a secure element) and verifiable secret sharing (Feldman/VSS
 * commitments, so malicious shares cannot steer reconstruction) REQUIRE
 * real cryptographic adapters. Until those exist, this module checks the
 * mathematical validity of the shares it is given, never the authenticity
 * of their provenance.
 */
class ShamirMfa(private val prime: BigInteger = DEFAULT_PRIME) {

    enum class FactorCategory { HARDWARE_BIOMETRIC, SOFT_BIOMETRIC, KNOWLEDGE }

    data class Share(val x: BigInteger, val y: BigInteger, val category: FactorCategory)

    private val random = SecureRandom()

    fun split(secret: BigInteger, m: Int, n: Int, categories: List<FactorCategory>): List<Share> {
        require(m in 2..n) { "threshold m must be in 2..n (m=1 is not secret sharing)" }
        require(categories.size == n) { "must supply exactly n categories" }
        require(secret >= BigInteger.ZERO && secret < prime) { "secret must be in [0, prime)" }

        val coefficients = mutableListOf(secret)
        repeat(m - 1) {
            coefficients.add(BigInteger(prime.bitLength(), random).mod(prime))
        }

        return (1..n).map { x ->
            val xi = BigInteger.valueOf(x.toLong())
            var yi = BigInteger.ZERO
            for ((power, coeff) in coefficients.withIndex()) {
                yi = yi.add(coeff.multiply(xi.pow(power))).mod(prime)
            }
            Share(xi, yi, categories[x - 1])
        }
    }

    /**
     * Reconstruct the secret from [shares], which must be at least
     * [threshold] in number. Every share is validated before interpolation;
     * at least one must carry HARDWARE_BIOMETRIC (no bypass).
     */
    fun reconstruct(shares: List<Share>, threshold: Int): BigInteger {
        require(threshold >= 2) { "threshold must be >= 2" }
        require(shares.size >= threshold) {
            "insufficient shares: have ${shares.size}, need $threshold"
        }
        // Fail-closed share validation BEFORE any field arithmetic.
        val xs = shares.map { it.x }
        require(xs.all { it > BigInteger.ZERO && it < prime }) {
            "share x out of range [1, prime)"
        }
        require(xs.toSet().size == xs.size) { "duplicate share x coordinates" }
        require(shares.all { it.y >= BigInteger.ZERO && it.y < prime }) {
            "share y out of field [0, prime)"
        }
        // Hardware binding is mandatory — no bypass flag exists.
        require(shares.any { it.category == FactorCategory.HARDWARE_BIOMETRIC }) {
            "reconstruction requires at least one HARDWARE_BIOMETRIC share"
        }

        var secret = BigInteger.ZERO
        for (i in shares.indices) {
            var numerator = BigInteger.ONE
            var denominator = BigInteger.ONE
            for (j in shares.indices) {
                if (i == j) continue
                numerator = numerator.multiply(shares[j].x.negate()).mod(prime)
                denominator = denominator.multiply(shares[i].x.subtract(shares[j].x)).mod(prime)
            }
            val lagrangeCoeff = numerator.multiply(denominator.modInverse(prime)).mod(prime)
            secret = secret.add(shares[i].y.multiply(lagrangeCoeff)).mod(prime)
        }
        return secret
    }

    companion object {
        val DEFAULT_PRIME: BigInteger = BigInteger.TWO.pow(521).subtract(BigInteger.ONE)
    }
}
