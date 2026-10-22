package com.eignex.klause.theory.qflra

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.ir.IntegerConstants
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.lp.lattice.BinaryParityBasis
import com.eignex.klause.lp.lattice.BinaryParityReduction
import com.eignex.klause.lp.lattice.BinaryParityRow
import com.eignex.klause.lp.lattice.BinaryParityWork
import com.eignex.klause.lp.lattice.MAX_PARITY_ROW
import com.eignex.klause.solver.search.ComponentResult
import com.eignex.klause.solver.search.SearchAtomPremise
import com.eignex.klause.solver.search.SearchContext
import com.eignex.klause.solver.search.SearchDecision
import com.eignex.klause.solver.search.SearchExplanation
import com.eignex.klause.solver.search.explainAtoms
import com.eignex.klause.theory.EncodedTheoryFactor

internal class IntegerParityPropagation private constructor(
    private val rows: List<SourceRow>,
    val retainedBytes: Long,
) {
    private class SourceRow(val parity: BinaryParityRow, val guard: Int, val equationWhen: Boolean)
    private class SourceProof(val explanation: SearchExplanation, val coupled: Boolean)

    @Suppress("ReturnCount", "CyclomaticComplexMethod")
    fun propagate(
        value: (Int) -> Boolean?,
        context: SearchContext,
        work: BinaryParityWork,
        receive: (Int) -> ComponentResult,
        observe: (String) -> Unit,
    ): ComponentResult? {
        if (context.cancelled()) return ComponentResult.Indeterminate
        if (rows.isEmpty() || !work.allocate(16L)) return stopped(work)
        val basis = BinaryParityBasis(work)
        observe("PARITY_PREFIX_BASIS")
        for ((index, row) in rows.withIndex()) {
            if (!work.visit()) return stopped(work)
            if (row.guard >= 0 && value(row.guard) != row.equationWhen) continue
            val reduced = basis.add(row.parity, index) ?: return stopped(work)
            if (!reduced.impossible) continue
            val reason = reason(reduced, null, context, work) ?: return stopped(work)
            if (!work.visit() || context.cancelled()) return ComponentResult.Indeterminate
            observe("PARITY_PREFIX_CONFLICT")
            if (reason.coupled) observe("PARITY_PREFIX_COUPLED_SUPPORT_CONFLICT")
            return ComponentResult.Conflict(reason.explanation)
        }
        var implied = false
        for (row in rows) {
            if (!work.visit()) return if (implied && !work.cancelled) ComponentResult.Consistent else stopped(work)
            if (row.guard < 0 || value(row.guard) != null || context.boolValue(row.guard) != null) continue
            if (basis.size == 0 && row.parity.size != 0) continue
            val reduced = basis.reduce(row.parity)
                ?: return if (implied && !work.cancelled) ComponentResult.Consistent else stopped(work)
            if (!reduced.impossible) continue
            val literal = Lit.make(row.guard, !row.equationWhen)
            val reason = reason(reduced, row, context, work)
            if (reason == null) {
                if (work.cancelled || context.cancelled()) return ComponentResult.Indeterminate
                continue
            }
            if (!work.visit() || context.cancelled()) return ComponentResult.Indeterminate
            if (context.boolValue(row.guard) != null) continue
            val result = context.imply(literal, reason.explanation)
            if (result !is ComponentResult.Consistent) return result
            val delivered = receive(literal)
            if (delivered !is ComponentResult.Consistent) return delivered
            observe("PARITY_PREFIX_IMPLIED")
            if (reason.coupled) observe("PARITY_PREFIX_COUPLED_SUPPORT_IMPLIED")
            implied = true
        }
        return if (work.cancelled) ComponentResult.Indeterminate else if (implied) ComponentResult.Consistent else null
    }

    private fun stopped(work: BinaryParityWork): ComponentResult? =
        if (work.cancelled) ComponentResult.Indeterminate else null

    @Suppress("ReturnCount")
    private fun reason(
        reduction: BinaryParityReduction,
        target: SourceRow?,
        context: SearchContext,
        work: BinaryParityWork,
    ): SourceProof? {
        if (reduction.supportSize >= MAX_PARITY_ROW || !work.allocate(reduction.supportSize.toLong() + 8L)) return null
        val conclusion = target?.let { Lit.make(it.guard, !it.equationWhen) }
        var coupled = target?.parity?.size?.let { it >= 3 } == true
        val premises = ArrayList<SearchAtomPremise>(reduction.supportSize)
        for (index in 0 until reduction.supportSize) {
            if (!work.visit()) return null
            val source = rows.getOrNull(reduction.supportAt(index)) ?: return null
            coupled = coupled || source.parity.size >= 3
            if (source.guard < 0) continue
            if (context.boolValue(source.guard) != source.equationWhen) return null
            val literal = Lit.make(source.guard, source.equationWhen)
            if (conclusion != null && Lit.variable(conclusion) == source.guard) return null
            if (!work.allocate(32L)) return null
            premises += SearchAtomPremise.Asserted(SearchDecision.Bool(literal))
        }
        // Flat source facts bound the existing explanation builder's complete traversal and storage.
        if (!work.allocate(32L * premises.size + 32L) || !work.visit(premises.size.toLong() + 1L)) return null
        val result = context.explainAtoms(SearchAtomPremise.All(premises), conclusion?.let { SearchDecision.Bool(it) })
            ?: return null
        if (!work.allocate(8L) || !work.visit() || context.cancelled()) return null
        val equations = reduction.supportSize + if (target == null) 0 else 1
        return SourceProof(result, coupled && equations >= 2)
    }

    companion object {
        @Suppress("ReturnCount", "LoopWithTooManyJumpStatements", "LongMethod", "CyclomaticComplexMethod")
        fun capture(model: Problem, work: BinaryParityWork): IntegerParityPropagation? {
            if (model.numIntVars == 0 || model.numRealVars != 0 || !work.allocate(16L)) return null
            val rows = ArrayList<SourceRow>()
            var terms = 0L
            for (factor in model.factors) {
                if (!work.visit()) return null
                val source = if (factor is EncodedTheoryFactor) {
                    if (factor.linearForm !== factor.source.linearForm) continue
                    factor.source
                } else factor
                val vars: IntArray
                val constants: IntegerConstants
                val guard: Int
                val equationWhen: Boolean
                when (source) {
                    is Linear -> {
                        if (source.op != LinearOp.EQ || source.realVars.isNotEmpty()) continue
                        vars = source.vars
                        constants = source.integerConstants ?: continue
                        guard = -1
                        equationWhen = true
                    }

                    is ReifiedLinear -> {
                        if (source.op != LinearOp.EQ && source.op != LinearOp.NE) continue
                        if (source.auxBoolVar !in 0 until model.numBoolVars) return null
                        vars = source.vars
                        constants = source.integerConstants ?: continue
                        guard = source.auxBoolVar
                        equationWhen = source.op == LinearOp.EQ
                    }

                    else -> continue
                }
                if (vars.size > MAX_PARITY_ROW) continue
                terms += vars.size
                if (rows.size == MAX_ROWS || terms > MAX_TERMS || !work.allocate(vars.size.toLong())) return null
                val odd = IntArray(vars.size)
                var count = 0
                for (index in vars.indices) {
                    if (!work.visit()) return null
                    val variable = vars[index]
                    if (variable !in 0 until model.numIntVars) return null
                    if (constants.coeff(index) and 1L != 0L) odd[count++] = variable
                }
                val parity = BinaryParityRow.create(odd, constants.bound and 1L != 0L, work, count) ?: return null
                if (!work.allocate(16L)) return null
                rows += SourceRow(parity, guard, equationWhen)
            }
            if (!work.allocate(rows.size.toLong())) return null
            return IntegerParityPropagation(rows.toList(), work.allocatedBytes).takeIf { work.visit() }
        }

        private const val MAX_ROWS = 65_536
        private const val MAX_TERMS = 131_072L
    }
}
