package guardianlink.integrity

object DeviceIntegrityTier {
    enum class Level { VERIFIED, DEGRADED, COMPROMISED, UNKNOWN }

    data class Signal(val name: String, val passed: Boolean)

    fun evaluate(signals: List<Signal>): Level {
        if (signals.isEmpty()) return Level.UNKNOWN

        val failed = signals.filter { !it.passed }
        return when {
            failed.isEmpty() -> Level.VERIFIED
            failed.size < signals.size -> Level.DEGRADED
            else -> Level.COMPROMISED
        }
    }

    fun overrideEligible(level: Level): Boolean =
        level == Level.VERIFIED || level == Level.DEGRADED
}