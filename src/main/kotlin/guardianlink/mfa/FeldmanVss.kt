package guardianlink.mfa

import java.math.BigInteger

/**
 * Feldman verifiable secret sharing (VSS) over secp256k1.
 *
 * Closes the README's Shamir trust-boundary row: "malicious shares
 * cannot steer reconstruction" previously REQUIRED an adapter that did
 * not exist. Now it exists.
 *
 * Dealing: the dealer holds polynomial f(x) = a_0 + a_1 x + ... over
 * the scalar field F_N (N = secp256k1 group order) and publishes
 * commitments C_j = a_j * G. Anyone holding the commitments can check
 * a share (x, y) via y*G == Σ x^j * C_j — WITHOUT learning the secret.
 * A share that fails verification is corrupt or malicious and must be
 * excluded from reconstruction.
 *
 * Commitments are `Point?`: a zero coefficient commits to the point at
 * infinity (null). This is legitimate — including for a_0 when the
 * secret itself is zero — and the verifier handles it.
 *
 * What this does NOT do: it proves shares are CONSISTENT with the
 * committed polynomial, not that the dealer is honest about the secret
 * itself, and not that a share's claimed factor CATEGORY is true.
 * Category attestation (hardware binding) remains deployment scope —
 * see docs/production-deployment.md.
 *
 * Field note: VSS commitments live in a group of order N, so shares
 * must be dealt over F_N. Use ShamirMfa(Secp256k1.N) together with
 * [deal]; the default Mersenne-prime field is NOT VSS-compatible.
 */
object FeldmanVss {

    data class DealtShares(
        val shares: List<ShamirMfa.Share>,
        val commitments: List<Secp256k1.Point?>,
    )

    /**
     * Deals (m, n) shares of [secret] with Feldman commitments.
     * Returns null (never throws) if inputs are malformed.
     */
    fun deal(
        secret: BigInteger,
        m: Int,
        n: Int,
        categories: List<ShamirMfa.FactorCategory>,
    ): DealtShares? = try {
        require(secret >= BigInteger.ZERO && secret < Secp256k1.N) { "secret must be in [0, N)" }
        require(m in 2..n) { "threshold m must be in 2..n" }
        require(categories.size == n) { "must supply exactly n categories" }
        val shamir = ShamirMfa(Secp256k1.N)
        val (shares, coefficients) = shamir.splitWithCoefficients(secret, m, n, categories)
        DealtShares(shares, commit(coefficients))
    } catch (_: Exception) {
        null
    }

    /**
     * C_j = a_j * G for each polynomial coefficient (null = point at
     * infinity, for a zero coefficient). Never throws on valid input.
     */
    fun commit(coefficients: List<BigInteger>): List<Secp256k1.Point?> {
        require(coefficients.isNotEmpty()) { "need at least one coefficient" }
        require(coefficients.all { it >= BigInteger.ZERO && it < Secp256k1.N }) {
            "coefficients must be in [0, N)"
        }
        return coefficients.map { c -> Secp256k1.multiply(c, Secp256k1.G) }
    }

    /**
     * Verifies share (x, y) against [commitments]: y*G == Σ_j x^j * C_j.
     * Returns false — never throws — on any malformation OR mismatch.
     * Invalid-curve points are rejected before any arithmetic.
     */
    fun verifyShare(
        commitments: List<Secp256k1.Point?>,
        x: BigInteger,
        y: BigInteger,
    ): Boolean {
        if (commitments.isEmpty()) return false
        if (x <= BigInteger.ZERO || x >= Secp256k1.N) return false
        if (y < BigInteger.ZERO || y >= Secp256k1.N) return false
        if (!commitments.all { it == null || Secp256k1.isOnCurve(it) }) return false
        return try {
            val lhs = Secp256k1.multiply(y, Secp256k1.G) ?: return false
            var rhs: Secp256k1.Point? = null
            var xPow = BigInteger.ONE
            for (c in commitments) {
                // A null commitment is the identity: x^j * O = O, skip it.
                if (c != null) {
                    val term = Secp256k1.multiply(xPow, c) ?: return false
                    rhs = Secp256k1.add(rhs, term)
                }
                xPow = xPow.multiply(x).mod(Secp256k1.N)
            }
            lhs == rhs
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Filters [shares] to the subset verifying against [commitments].
     * Reconstruction MUST use only verified shares.
     */
    fun verifiedSubset(
        commitments: List<Secp256k1.Point?>,
        shares: List<ShamirMfa.Share>,
    ): List<ShamirMfa.Share> = shares.filter { verifyShare(commitments, it.x, it.y) }
}
