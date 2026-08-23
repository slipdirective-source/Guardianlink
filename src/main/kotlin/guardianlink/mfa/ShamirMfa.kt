package guardianlink.mfa

import java.math.BigInteger
import java.security.SecureRandom

class ShamirMfa(private val prime: BigInteger = DEFAULT_PRIME) {

    enum class FactorCategory { HARDWARE_BIOMETRIC, SOFT_BIOMETRIC, KNOWLEDGE }

    data class Share(val x: BigInteger, val y: BigInteger, val category: FactorCategory)

    private val random = SecureRandom()

    fun split(secret: BigInteger, m: Int, n: Int, categories: List<FactorCategory>): List<Share> {
        require(m in 1..n) { "threshold m must be between 1 and n" }
        require(categories.size == n) { "must supply exactly n categories" }
        require(secret < prime) { "secret must be smaller than the field prime" }

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

    fun reconstruct(shares: List<Share>, requireHardwareBiometric: Boolean = true): BigInteger {
        require(shares.isNotEmpty()) { "need at least one share" }
        if (requireHardwareBiometric) {
            require(shares.any { it.category == FactorCategory.HARDWARE_BIOMETRIC }) {
                "reconstruction requires at least one HARDWARE_BIOMETRIC share"
            }
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