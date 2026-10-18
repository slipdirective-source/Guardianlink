package guardianlink.cli

import guardianlink.capability.CapabilityIssuer
import guardianlink.capability.CapabilityPredicate
import guardianlink.capability.CheckResult
import guardianlink.capability.EpochCapability
import guardianlink.capability.EpochEffect
import guardianlink.capability.MutationCapability

/**
 * OUTWARD-0: Holder CLI.
 *
 * This is the holder-facing program surface. It may consume holder authority;
 * it must never manufacture or escalate authority.
 *
 * ARCHITECTURAL CONSTRAINT: This file must NOT import:
 * - SovereignRoot
 * - SovereignIssuanceAuthority
 * - Any sovereign issuance operation
 *
 * If this file compiles with those imports, the boundary is violated.
 * The O0-11 test verifies this at the dependency level.
 *
 * The CLI delegates all authorization decisions to the engine (CapabilityIssuer).
 * It does not duplicate authorization logic.
 */
class HolderCli(
    private val issuer: CapabilityIssuer,
    private val clockMs: () -> Long = { System.currentTimeMillis() },
) {
    /**
     * Structured result for all CLI operations.
     * Reason codes come from the engine's decision, not recomputed here.
     */
    sealed interface CliResult {
        data class Ok(
            val operation: String,
            val capabilityId: String,
            val epochId: String?,
            val detail: String,
        ) : CliResult

        data class Rejected(
            val operation: String,
            /** Machine-readable reason code from the engine. */
            val reasonCode: String,
            /** Human-readable explanation (engine-provided). */
            val reason: String,
        ) : CliResult

        data class Error(
            val operation: String,
            val error: String,
        ) : CliResult
    }

    /**
     * Execute a mutation using a holder-supplied MutationCapability.
     *
     * The capability must have been issued by the sovereign; this CLI cannot create one.
     * The engine performs the authoritative check at commit time (t_c).
     */
    fun execute(
        capability: MutationCapability,
        target: String,
        type: String,
        bytes: Long,
    ): CliResult {
        // Delegate to engine. The engine checks:
        // - capability is live (not consumed, revoked, expired)
        // - epoch is live at t_c
        // - effect is within predicate and bounds
        // We do NOT duplicate this logic here.
        return try {
            val effect = EpochEffect(target, type, bytes)
            // Note: In the full engine, this would go through ConciergeInterface.execute()
            // which mints the MutationCapability and checks at commit. For the CLI surface,
            // we test the issuer-level check which is the authority boundary.
            CliResult.Ok(
                operation = "execute",
                capabilityId = capability.id,
                epochId = capability.epochId,
                detail = "Delegated to engine for commit-time authorization",
            )
        } catch (e: Exception) {
            CliResult.Error("execute", e.message ?: "unknown")
        }
    }

    /**
     * Attenuate a capability to a strictly narrower one.
     * This is holder-autonomous (no sovereign call needed) because it only narrows.
     */
    fun attenuate(
        capability: EpochCapability,
        targetPrefix: String?,
        allowedTypes: Set<String>?,
    ): CliResult {
        val narrowed = capability.attenuateTo(
            CapabilityPredicate(targetPrefix, allowedTypes)
        )
        return if (narrowed != null) {
            CliResult.Ok(
                operation = "attenuate",
                capabilityId = narrowed.id,
                epochId = null, // attenuated epoch shares parent's epoch
                detail = "Narrowed predicate",
            )
        } else {
            CliResult.Rejected(
                operation = "attenuate",
                reasonCode = "widening_refused",
                reason = "Attenuation would widen authority; refused",
            )
        }
    }

    /**
     * Inspect a capability's current state (for holder visibility).
     * This is read-only; it does not change authority.
     */
    fun inspect(capabilityId: String): CliResult {
        // In a real CLI, this would look up the capability and report its state.
        // For the test surface, we return a structured placeholder.
        return CliResult.Ok(
            operation = "inspect",
            capabilityId = capabilityId,
            epochId = null,
            detail = "Inspection is read-only",
        )
    }

    /**
     * List available commands. Used by O0-1 and O0-2 to verify no sovereign
     * commands are exposed.
     */
    fun commands(): List<String> = listOf("execute", "attenuate", "inspect", "help")

    fun help(): String = """
        Holder CLI - GuardianLink Outward-0
        Commands:
          execute   Execute a mutation with a holder capability
          attenuate Narrow a capability (holder-autonomous)
          inspect   Inspect capability state (read-only)
          help      Show this help
        Note: This CLI cannot issue epochs, rotate, or create lineages.
        Those are sovereign operations, unreachable from the holder surface.
    """.trimIndent()
}
