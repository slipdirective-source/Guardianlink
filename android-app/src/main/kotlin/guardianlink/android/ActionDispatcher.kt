package guardianlink.android

import android.util.Base64
import guardianlink.core.ConciergeInterface
import guardianlink.gates.Action
import guardianlink.gates.MonotonicClock
import guardianlink.gates.Rails
import guardianlink.gates.render
import guardianlink.gates.validateAction
import java.util.concurrent.atomic.AtomicLong

/**
 * The sole writer. Every mutation the device performs — TUN packets,
 * settings, keystore, files — is modeled as an [Action] and executed
 * here, through [ConciergeInterface.execute], through the NineGates.
 * There is no second write path: audit this by grep, not by promise.
 *
 * [ConciergeInterface] is not thread-safe; all submissions serialize
 * on this dispatcher.
 *
 * Evidence (signal, keys, assent, audio, vectors) is caller-supplied;
 * instruments (clock, ledger, revocation feed, enrolled voice template)
 * stay engine-side and are never taken from the request.
 */
class ActionDispatcher(
    private val concierge: ConciergeInterface,
    private val clock: MonotonicClock,
    private val evidenceSupplier: EvidenceSupplier,
) {
    private val actionSeq = AtomicLong(0)

    /**
     * Submit any action. Actions that do not validate into A_total are
     * rejected before they reach the engine.
     */
    @Synchronized
    fun submit(action: Action): ConciergeInterface.ExecutionResult {
        require(validateAction(action, Rails())) { "action not in A_total" }
        val actionId = "a-${clock.nowMs()}-${actionSeq.incrementAndGet()}"
        val evidence = evidenceSupplier.evidenceFor(action, actionId, render(action))
        return concierge.execute(action, evidence)
    }

    /**
     * TUN packet forward, per docs/android-integration.md: the network is
     * a substrate, so a packet is Write("net/<dst>", payload).
     *
     * NOTE (doc drift): the integration doc writes this as
     * Write(path, value); the engine's Action AST is
     * Write(recordId, fields: Map<String, String>), which is what is
     * constructed here.
     */
    @Synchronized
    fun submitTunPacket(dstIp: String, packet: ByteArray): ConciergeInterface.ExecutionResult {
        val payload = Base64.encodeToString(packet, Base64.NO_WRAP)
        return submit(Action.Write("net/$dstIp", mapOf("payload" to payload)))
    }

    fun currentSubstrate() = concierge.currentSubstrate()
}

/**
 * Builds per-action evidence for the dispatcher. The Android
 * implementation runs the on-device assent ceremony (rendering shown
 * to the user, voice challenge over the mic) and binds the result
 * into [ConciergeInterface.ActionEvidence].
 */
fun interface EvidenceSupplier {
    fun evidenceFor(
        action: Action,
        actionId: String,
        rendering: String,
    ): ConciergeInterface.ActionEvidence
}
