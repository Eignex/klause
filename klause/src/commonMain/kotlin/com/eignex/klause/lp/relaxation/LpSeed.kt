package com.eignex.klause.lp.relaxation

import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Problem
import com.eignex.klause.lp.engine.newLpSolver
import com.eignex.klause.solver.Sample
import com.eignex.klause.util.ExecutionControl
import com.eignex.util.numbers.CheckedLongOverflowException
import kotlin.math.roundToLong

// The LP optimum inside domains, integers rounded into their domains and Booleans read at one half. Only a
// seed: nothing claims the point satisfies anything, so it suits a search that verifies what it reports.
// Building over domains as a box is what lets it serve a model with open sides.
internal fun Problem.lpSeed(domains: Array<IntDomain>, cancellation: ExecutionControl): Sample? {
    // Rows that read a column's declared range, a reified row's big-M among them, need one even on an open column;
    // the box stands in for it, which only bounds where the seed is looked for.
    val boxed = withIntDomains(domains)
    val relaxation = try {
        CpToLpRelaxation(boxed, objective = null).build(BoxDomains(domains), cancellation = cancellation)
    } catch (_: LpAssemblyCancelled) {
        return null
    } catch (_: CheckedLongOverflowException) {
        // A row the 64-bit LP cannot state over this box, which leaves the search its own starts.
        return null
    }
    val primal = newLpSolver(relaxation.model, cancellation).use { it.solve()?.primal } ?: return null
    val ints = LongArray(numIntVars) { v ->
        val column = relaxation.intColOf.getOrElse(v) { -1 }
        val value = if (column in primal.indices) primal[column] else 0.0
        domains[v].clamp(if (value.isFinite()) value.roundToLong() else 0L)
    }
    val bools = BooleanArray(numBoolVars) { b ->
        val column = relaxation.boolColOf.getOrElse(b) { -1 }
        column in primal.indices && primal[column] > 0.5
    }
    return Sample(bools, ints, relaxation.floatReals(primal, boxed))
}

private class BoxDomains(private val domains: Array<IntDomain>) : RelaxationDomains {
    override fun intDomain(varId: Int): IntDomain = domains[varId]
    override fun boolValue(varId: Int): Boolean? = null
}
