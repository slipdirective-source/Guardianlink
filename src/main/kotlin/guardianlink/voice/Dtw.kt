package guardianlink.voice

import kotlin.math.min
import kotlin.math.sqrt

/**
 * Dynamic time warping over MFCC frame sequences.
 *
 * Used for the liveness content check: the spoken utterance's frame
 * sequence must align cheaply with the enrolled reference for the
 * *challenged* phrase. Same phrase → low distance; different phrase →
 * high distance, even from the same speaker. Length-normalized by
 * (n + m) so the threshold is stable across utterance lengths.
 *
 * O(n·m) time, O(m) space.
 */
object Dtw {
    /**
     * Length-normalized DTW distance between frame sequences [a] and [b].
     * Returns +∞ for empty input — the caller fails closed on that.
     */
    fun distance(a: List<DoubleArray>, b: List<DoubleArray>): Double {
        if (a.isEmpty() || b.isEmpty()) return Double.POSITIVE_INFINITY
        val n = a.size
        val m = b.size
        // Pairwise frame distances, computed once.
        val cost = Array(n) { i ->
            DoubleArray(m) { j -> frameDistance(a[i], b[j]) }
        }
        var prev = DoubleArray(m + 1) { Double.POSITIVE_INFINITY }
        var curr = DoubleArray(m + 1) { Double.POSITIVE_INFINITY }
        prev[0] = 0.0
        for (i in 1..n) {
            curr[0] = Double.POSITIVE_INFINITY
            for (j in 1..m) {
                curr[j] = cost[i - 1][j - 1] + min(prev[j], min(curr[j - 1], prev[j - 1]))
            }
            val tmp = prev; prev = curr; curr = tmp
        }
        return prev[m] / (n + m)
    }

    private fun frameDistance(x: DoubleArray, y: DoubleArray): Double {
        var s = 0.0
        for (d in x.indices) {
            val diff = x[d] - y[d]
            s += diff * diff
        }
        return sqrt(s)
    }
}
