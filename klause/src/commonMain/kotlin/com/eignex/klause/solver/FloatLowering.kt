package com.eignex.klause.solver

import com.eignex.klause.solver.factor.FloatLinear
import com.eignex.klause.solver.factor.Linear
import com.eignex.klause.solver.factor.LinearOp
import kotlin.math.roundToLong

/**
 * Bucketing-based lowering of float variables and constraints to bounded integers.
 * Used by backends that don't natively support real arithmetic (bit-blaster, LogicNG,
 * and currently LocalSearchSolver / BacktrackSolver until native float handling lands).
 *
 * Each float variable `f` with domain `[lo, hi]` is replaced by an int var `i` over
 * `[0, buckets - 1]`. The float value is recovered as
 *   `lo + i * (hi - lo) / (buckets - 1)`  (linear interpolation across the bucket index).
 *
 * Float linear constraints `Σ coeffs_k * f_k ⟨op⟩ bound` are rewritten as integer
 * linear constraints over the bucketed indices, scaled by [floatScale] and rounded.
 * This is the same encoding that the FZN compiler has been doing inline; centralizing
 * it here lets every bucketing backend share the implementation.
 *
 * Use as:
 * ```
 * val lowered = FloatLowering.lower(problem, buckets = 1024)
 * val intSample = engine.solve(lowered.problem)
 * val sample = lowered.decode(intSample)
 * ```
 */
object FloatLowering {
    const val DEFAULT_BUCKETS: Int = 1024
    const val DEFAULT_SCALE: Long = 1_000_000L

    fun lower(
        problem: Problem,
        buckets: Int = DEFAULT_BUCKETS,
        floatScale: Long = DEFAULT_SCALE,
    ): Lowered {
        if (problem.numFloatVars == 0) return Lowered(problem, FloatDecoder(emptyArray()))

        // Allocate one new int var per float, appended after the existing int range.
        val baseIntId = problem.numIntVars
        val intDomains = problem.intDomains.toMutableList()
        val decoders = Array(problem.numFloatVars) { i ->
            val d = problem.floatDomains[i]
            intDomains.add(IntDomain(0, buckets - 1))
            BucketDecoder(intVarId = baseIntId + i, lo = d.lo, hi = d.hi, buckets = buckets)
        }

        // Rewrite each FloatLinear into an integer Linear over bucketed indices.
        val newFactors = ArrayList<Factor>(problem.factors.size)
        for (f in problem.factors) {
            if (f is FloatLinear) {
                newFactors.add(rewriteFloatLinear(f, decoders, floatScale))
            } else {
                newFactors.add(f)
            }
        }

        val lowered = Problem(
            numBoolVars = problem.numBoolVars,
            numIntVars = intDomains.size,
            intDomains = intDomains.toTypedArray(),
            factors = newFactors,
            numFloatVars = 0,
            floatDomains = emptyArray(),
        )
        return Lowered(lowered, FloatDecoder(decoders))
    }

    /**
     * Rewrite `Σ c_k * f_k ⟨op⟩ bound` over floats into an integer-linear constraint
     * over the float bucket indices.
     *
     * For each float var `f_k ∈ [lo_k, hi_k]` discretized to `N` buckets with step
     * `step_k = (hi_k - lo_k) / (N - 1)`, the value at bucket index `i_k` is
     * `lo_k + i_k * step_k`. Substituting and rearranging:
     *
     *   `Σ c_k * (lo_k + i_k * step_k) ⟨op⟩ bound`
     *   `Σ c_k * step_k * i_k          ⟨op⟩ bound - Σ c_k * lo_k`
     *
     * Multiply through by [floatScale] and round to the nearest integer.
     */
    private fun rewriteFloatLinear(
        f: FloatLinear,
        decoders: Array<BucketDecoder>,
        floatScale: Long,
    ): Linear {
        var scaledBound = (f.bound * floatScale).roundToLong()
        val scaledCoeffs = IntArray(f.coeffs.size)
        val vars = IntArray(f.coeffs.size)
        for (k in f.coeffs.indices) {
            val d = decoders[f.vars[k]]
            val step = if (d.buckets > 1) (d.hi - d.lo) / (d.buckets - 1) else 0.0
            scaledCoeffs[k] = (f.coeffs[k] * step * floatScale).roundToLong().toInt()
            vars[k] = d.intVarId
            scaledBound -= (f.coeffs[k] * d.lo * floatScale).roundToLong()
        }
        return Linear(scaledCoeffs, vars, f.op, scaledBound.toInt())
    }

    data class Lowered(val problem: Problem, val decoder: FloatDecoder)
}

/**
 * Recovers the original float values of a [Sample] produced against a lowered problem.
 * The decoder is a no-op when [decoders] is empty (problem had no floats).
 */
class FloatDecoder(internal val decoders: Array<BucketDecoder>) {
    fun decode(sample: Sample): Sample {
        if (decoders.isEmpty()) return sample
        val floats = DoubleArray(decoders.size) { i ->
            val d = decoders[i]
            val bucket = sample.ints[d.intVarId]
            if (d.buckets <= 1) d.lo
            else d.lo + bucket * (d.hi - d.lo) / (d.buckets - 1)
        }
        return Sample(sample.bools, sample.ints, floats)
    }
}

/** Per-float-var bucketing record. */
data class BucketDecoder(
    val intVarId: Int,
    val lo: Double,
    val hi: Double,
    val buckets: Int,
)
