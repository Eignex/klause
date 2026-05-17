package com.eignex.klause.solver

/**
 * Anything an [Optimizer] can score an assignment by. The contract is "lower is better" —
 * optimisation backends minimise this. To maximise, negate the weights.
 *
 * The primary subtype is [LinearObjective]. Backends pattern-match on it for fast paths
 * (incremental delta in the local-search engine, native `mkAdd` translation in Z3) and may
 * either fall back to the generic [evaluate] or refuse to optimise other subtypes.
 */
interface Objective {
    fun evaluate(sample: Sample): Double
}

/**
 * Σ boolWeights[b] · 1[bool[b]] + Σ intCoefficients[i] · int[i] + constant.
 *
 * - [boolWeights] indexes by the original-problem bool var id; size must equal
 *   `problem.numBoolVars`.
 * - [intCoefficients] indexes by the original-problem int var id (note: float vars after
 *   compilation live in the int-var namespace, so a coefficient on a `floatVar` is
 *   applied to its bucket index — multiply by `(max - min) / (buckets - 1)` and fold
 *   `min · coeff` into [constant] if you need real-valued semantics).
 * - [constant] is added unconditionally; useful for objectives whose "zero" assignment
 *   has nonzero cost.
 *
 * All arrays are kept by reference, not copied. Treat them as immutable after handing the
 * objective to an optimiser.
 */
data class LinearObjective(
    val boolWeights: DoubleArray = DoubleArray(0),
    val intCoefficients: DoubleArray = DoubleArray(0),
    val constant: Double = 0.0,
) : Objective {

    override fun evaluate(sample: Sample): Double {
        var total = constant
        for (b in 0 until minOf(sample.bools.size, boolWeights.size)) {
            if (sample.bools[b]) total += boolWeights[b]
        }
        for (i in 0 until minOf(sample.ints.size, intCoefficients.size)) {
            total += intCoefficients[i] * sample.ints[i]
        }
        return total
    }

    override fun equals(other: Any?): Boolean {
        if (other !is LinearObjective) return false
        return constant == other.constant &&
            boolWeights.contentEquals(other.boolWeights) &&
            intCoefficients.contentEquals(other.intCoefficients)
    }

    override fun hashCode(): Int {
        var h = constant.hashCode()
        h = 31 * h + boolWeights.contentHashCode()
        h = 31 * h + intCoefficients.contentHashCode()
        return h
    }

    companion object {
        /**
         * "Minimize this one integer variable" — the MiniZinc `solve minimize x` idiom
         * built directly. Equivalent to `LinearObjective(intCoefficients = arr)` where
         * `arr` is length [numIntVars] with `arr[intVar] = 1.0` and the rest zero. Pass
         * the problem's [Problem.numIntVars] to keep the coefficient array aligned with
         * destroy operators like [com.eignex.klause.solver.localsearch.meta.DestroyOperator.WorstObjective]
         * that index by position.
         */
        fun minimizeInt(intVar: Int, numIntVars: Int): LinearObjective {
            require(intVar in 0 until numIntVars) { "intVar $intVar out of [0, $numIntVars)" }
            val arr = DoubleArray(numIntVars)
            arr[intVar] = 1.0
            return LinearObjective(intCoefficients = arr)
        }

        /** Negate-the-coefficient form of [minimizeInt]. Optimizers minimize, so a `-1.0`
         *  coefficient maximizes. */
        fun maximizeInt(intVar: Int, numIntVars: Int): LinearObjective {
            require(intVar in 0 until numIntVars) { "intVar $intVar out of [0, $numIntVars)" }
            val arr = DoubleArray(numIntVars)
            arr[intVar] = -1.0
            return LinearObjective(intCoefficients = arr)
        }

        /** Minimize a single Boolean variable's truth value (penalises the bool being true). */
        fun minimizeBool(boolVar: Int, numBoolVars: Int): LinearObjective {
            require(boolVar in 0 until numBoolVars) { "boolVar $boolVar out of [0, $numBoolVars)" }
            val arr = DoubleArray(numBoolVars)
            arr[boolVar] = 1.0
            return LinearObjective(boolWeights = arr)
        }

        /** Reward the Boolean being true. */
        fun maximizeBool(boolVar: Int, numBoolVars: Int): LinearObjective {
            require(boolVar in 0 until numBoolVars) { "boolVar $boolVar out of [0, $numBoolVars)" }
            val arr = DoubleArray(numBoolVars)
            arr[boolVar] = -1.0
            return LinearObjective(boolWeights = arr)
        }
    }
}
