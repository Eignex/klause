package com.eignex.klause.portfolio

import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * The engine families a portfolio shares time between before it chooses an arm. [observable] says whether a
 * segment that shows no progress is evidence against the family: a local-search or LNS arm's whole state is
 * visible, so a segment that lowers no violation and improves no incumbent is a plateau; a backtrack arm can be
 * closing in on a proof while showing nothing, so its silence says nothing.
 */
internal enum class ArmFamily(val observable: Boolean) {
    /** Complete search: resumable backtrack arms. */
    Backtrack(observable = false),

    /** Local search: counted arms that restart from the incumbent. */
    LocalSearch(observable = true),

    /** Large-neighbourhood search around the incumbent; it has nothing to work on before one exists. */
    Lns(observable = true),
}

/**
 * Thompson sampling over [ArmFamily], so the time each family gets does not depend on how many arms it has: a fourth
 * local-search variant splits the local-search share instead of taking a share of its own from backtrack.
 *
 * A segment that earned credit counts for its family; once an incumbent exists, one that earned none counts against
 * it when the family is [ArmFamily.observable]. Evidence fades over [halfLife] segments, short enough that a family
 * which stops progressing loses its share within a few of them: a local search stuck on a plateau hands the time to
 * the complete search that can still prove the answer, and wins it back as soon as it improves again.
 */
internal class FamilyPolicy(private val random: Random, private val halfLife: Double = FAMILY_HALF_LIFE) {
    private val successes = DoubleArray(ArmFamily.entries.size)
    private val failures = DoubleArray(ArmFamily.entries.size)

    /** The family to run next among [eligible], which must not be empty. */
    fun choose(eligible: Collection<ArmFamily>): ArmFamily {
        require(eligible.isNotEmpty()) { "no eligible family" }
        return eligible.maxBy { random.nextBeta(PRIOR + successes[it.ordinal], PRIOR + failures[it.ordinal]) }
    }

    /**
     * Record one segment of [family]: whether it [progressed], and whether a segment that did not counts as a
     * [plateau]. Before an incumbent exists a local search still descending toward feasibility lowers its record
     * violation only now and then, and no other family is any closer, so only progress counts.
     */
    fun record(family: ArmFamily, progressed: Boolean, plateau: Boolean) {
        val retained = 2.0.pow(-1.0 / halfLife)
        for (i in successes.indices) {
            successes[i] *= retained
            failures[i] *= retained
        }
        if (progressed) {
            successes[family.ordinal] += 1.0
        } else if (plateau && family.observable) {
            failures[family.ordinal] += 1.0
        }
    }

    private companion object {
        // Beta(1, 1): a family with no evidence is as likely as any to be the one to run.
        const val PRIOR = 1.0

        // Segments after which a family's evidence counts half as much.
        const val FAMILY_HALF_LIFE = 4.0
    }
}

/** A draw from Beta([alpha], [beta]), both positive, as the ratio of two Gamma draws. */
internal fun Random.nextBeta(alpha: Double, beta: Double): Double {
    val x = nextGamma(alpha)
    val y = nextGamma(beta)
    return if (x + y > 0.0) x / (x + y) else 0.5
}

// Marsaglia and Tsang's method; a shape below one is boosted by one and scaled back by u^(1/shape).
private fun Random.nextGamma(shape: Double): Double {
    if (shape < 1.0) return nextGamma(shape + 1.0) * nextDouble().pow(1.0 / shape)
    val d = shape - 1.0 / 3.0
    val c = 1.0 / sqrt(9.0 * d)
    while (true) {
        val x = nextGaussian()
        val v = (1.0 + c * x).pow(3)
        if (v <= 0.0) continue
        val u = nextDouble()
        if (ln(u) < 0.5 * x * x + d - d * v + d * ln(v)) return d * v
    }
}

// Box-Muller.
private fun Random.nextGaussian(): Double {
    val u = 1.0 - nextDouble()
    val v = nextDouble()
    return sqrt(-2.0 * ln(u)) * kotlin.math.cos(2.0 * kotlin.math.PI * v)
}
