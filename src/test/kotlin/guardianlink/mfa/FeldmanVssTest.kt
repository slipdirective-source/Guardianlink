package guardianlink.mfa

import org.junit.jupiter.api.Test
import java.math.BigInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Feldman VSS over secp256k1: commitments bind shares to the dealt
 * polynomial; corrupt or malicious shares are detectable before
 * reconstruction and must be excluded.
 */
class FeldmanVssTest {

    private val categories = listOf(
        ShamirMfa.FactorCategory.HARDWARE_BIOMETRIC,
        ShamirMfa.FactorCategory.SOFT_BIOMETRIC,
        ShamirMfa.FactorCategory.KNOWLEDGE,
        ShamirMfa.FactorCategory.KNOWLEDGE,
    )

    @Test
    fun `curve sanity - G is on curve and N times G is infinity`() {
        assertTrue(Secp256k1.isOnCurve(Secp256k1.G))
        assertNull(Secp256k1.multiply(Secp256k1.N, Secp256k1.G))
        // 1*G == G, 2*G is on curve and != G.
        assertEquals(Secp256k1.G, Secp256k1.multiply(BigInteger.ONE, Secp256k1.G))
        val twoG = Secp256k1.multiply(BigInteger.TWO, Secp256k1.G)
        assertNotNull(twoG)
        assertTrue(Secp256k1.isOnCurve(twoG))
    }

    @Test
    fun `dealt shares all verify against the commitments`() {
        val dealt = FeldmanVss.deal(BigInteger.valueOf(42), 2, 4, categories)
        assertNotNull(dealt)
        assertEquals(4, dealt.shares.size)
        assertEquals(2, dealt.commitments.size) // m coefficients -> m commitments
        for (s in dealt.shares) {
            assertTrue(FeldmanVss.verifyShare(dealt.commitments, s.x, s.y), "share x=${s.x} must verify")
        }
    }

    @Test
    fun `tampered share fails verification`() {
        val dealt = FeldmanVss.deal(BigInteger.valueOf(42), 2, 4, categories)!!
        val victim = dealt.shares[0]
        val tampered = victim.copy(y = victim.y.add(BigInteger.ONE).mod(Secp256k1.N))
        assertFalse(FeldmanVss.verifyShare(dealt.commitments, tampered.x, tampered.y))
        // And the untouched share still verifies.
        assertTrue(FeldmanVss.verifyShare(dealt.commitments, victim.x, victim.y))
    }

    @Test
    fun `corrupted commitment fails verification`() {
        val dealt = FeldmanVss.deal(BigInteger.valueOf(42), 3, 4, categories)!!
        val evil = dealt.commitments.toMutableList()
        // Attacker replaces C1 with an unrelated on-curve point: it passes
        // the on-curve gate, but shares no longer match the polynomial.
        // (Note: merely PERMUTING commitments is not a corruption — at x=1
        // every permutation verifies, since 1^j = 1 for all j.)
        evil[1] = Secp256k1.multiply(BigInteger.valueOf(999983), Secp256k1.G)
        for (s in dealt.shares) {
            assertFalse(FeldmanVss.verifyShare(evil, s.x, s.y))
        }
    }

    @Test
    fun `off-curve commitment is rejected before arithmetic`() {
        val dealt = FeldmanVss.deal(BigInteger.valueOf(42), 2, 4, categories)!!
        val evil = dealt.commitments.toMutableList()
        val c0 = dealt.commitments.first { it != null }!!
        // Valid coordinates format but not on the curve (x+1 is overwhelmingly not on curve).
        val notOnCurve = Secp256k1.Point(
            c0.x.add(BigInteger.ONE).mod(Secp256k1.P),
            c0.y,
        )
        assertFalse(Secp256k1.isOnCurve(notOnCurve))
        evil[0] = notOnCurve
        assertFalse(FeldmanVss.verifyShare(evil, dealt.shares[0].x, dealt.shares[0].y))
    }

    @Test
    fun `degenerate inputs fail closed`() {
        val dealt = FeldmanVss.deal(BigInteger.valueOf(42), 2, 4, categories)!!
        assertFalse(FeldmanVss.verifyShare(emptyList(), BigInteger.ONE, BigInteger.ONE))
        assertFalse(FeldmanVss.verifyShare(dealt.commitments, BigInteger.ZERO, BigInteger.ONE))
        assertFalse(FeldmanVss.verifyShare(dealt.commitments, Secp256k1.N, BigInteger.ONE))
        assertFalse(FeldmanVss.verifyShare(dealt.commitments, BigInteger.ONE, Secp256k1.N))
        // Malformed deal parameters return null, never throw.
        assertNull(FeldmanVss.deal(BigInteger.valueOf(-1), 2, 4, categories))
        assertNull(FeldmanVss.deal(BigInteger.valueOf(42), 1, 4, categories))
    }

    @Test
    fun `verified subset reconstructs the secret and excludes the malicious share`() {
        val dealt = FeldmanVss.deal(BigInteger.valueOf(1234), 2, 4, categories)!!
        val shamir = ShamirMfa(Secp256k1.N)
        // Adversary corrupts one share (index 2 — keep the
        // HARDWARE_BIOMETRIC share index 0 clean so reconstruction's
        // mandatory-hardware rule is satisfiable).
        val corrupted = dealt.shares.mapIndexed { i, s ->
            if (i == 2) s.copy(y = s.y.add(BigInteger.valueOf(7)).mod(Secp256k1.N)) else s
        }
        val clean = FeldmanVss.verifiedSubset(dealt.commitments, corrupted)
        assertEquals(3, clean.size)
        // 2-of-4: the remaining clean shares still reconstruct.
        val recovered = shamir.reconstruct(clean.take(2), threshold = 2)
        assertEquals(BigInteger.valueOf(1234), recovered)
    }
}
