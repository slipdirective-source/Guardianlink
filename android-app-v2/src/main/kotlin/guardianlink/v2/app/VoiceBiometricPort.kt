package guardianlink.v2.app

import guardianlink.voice.AudioSample
import guardianlink.voice.BiometricVerifier
import guardianlink.voice.VerificationResult
import guardianlink.voice.VoiceTemplate

/**
 * The Gate 7 voice port (theta_voice) for this build.
 *
 * Wiring, honestly graded:
 *  - The enrolled template (if any) is loaded from [VoiceTemplateStore]
 *    — the scaffold's enrollment/template-store pattern, ported.
 *  - The real verifier behind this port is the engine's
 *    GmmUbmVoiceVerifier, which needs a UBM trained on a background
 *    speaker population. NO such corpus is deployed in this build, and
 *    the scaffold's substitute (a UBM trained on SYNTHETIC speakers) is
 *    a demo bootstrap this module does not inherit. So [delegate] is
 *    null in production wiring today.
 *  - Consequence, by design: with no delegate, [verify] can never
 *    ACCEPT. With no enrolled template it reports NOT_CHECKED; with a
 *    template but no verifier it reports NOT_LIVE (a ceremony ran and
 *    could not establish liveness). Either way Gate 7 halts on
 *    theta_voice with the reason in the outcome — voice evidence is
 *    ABSENT, and enrollment + a real UBM are the named next steps.
 *  - [enroll] without a delegate throws: enrollment is not offered
 *    until a verifier exists to enroll against. Nothing here creates
 *    a template that merely satisfies a type.
 *
 * When a real UBM ships, this class takes the constructed
 * GmmUbmVoiceVerifier as [delegate] and nothing else changes.
 */
class VoiceBiometricPort(
    private val enrolledTemplate: VoiceTemplate?,
    private val delegate: BiometricVerifier?,
) : BiometricVerifier {

    override fun enroll(samples: List<AudioSample>): VoiceTemplate =
        delegate?.enroll(samples)
            ?: throw UnsupportedOperationException(
                "voice enrollment is not available in this build: no voice verifier is deployed (no UBM background corpus), " +
                    "so no template can be created. Enrollment is the named next step for Gate 7 voice evidence.",
            )

    override fun verify(sample: AudioSample, template: VoiceTemplate): VerificationResult {
        val d = delegate
        if (enrolledTemplate == null || d == null) {
            return VerificationResult(
                score = 0.0,
                decision = VerificationResult.Decision.REJECT,
                liveness = if (enrolledTemplate == null) {
                    VerificationResult.Liveness.NOT_CHECKED
                } else {
                    VerificationResult.Liveness.NOT_LIVE
                },
            )
        }
        return d.verify(sample, template)
    }
}
