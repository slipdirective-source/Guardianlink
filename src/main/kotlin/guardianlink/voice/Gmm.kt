package guardianlink.voice

import kotlin.math.*
import kotlin.random.Random

/**
 * Gaussian mixture model with diagonal covariances — the workhorse of the
 * classical GMM-UBM speaker-verification stack.
 *
 * All likelihoods are computed in the log domain (log-sum-exp) for
 * numerical stability. Training is deterministic given [seed].
 */
data class Gmm(
    val weights: DoubleArray,
    val means: Array<DoubleArray>,
    val variances: Array<DoubleArray>,
) {
    val components: Int get() = weights.size
    val dim: Int get() = means[0].size

    /** Log p(x) under the mixture. */
    fun logLikelihood(x: DoubleArray): Double {
        var acc = Double.NEGATIVE_INFINITY
        for (k in 0 until components) {
            val lp = ln(weights[k]) + logGaussian(x, means[k], variances[k])
            acc = logAdd(acc, lp)
        }
        return acc
    }

    /** Posterior responsibilities γ_k(x), summing to 1. */
    fun responsibilities(x: DoubleArray): DoubleArray {
        val lps = DoubleArray(components) { k -> ln(weights[k]) + logGaussian(x, means[k], variances[k]) }
        val total = lps.fold(Double.NEGATIVE_INFINITY, ::logAdd)
        return DoubleArray(components) { k -> exp(lps[k] - total) }
    }

    private fun logGaussian(x: DoubleArray, mean: DoubleArray, variance: DoubleArray): Double {
        var s = 0.0
        for (d in x.indices) {
            val diff = x[d] - mean[d]
            s += ln(variance[d]) + diff * diff / variance[d]
        }
        return -0.5 * (dim * ln(2.0 * PI) + s)
    }

    companion object {
        /** Variance floor: keeps a collapsed component from producing infinities. */
        const val VARIANCE_FLOOR = 1e-3

        private fun logAdd(a: Double, b: Double): Double {
            if (a == Double.NEGATIVE_INFINITY) return b
            if (b == Double.NEGATIVE_INFINITY) return a
            val m = max(a, b)
            return m + ln(exp(a - m) + exp(b - m))
        }

        /**
         * Train a GMM with EM from [frames]. Deterministic given [seed]:
         * k-means seeding (seeded picks + Lloyd iterations), then EM until
         * the log-likelihood improvement drops below [tolerance] or
         * [maxIters] is reached.
         */
        fun trainEm(
            frames: List<DoubleArray>,
            components: Int,
            maxIters: Int = 40,
            tolerance: Double = 1e-4,
            seed: Long = 0x5EEDL,
        ): Gmm {
            require(frames.isNotEmpty()) { "no frames" }
            require(components >= 1) { "components >= 1" }
            val dim = frames[0].size
            val rng = Random(seed)
            var means = kMeans(frames, components, rng)
            var weights = DoubleArray(components) { 1.0 / components }
            var variances = Array(components) { globalVariance(frames, dim) }
            var prevLl = Double.NEGATIVE_INFINITY
            repeat(maxIters) {
                // E-step: responsibilities.
                val gamma = Array(frames.size) { t ->
                    Gmm(weights, means, variances).responsibilities(frames[t])
                }
                // M-step.
                val nk = DoubleArray(components)
                for (t in frames.indices) for (k in 0 until components) nk[k] += gamma[t][k]
                val total = nk.sum()
                weights = DoubleArray(components) { k -> max(nk[k] / total, 1e-12) }
                val newMeans = Array(components) { DoubleArray(dim) }
                val newVars = Array(components) { DoubleArray(dim) }
                for (k in 0 until components) {
                    if (nk[k] < 1e-12) {
                        newMeans[k] = means[k].copyOf()
                        newVars[k] = variances[k].copyOf()
                        continue
                    }
                    for (d in 0 until dim) {
                        var m = 0.0
                        for (t in frames.indices) m += gamma[t][k] * frames[t][d]
                        m /= nk[k]
                        newMeans[k][d] = m
                        var v = 0.0
                        for (t in frames.indices) {
                            val diff = frames[t][d] - m
                            v += gamma[t][k] * diff * diff
                        }
                        newVars[k][d] = max(v / nk[k], VARIANCE_FLOOR)
                    }
                }
                means = newMeans
                variances = newVars
                // Convergence on total log-likelihood.
                val gmm = Gmm(weights, means, variances)
                var ll = 0.0
                for (f in frames) ll += gmm.logLikelihood(f)
                if (ll - prevLl < tolerance) return gmm
                prevLl = ll
            }
            return Gmm(weights, means, variances)
        }

        /**
         * MAP-adapt a background model to speaker frames (means only — the
         * classical recipe). [relevance] r: α_k = n_k / (n_k + r).
         */
        fun mapAdapt(ubm: Gmm, frames: List<DoubleArray>, relevance: Double = 16.0): Gmm {
            require(frames.isNotEmpty()) { "no frames" }
            val k = ubm.components
            val dim = ubm.dim
            val nk = DoubleArray(k)
            val ex = Array(k) { DoubleArray(dim) }
            for (f in frames) {
                val g = ubm.responsibilities(f)
                for (c in 0 until k) {
                    nk[c] += g[c]
                    for (d in 0 until dim) ex[c][d] += g[c] * f[d]
                }
            }
            val means = Array(k) { DoubleArray(dim) }
            for (c in 0 until k) {
                val alpha = if (nk[c] < 1e-12) 0.0 else nk[c] / (nk[c] + relevance)
                for (d in 0 until dim) {
                    val emp = if (nk[c] < 1e-12) ubm.means[c][d] else ex[c][d] / nk[c]
                    means[c][d] = alpha * emp + (1.0 - alpha) * ubm.means[c][d]
                }
            }
            // Weights/variances stay at the UBM (classical means-only MAP).
            return ubm.copy(means = means)
        }

        private fun globalVariance(frames: List<DoubleArray>, dim: Int): DoubleArray {
            val mean = DoubleArray(dim)
            for (f in frames) for (d in 0 until dim) mean[d] += f[d] / frames.size
            val v = DoubleArray(dim)
            for (f in frames) for (d in 0 until dim) {
                val diff = f[d] - mean[d]
                v[d] += diff * diff / frames.size
            }
            for (d in 0 until dim) v[d] = max(v[d], VARIANCE_FLOOR)
            return v
        }

        private fun kMeans(frames: List<DoubleArray>, k: Int, rng: Random, lloydIters: Int = 10): Array<DoubleArray> {
            val dim = frames[0].size
            val idx = frames.indices.shuffled(rng).take(k)
            var centroids = Array(k) { i -> frames[idx[i % idx.size]].copyOf() }
            repeat(lloydIters) {
                val assign = IntArray(frames.size)
                for (t in frames.indices) {
                    var best = 0
                    var bestD = Double.POSITIVE_INFINITY
                    for (c in 0 until k) {
                        var d = 0.0
                        for (dd in 0 until dim) {
                            val diff = frames[t][dd] - centroids[c][dd]
                            d += diff * diff
                        }
                        if (d < bestD) { bestD = d; best = c }
                    }
                    assign[t] = best
                }
                val next = Array(k) { DoubleArray(dim) }
                val cnt = IntArray(k)
                for (t in frames.indices) {
                    val c = assign[t]; cnt[c]++
                    for (d in 0 until dim) next[c][d] += frames[t][d]
                }
                for (c in 0 until k) {
                    if (cnt[c] > 0) for (d in 0 until dim) next[c][d] = next[c][d] / cnt[c]
                    else next[c] = frames[rng.nextInt(frames.size)].copyOf()
                }
                centroids = next
            }
            return centroids
        }
    }
}
