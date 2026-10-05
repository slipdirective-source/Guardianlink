package guardianlink.mfa

import org.junit.jupiter.api.Test
import java.math.BigInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ShamirMfaTest {
    private val mfa = ShamirMfa()
    private val HW = ShamirMfa.FactorCategory.HARDWARE_BIOMETRIC
    private val SW = ShamirMfa.FactorCategory.SOFT_BIOMETRIC
    private val KN = ShamirMfa.FactorCategory.KNOWLEDGE

    private fun split3(secret: BigInteger, m: Int = 2): List<ShamirMfa.Share> =
        mfa.split(secret = secret, m = m, n = 3, categories = listOf(HW, SW, KN))

    @Test
    fun testSplitAndReconstruct() {
        val secret = BigInteger("12345")
        val shares = split3(secret)
        assertEquals(3, shares.size)
        val reconstructed = mfa.reconstruct(shares.take(2), threshold = 2)
        assertEquals(secret, reconstructed)
    }

    @Test
    fun testInsufficientSharesFailLoudly() {
        val secret = BigInteger("99999")
        val shares = mfa.split(
            secret = secret, m = 3, n = 5,
            categories = listOf(HW, SW, KN, KN, SW)
        )
        // Fewer than threshold must FAIL, not silently return a wrong value.
        assertFailsWith<IllegalArgumentException> {
            mfa.reconstruct(shares.take(2), threshold = 3)
        }
        val reconstructed3 = mfa.reconstruct(shares.take(3), threshold = 3)
        assertEquals(secret, reconstructed3)
    }

    @Test
    fun testHardwareBiometricRequired() {
        val secret = BigInteger("11111")
        val shares = split3(secret)
        val reconstructed1 = mfa.reconstruct(listOf(shares[0], shares[1]), threshold = 2)
        assertEquals(secret, reconstructed1)
        // No HARDWARE_BIOMETRIC share present: must fail, and there is no
        // bypass flag to flip.
        assertFailsWith<IllegalArgumentException> {
            mfa.reconstruct(listOf(shares[1], shares[2]), threshold = 2)
        }
    }

    @Test
    fun testReconstructWithoutHardwareBiometricThrows() {
        val shares = mfa.split(
            secret = BigInteger("22222"), m = 2, n = 2,
            categories = listOf(SW, KN)
        )
        assertFailsWith<IllegalArgumentException> {
            mfa.reconstruct(shares, threshold = 2)
        }
    }

    @Test
    fun testHardwareBypassIsGone() {
        // The old requireHardwareBiometric=false bypass no longer exists:
        // reconstruct's signature is (shares, threshold) only, and hardware
        // binding is unconditional.
        val shares = mfa.split(
            secret = BigInteger("33333"), m = 2, n = 2,
            categories = listOf(SW, KN)
        )
        assertFailsWith<IllegalArgumentException> {
            mfa.reconstruct(shares, threshold = 2)
        }
    }

    @Test
    fun testThresholdOneRejected() {
        assertFailsWith<IllegalArgumentException> {
            mfa.split(secret = BigInteger("1"), m = 1, n = 2, categories = listOf(HW, HW))
        }
        val shares = split3(BigInteger("7"))
        assertFailsWith<IllegalArgumentException> {
            mfa.reconstruct(shares.take(1), threshold = 1)
        }
    }

    @Test
    fun testSecretOutOfFieldThrows() {
        assertFailsWith<IllegalArgumentException> {
            mfa.split(
                secret = ShamirMfa.DEFAULT_PRIME.add(BigInteger.ONE), m = 2, n = 2,
                categories = listOf(HW, HW)
            )
        }
        assertFailsWith<IllegalArgumentException> {
            mfa.split(
                secret = BigInteger.ONE.negate(), m = 2, n = 2,
                categories = listOf(HW, HW)
            )
        }
    }

    // ------------------------------------------------------------------
    // Adversarial share validation (CRITICAL: x=0 injection).
    // ------------------------------------------------------------------

    @Test
    fun testXZeroShareInjectionRejected() {
        // An x=0 share forces Lagrange-at-0 to return its own y: the
        // attacker dictates the "secret". Must be rejected, not computed.
        val shares = split3(BigInteger("424242"))
        val evil = ShamirMfa.Share(BigInteger.ZERO, BigInteger("999999"), HW)
        assertFailsWith<IllegalArgumentException> {
            mfa.reconstruct(listOf(shares[0], evil), threshold = 2)
        }
    }

    @Test
    fun testDuplicateXRejected() {
        val shares = split3(BigInteger("555"))
        val dup = shares[0].copy()
        assertFailsWith<IllegalArgumentException> {
            mfa.reconstruct(listOf(shares[0], dup), threshold = 2)
        }
    }

    @Test
    fun testOutOfFieldSharesRejected() {
        val shares = split3(BigInteger("666"))
        // x == prime
        assertFailsWith<IllegalArgumentException> {
            mfa.reconstruct(listOf(shares[0].copy(x = ShamirMfa.DEFAULT_PRIME), shares[1]), threshold = 2)
        }
        // negative x
        assertFailsWith<IllegalArgumentException> {
            mfa.reconstruct(listOf(shares[0].copy(x = BigInteger.ONE.negate()), shares[1]), threshold = 2)
        }
        // y == prime
        assertFailsWith<IllegalArgumentException> {
            mfa.reconstruct(listOf(shares[0].copy(y = ShamirMfa.DEFAULT_PRIME), shares[1]), threshold = 2)
        }
        // negative y
        assertFailsWith<IllegalArgumentException> {
            mfa.reconstruct(listOf(shares[0].copy(y = BigInteger.ONE.negate()), shares[1]), threshold = 2)
        }
    }

    @Test
    fun testWrongThresholdFails() {
        // Shares split at m=3 presented with threshold=2: the caller must
        // state the TRUE threshold; understating it is a policy violation.
        val secret = BigInteger("777")
        val shares = mfa.split(
            secret = secret, m = 3, n = 5,
            categories = listOf(HW, SW, KN, KN, SW)
        )
        assertFailsWith<IllegalArgumentException> {
            mfa.reconstruct(shares.take(2), threshold = 3)
        }
        // And the true threshold still reconstructs.
        assertEquals(secret, mfa.reconstruct(shares.take(3), threshold = 3))
    }

    @Test
    fun testExtraSharesStillReconstruct() {
        val secret = BigInteger("888")
        val shares = mfa.split(
            secret = secret, m = 2, n = 5,
            categories = listOf(HW, SW, KN, KN, SW)
        )
        // More than threshold: all points lie on the polynomial, still valid.
        assertEquals(secret, mfa.reconstruct(shares, threshold = 2))
    }
}
