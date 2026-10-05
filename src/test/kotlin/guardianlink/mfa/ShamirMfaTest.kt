package guardianlink.mfa

import org.junit.jupiter.api.Test
import java.math.BigInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class ShamirMfaTest {
    private val mfa = ShamirMfa()

    @Test
    fun testSplitAndReconstruct() {
        val secret = BigInteger("12345")
        val shares = mfa.split(
            secret = secret,
            m = 2,
            n = 3,
            categories = listOf(
                ShamirMfa.FactorCategory.HARDWARE_BIOMETRIC,
                ShamirMfa.FactorCategory.SOFT_BIOMETRIC,
                ShamirMfa.FactorCategory.KNOWLEDGE
            )
        )

        assertEquals(3, shares.size)
        val reconstructed = mfa.reconstruct(shares.take(2))
        assertEquals(secret, reconstructed)
    }

    @Test
    fun testReconstructionRequiresThreshold() {
        val secret = BigInteger("99999")
        val shares = mfa.split(
            secret = secret,
            m = 3,
            n = 5,
            categories = listOf(
                ShamirMfa.FactorCategory.HARDWARE_BIOMETRIC,
                ShamirMfa.FactorCategory.SOFT_BIOMETRIC,
                ShamirMfa.FactorCategory.KNOWLEDGE,
                ShamirMfa.FactorCategory.KNOWLEDGE,
                ShamirMfa.FactorCategory.SOFT_BIOMETRIC
            )
        )

        // 2 shares shouldn't be enough (threshold is 3)
        val reconstructed2 = mfa.reconstruct(shares.take(2))
        assertNotEquals(secret, reconstructed2)  // wrong answer with insufficient shares
        
        // 3 shares should work
        val reconstructed3 = mfa.reconstruct(shares.take(3))
        assertEquals(secret, reconstructed3)
    }

    @Test
    fun testHardwareBiometricRequired() {
        val secret = BigInteger("11111")
        val shares = mfa.split(
            secret = secret,
            m = 2,
            n = 3,
            categories = listOf(
                ShamirMfa.FactorCategory.HARDWARE_BIOMETRIC,
                ShamirMfa.FactorCategory.SOFT_BIOMETRIC,
                ShamirMfa.FactorCategory.SOFT_BIOMETRIC
            )
        )

        // Should succeed with HARDWARE_BIOMETRIC
        val reconstructed1 = mfa.reconstruct(listOf(shares[0], shares[1]))
        assertEquals(secret, reconstructed1)
        
        // Should fail without HARDWARE_BIOMETRIC
        assertFailsWith<IllegalArgumentException> {
            mfa.reconstruct(listOf(shares[1], shares[2]))
        }
    }

    @Test
    fun testReconstructWithoutHardwareBiometricThrows() {
        val secret = BigInteger("22222")
        val shares = mfa.split(
            secret = secret,
            m = 2,
            n = 2,
            categories = listOf(
                ShamirMfa.FactorCategory.SOFT_BIOMETRIC,
                ShamirMfa.FactorCategory.KNOWLEDGE
            )
        )

        assertFailsWith<IllegalArgumentException> {
            mfa.reconstruct(shares)  // should fail — no HARDWARE_BIOMETRIC
        }
    }

    @Test
    fun testReconstructWithoutGuardSucceeds() {
        val secret = BigInteger("33333")
        val shares = mfa.split(
            secret = secret,
            m = 2,
            n = 2,
            categories = listOf(
                ShamirMfa.FactorCategory.SOFT_BIOMETRIC,
                ShamirMfa.FactorCategory.KNOWLEDGE
            )
        )

        // Bypassing the guard
        val reconstructed = mfa.reconstruct(shares, requireHardwareBiometric = false)
        assertEquals(secret, reconstructed)
    }

    @Test
    fun testSecretSmallerthanPrime() {
        val secret = BigInteger("1")
        val shares = mfa.split(
            secret = secret,
            m = 1,
            n = 1,
            categories = listOf(ShamirMfa.FactorCategory.HARDWARE_BIOMETRIC)
        )
        
        val reconstructed = mfa.reconstruct(shares)
        assertEquals(secret, reconstructed)
    }

    @Test
    fun testSecretLargerThanPrimeThrows() {
        val largeSecret = ShamirMfa.DEFAULT_PRIME.add(BigInteger("1"))
        assertFailsWith<IllegalArgumentException> {
            mfa.split(
                secret = largeSecret,
                m = 1,
                n = 1,
                categories = listOf(ShamirMfa.FactorCategory.HARDWARE_BIOMETRIC)
            )
        }
    }
}
