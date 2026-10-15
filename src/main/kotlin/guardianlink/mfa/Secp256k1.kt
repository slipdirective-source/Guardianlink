package guardianlink.mfa

import java.math.BigInteger

/**
 * Minimal secp256k1 arithmetic (pure Kotlin, no dependency) for Feldman
 * verifiable secret sharing.
 *
 * Only what VSS needs is implemented: point validation, addition,
 * and scalar multiplication. This is NOT a general-purpose ECC library
 * — do not reuse it for signatures or key exchange without a full
 * audit (no constant-time guarantees, no cofactor handling beyond the
 * prime-order group).
 */
object Secp256k1 {
    /** Field prime p. */
    val P: BigInteger =
        BigInteger("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEFFFFFC2F", 16)

    /** Group order n (prime). Scalar field for VSS shares. */
    val N: BigInteger =
        BigInteger("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141", 16)

    /** Generator G. */
    val G: Point = Point(
        BigInteger("79BE667EF9DCBBAC55A06295CE870B07029BFCDB2DCE28D959F2815B16F81798", 16),
        BigInteger("483ADA7726A3C4655DA4FBFC0E1108A8FD17B448A68554199C47D08FFB10D4B8", 16),
    )

    /** Affine point; the point at infinity is represented as null. */
    data class Point(val x: BigInteger, val y: BigInteger)

    fun isOnCurve(p: Point): Boolean {
        if (p.x < BigInteger.ZERO || p.x >= P || p.y < BigInteger.ZERO || p.y >= P) return false
        // y^2 == x^3 + 7 (mod p)
        val lhs = p.y.modPow(BigInteger.TWO, P)
        val rhs = p.x.modPow(BigInteger("3"), P).add(BigInteger.valueOf(7)).mod(P)
        return lhs == rhs
    }

    fun add(p: Point?, q: Point?): Point? {
        if (p == null) return q
        if (q == null) return p
        if (p.x == q.x) {
            // P == -Q -> infinity; P == Q -> double.
            if (p.y.add(q.y).mod(P) == BigInteger.ZERO) return null
            return double(p)
        }
        val lambda = q.y.subtract(p.y).mod(P)
            .multiply(q.x.subtract(p.x).mod(P).modInverse(P)).mod(P)
        val x3 = lambda.modPow(BigInteger.TWO, P).subtract(p.x).subtract(q.x).mod(P)
        val y3 = lambda.multiply(p.x.subtract(x3)).subtract(p.y).mod(P)
        return Point(x3, y3)
    }

    fun double(p: Point): Point? {
        if (p.y == BigInteger.ZERO) return null
        val lambda = p.x.modPow(BigInteger.TWO, P).multiply(BigInteger.valueOf(3)).mod(P)
            .multiply(p.y.multiply(BigInteger.TWO).mod(P).modInverse(P)).mod(P)
        val x3 = lambda.modPow(BigInteger.TWO, P).subtract(p.x.multiply(BigInteger.TWO)).mod(P)
        val y3 = lambda.multiply(p.x.subtract(x3)).subtract(p.y).mod(P)
        return Point(x3, y3)
    }

    /**
     * Scalar multiplication k*P, double-and-add. k is reduced mod N.
     * Returns null for k ≡ 0 (mod N).
     */
    fun multiply(k: BigInteger, p: Point): Point? {
        var scalar = k.mod(N)
        var result: Point? = null
        var base: Point? = p
        while (scalar > BigInteger.ZERO) {
            if (scalar.testBit(0)) result = add(result, base)
            base = if (base == null) null else double(base)
            scalar = scalar.shiftRight(1)
        }
        return result
    }
}
