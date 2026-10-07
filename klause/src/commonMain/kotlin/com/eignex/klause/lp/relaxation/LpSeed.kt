package com.eignex.klause.lp.relaxation

import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Problem
import com.eignex.klause.lp.engine.solveAndCertify
import com.eignex.klause.solver.Sample
import com.eignex.klause.util.Cancellation
import kotlin.math.roundToLong

/**
 * A starting point for a search over [domains]: the LP relaxation's optimum inside them, integers rounded and kept in
 * their domains, Booleans read at one half. Null when the LP finds no point before [cancellation].
 *
 * Only a seed: nothing here claims the point satisfies anything, so it suits a search that verifies whatever it
 * reports. The relaxation is built over [domains] as a box, which is what lets it serve a model with open sides.
 */
internal fun Problem.lpSeed(domains: Array<IntDomain>, cancellation: Cancellation): Sample? {
    val relaxation = try {
        CpToLpRelaxation(this, objective = null).build(BoxDomains(domains), cancellation = cancellation)
    } catch (_: LpAssemblyCancelled) {
        return null
    }
    val primal = solveAndCertify(relaxation.model, cancellation = cancellation).float?.primal ?: return null
    val ints = LongArray(numIntVars) { v ->
        val column = relaxation.intColOf.getOrElse(v) { -1 }
        val value = if (column in primal.indices) primal[column] else 0.0
        domains[v].clamp(if (value.isFinite()) value.roundToLong() else 0L)
    }
    val bools = BooleanArray(numBoolVars) { b ->
        val column = relaxation.boolColOf.getOrElse(b) { -1 }
        column in primal.indices && primal[column] > 0.5
    }
    return Sample(bools, ints, relaxation.floatReals(primal, this))
}

/** [RelaxationDomains] over fixed [domains]: every integer column at its box, every Boolean free. */
private class BoxDomains(private val domains: Array<IntDomain>) : RelaxationDomains {
    override fun intDomain(varId: Int): IntDomain = domains[varId]
    override fun boolValue(varId: Int): Boolean? = null
}
