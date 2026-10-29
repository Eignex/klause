package com.eignex.klause.presolve

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.factor.table.Table
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntegerConstants
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.impliedLinearRows
import com.eignex.klause.util.Cancellation

internal fun Factor.retainsStructure(problem: Problem, token: Cancellation): Boolean =
    (this !is Linear && this !is Clause) || isBinaryPartition(problem, token)

internal fun protectedStructureColumns(problem: Problem, token: Cancellation): Set<Int>? {
    val protected = HashSet<Int>()
    for (factor in problem.factors) {
        token.charge(1)
        if (token()) return null
        if (!factor.retainsStructure(problem, token)) continue
        for (variable in factor.intVars) {
            token.charge(1)
            if (token()) return null
            protected.add(variable)
        }
    }
    return if (token()) null else protected
}

private fun Factor.isBinaryPartition(problem: Problem, token: Cancellation): Boolean {
    val row = this as? Linear ?: return false
    if (row.op != LinearOp.EQ || row.vars.size < 2) return false
    val constants = row.integerConstants ?: return false
    if (constants.bound == 0L) return false
    val bounds = problem.intBounds
    for (k in row.vars.indices) {
        token.charge(1)
        if (token()) return true
        val variable = row.vars[k]
        if (constants.coeff(k) != constants.bound || !bounds.hasLower(variable) || !bounds.hasUpper(variable) ||
            bounds.lower(variable) < 0L || bounds.upper(variable) > 1L
        ) return false
    }
    return true
}

internal fun PresolveInvocation?.recordStructuralImpact(
    problem: Problem,
    dropped: IntArray,
    added: List<Factor>,
    rebuild: SourceRebuilds,
    limit: Int,
    token: Cancellation,
) {
    if (this == null || limit == 0 || (pass != "affine" && pass != "structural" && pass != "extensional")) return
    val view = StructuralImpactView(limit, token)
    for (id in dropped) {
        if (!view.factor("Removed", problem.factors[id], problem)) break
    }
    if (!view.stopped) for (factor in added) {
        if (!view.factor("Added", factor, problem)) break
    }
    if (!view.stopped) view.reconstruction(rebuild)
    structuralImpact = view.snapshot()
}

private class StructuralImpactView(private val limit: Int, private val token: Cancellation) {
    private val counts = LinkedHashMap<String, Long>()
    private var visits = 0L
    var stopped = false
        private set

    private fun visit(): Boolean {
        if (stopped) return false
        if (visits >= limit || token()) {
            stopped = true
            return false
        }
        visits++
        token.charge(1)
        if (token()) stopped = true
        return !stopped
    }

    private fun add(key: String, value: Long = 1L) {
        counts[key] = (counts[key] ?: 0L) + value
    }

    fun factor(prefix: String, factor: Factor, problem: Problem): Boolean {
        if (!visit()) return false
        add(prefix + "Factors")
        if (factor !is Linear && factor !is Clause) add(prefix + "StructuredFactors")
        if (factor is Table) add(prefix + "TableCells", factor.tuples.size.toLong())
        val integers = factor.intVars
        val booleans = factor.boolVars
        if (integers.isNotEmpty() && booleans.isNotEmpty()) add(prefix + "MixedFactors")
        var support = 0L
        for (variable in integers) {
            if (!visit()) return false
            support++
        }
        for (variable in booleans) {
            if (!visit()) return false
            support++
        }
        add(prefix + "SupportEntries", support)
        counts[prefix + "MaxSupport"] = maxOf(counts[prefix + "MaxSupport"] ?: 0L, support)
        for (row in factor.impliedLinearRows) {
            if (!visit()) return false
            if (row.constants !is IntegerConstants) {
                add(prefix + "UncountedLinearRows")
                continue
            }
            for (k in 0 until row.size) {
                if (!visit()) return false
                if (row.coeff(k) != 0L) add(prefix + "DeclaredLinearNnz")
            }
        }
        // Candidate definition edges do not assert an inferred or installed LS sweep.
        if (factor is Linear && factor.op == LinearOp.EQ) {
            add(prefix + "EqualityRows")
            add(prefix + "DefinitionInputEntries", (factor.vars.size - 1).coerceAtLeast(0).toLong())
            val constants = factor.integerConstants
            if (constants != null && constants.bound != 0L && factor.vars.size >= 2) {
                var partition = true
                for (k in factor.vars.indices) {
                    if (!visit()) return false
                    val variable = factor.vars[k]
                    val bounds = problem.intBounds
                    if (constants.coeff(k) != constants.bound || !bounds.hasLower(variable) ||
                        !bounds.hasUpper(variable) || bounds.lower(variable) < 0L || bounds.upper(variable) > 1L
                    ) partition = false
                }
                if (partition) add(prefix + "PartitionRows")
            }
        }
        return true
    }

    fun reconstruction(rebuild: SourceRebuilds) {
        val depths = HashMap<Int, Long>()
        for (step in rebuild.steps) {
            if (!visit()) return
            if (step !is RebuildStep.AffineValue) continue
            add("ReconstructionDefinitions")
            var depth = 1L
            for (variable in step.termVars) {
                if (!visit()) return
                add("ReconstructionInputEntries")
                depth = maxOf(depth, 1L + (depths[variable] ?: 0L))
            }
            depths[step.variable] = depth
            counts["ReconstructionMaxDepth"] = maxOf(counts["ReconstructionMaxDepth"] ?: 0L, depth)
        }
    }

    fun snapshot(): Map<String, Long> = counts.toMap() + mapOf(
        "Calls" to 1L,
        "Complete" to if (stopped) 0L else 1L,
        "Visits" to visits,
    )
}
