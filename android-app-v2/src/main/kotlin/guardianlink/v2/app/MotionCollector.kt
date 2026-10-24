package guardianlink.v2.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import kotlin.math.sqrt

/** Motion states the classifier can be in. Transitions are the evidence; steady states are not re-reported. */
enum class MotionState {
    STILL,
    MOVING,
}

/**
 * Pure still/moving classifier over linear-acceleration magnitudes
 * (gravity already removed by the caller). A sliding window of the
 * most recent [windowSize] samples votes: when at least
 * [activeFraction] of the window exceeds [activeThreshold], the device
 * is moving. A transition is returned exactly once, when the voted
 * state changes. No `android.*` types — JVM-testable.
 */
class MotionClassifier(
    private val windowSize: Int = 8,
    private val activeThreshold: Double = 1.2,
    private val activeFraction: Double = 0.5,
) {
    var state: MotionState = MotionState.STILL
        private set

    private val window = ArrayDeque<Boolean>()

    /** Feed one linear-acceleration magnitude (m/s²). Returns the new state on a transition, else null. */
    fun onSample(linearMagnitude: Double): MotionState? {
        window.addLast(linearMagnitude > activeThreshold)
        if (window.size > windowSize) window.removeFirst()
        if (window.size < windowSize) return null
        val activeShare = window.count { it }.toDouble() / window.size.toDouble()
        val target = if (activeShare >= activeFraction) MotionState.MOVING else MotionState.STILL
        if (target == state) return null
        state = target
        return state
    }
}

/**
 * Pure step-delta tracker over the step counter's cumulative
 * since-boot readings. The first reading only establishes a baseline;
 * a reading lower than its predecessor means the counter reset
 * (reboot) and also only re-baselines. Deltas accumulate until they
 * reach [emitThreshold], so the ledger hears about meaningful movement,
 * not every single step. No `android.*` types — JVM-testable.
 */
class StepDeltaTracker(private val emitThreshold: Long = 25) {
    private var lastTotal: Long? = null
    private var pending: Long = 0

    /** Feed a cumulative step reading. Returns the accumulated delta when it is worth emitting, else null. */
    fun onReading(totalSteps: Long): Long? {
        val last = lastTotal
        if (last == null) {
            lastTotal = totalSteps
            return null
        }
        if (totalSteps < last) {
            // Counter reset (device rebooted): re-baseline, claim nothing.
            lastTotal = totalSteps
            pending = 0
            return null
        }
        val delta = totalSteps - last
        lastTotal = totalSteps
        if (delta == 0L) return null
        pending += delta
        if (pending < emitThreshold) return null
        val out = pending
        pending = 0
        return out
    }
}

/**
 * Motion collector (framework [SensorManager] only — PORT-MAP §f
 * risk 5: no GMS Activity Recognition dependency).
 *
 * Two sensors:
 *  - Accelerometer, gravity removed with the standard low-pass filter,
 *    fed to [MotionClassifier]. Emits STILL/MOVING transitions only.
 *  - Step counter (when the device has one), fed to [StepDeltaTracker].
 *    Emits accumulated step deltas.
 *
 * Everything this collector emits is a DERIVED event: a classification
 * or a computed delta, labeled inference with a confidence by the sink
 * wiring in SensingService — never presented as a raw observation.
 * Sampling is SENSOR_DELAY_NORMAL (the slowest standard rate) and
 * listeners are registered/unregistered with the service lifecycle;
 * there is no high-rate continuous sampling.
 *
 * The whole collector is gated on ACTIVITY_RECOGNITION (API 29+), the
 * runtime grant §d assigns to motion/step sensing; without it the
 * collector reports itself unavailable and registers nothing.
 */
class MotionCollector(
    private val context: Context,
    private val sink: (topic: String, claim: String, confidence: Double) -> Unit,
    private val onUnavailable: (reason: String) -> Unit,
) {
    private var sensorManager: SensorManager? = null
    private var started = false

    private val classifier = MotionClassifier()
    private val stepTracker = StepDeltaTracker()

    // Low-pass gravity estimate (per axis), per the platform's own
    // sample-code pattern for isolating linear acceleration.
    private val gravity = floatArrayOf(0f, 0f, 0f)
    private var gravityPrimed = false

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            when (event.sensor.type) {
                Sensor.TYPE_ACCELEROMETER -> onAccelerometer(event.values)
                Sensor.TYPE_STEP_COUNTER -> onStepCounter(event.values)
            }
        }

        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
            // Noted, not acted on: a low-accuracy sensor degrades the
            // classifier's input, and the emitted confidence is already
            // modest. No silent correction is applied.
        }
    }

    /** True when the collector is live. False means [onUnavailable] was told why. */
    fun start(): Boolean {
        if (started) return true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACTIVITY_RECOGNITION) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            onUnavailable("activity recognition permission not granted")
            return false
        }
        val sm = context.getSystemService(SensorManager::class.java)
        val accelerometer = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val stepCounter = sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
        if (accelerometer == null && stepCounter == null) {
            onUnavailable("device has neither an accelerometer nor a step counter")
            return false
        }
        sensorManager = sm
        var registeredAny = false
        if (accelerometer != null) {
            registeredAny = sm.registerListener(listener, accelerometer, SensorManager.SENSOR_DELAY_NORMAL) || registeredAny
        }
        if (stepCounter != null) {
            registeredAny = sm.registerListener(listener, stepCounter, SensorManager.SENSOR_DELAY_NORMAL) || registeredAny
        }
        if (!registeredAny) {
            onUnavailable("sensor registration failed")
            return false
        }
        started = true
        Log.i(
            TAG,
            "motion collector started (accelerometer=${accelerometer != null}, stepCounter=${stepCounter != null})",
        )
        return true
    }

    fun stop() {
        if (!started) return
        started = false
        try {
            sensorManager?.unregisterListener(listener)
        } catch (e: Exception) {
            Log.w(TAG, "unregisterListener failed", e)
        }
        sensorManager = null
        gravityPrimed = false
        Log.i(TAG, "motion collector stopped")
    }

    private fun onAccelerometer(values: FloatArray) {
        if (values.size < 3) return
        if (!gravityPrimed) {
            gravity[0] = values[0]
            gravity[1] = values[1]
            gravity[2] = values[2]
            gravityPrimed = true
            return
        }
        val alpha = 0.8f
        gravity[0] = alpha * gravity[0] + (1 - alpha) * values[0]
        gravity[1] = alpha * gravity[1] + (1 - alpha) * values[1]
        gravity[2] = alpha * gravity[2] + (1 - alpha) * values[2]
        val lx = values[0] - gravity[0]
        val ly = values[1] - gravity[1]
        val lz = values[2] - gravity[2]
        val magnitude = sqrt((lx * lx + ly * ly + lz * lz).toDouble())
        val transition = classifier.onSample(magnitude) ?: return
        val claim = when (transition) {
            MotionState.MOVING -> "Motion: moving (accelerometer-derived)"
            MotionState.STILL -> "Motion: still (accelerometer-derived)"
        }
        sink("state.motion", claim, 0.7)
    }

    private fun onStepCounter(values: FloatArray) {
        if (values.isEmpty()) return
        val delta = stepTracker.onReading(values[0].toLong()) ?: return
        sink("state.steps", "Steps: +$delta since the previous step report (device step counter)", 0.75)
    }

    companion object {
        private const val TAG = "GlV2Motion"
    }
}
