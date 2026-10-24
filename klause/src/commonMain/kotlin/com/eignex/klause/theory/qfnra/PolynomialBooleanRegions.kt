package com.eignex.klause.theory.qfnra

import com.eignex.klause.arithmetic.polynomial.PolynomialArithmetic
import com.eignex.klause.arithmetic.polynomial.PolynomialResourceLimit
import com.eignex.klause.ir.PolynomialRelation
import com.eignex.klause.theory.qfnra.PolynomialFormula.Companion.FALSE
import com.eignex.klause.theory.qfnra.PolynomialFormula.Companion.TRUE
import com.eignex.klause.theory.qfnra.PolynomialFormula.Companion.UNASSIGNED

internal data class PolynomialBooleanRegion(val atoms: Map<Int, Boolean>, val bools: Map<Int, Boolean>)

internal class PolynomialBooleanRegions(
    private val nodes: List<FormulaNode>,
    private val root: Int,
    private val atoms: List<PolynomialAtom>,
    private val arithmetic: PolynomialArithmetic,
    private val progress: PolynomialQueryProgress? = null,
    private val onConflict: ((List<PolynomialAtom>) -> Unit)? = null,
) {
    private val state = PolynomialBooleanPropagation(nodes, root, arithmetic, skipConfirmedParents = true)
    private val exclusions = ArrayList<Exclusion>()
    private val signs = PolynomialBooleanSigns(nodes, atoms, state, arithmetic)
    private var partialAvailable = true
    private var partialSigns: PolynomialSignPropagation? = null
    private val partialValues = HashMap<Int, Int>()

    fun prepare(): Iterator<PolynomialBooleanRegion?> = sequence<PolynomialBooleanRegion?> {
        if (!initialize()) return@sequence
        for (ready in signs.prepare()) {
            if (ready == false) return@sequence
            if (ready == null) yield(null)
        }
        var checkedChanges = state.numericChanges
        while (true) {
            arithmetic.step()
            if (!propagate()) {
                if (!state.backtrack()) return@sequence
                yield(null)
                continue
            }
            if (partialAvailable && state.numericChanges != checkedChanges) {
                val checking = checkPartial()
                var consistent: Boolean? = null
                while (consistent == null) {
                    consistent = checking.next()
                    if (consistent == null) yield(null)
                }
                checkedChanges = state.numericChanges
                if (!consistent) continue
            }
            val proof = proof()
            if (proof == null) {
                check(state.decide())
            } else {
                yield(proof.region)
                arithmetic.step()
                // Exclusions record visited regions; only the numeric caller can certify their exhaustion.
                exclusions.add(Exclusion(proof.literals, numeric = false))
            }
            yield(null)
        }
    }.iterator()

    private fun initialize(): Boolean {
        if (!state.initialize()) return false
        // Constant predicates have the same truth in every cell of this conditioned query.
        for ((index, node) in nodes.withIndex()) {
            arithmetic.step()
            if (node !is FormulaNode.Atom) continue
            val atom = atoms[node.atom]
            if (atom.polynomial.mainVariable != null) continue
            val sign = atom.polynomial.terms.firstOrNull()?.coefficient?.signum() ?: 0
            if (!state.assign(index, if (atom.holds(sign)) TRUE else FALSE)) return false
        }
        return true
    }

    private fun propagate(): Boolean {
        while (true) {
            arithmetic.step()
            val mark = state.trailSize
            if (!state.propagate()) return false
            for (excluded in exclusions) {
                arithmetic.step()
                if (!propagate(excluded)) return false
            }
            if (state.trailSize == mark) return true
        }
    }

    private fun propagate(excluded: Exclusion): Boolean {
        var unknowns = 0
        var pending: Literal? = null
        for (literal in excluded.literals) {
            arithmetic.step()
            val value = state.value(literal.node)
            if (value == UNASSIGNED) {
                unknowns++
                pending = literal
            } else if (value != literal.value) return true
        }
        if (unknowns == 0) {
            used(excluded)
            return false
        }
        if (unknowns == 1) {
            used(excluded)
            val literal = checkNotNull(pending)
            return state.assign(literal.node, TRUE - literal.value)
        }
        return true
    }

    private fun used(exclusion: Exclusion) {
        if (!exclusion.numeric) return
        arithmetic.step()
        progress?.let {
            it.partialExclusionUses++
            if (exclusion.uses > 0) it.partialExclusionReuses++
        }
        exclusion.uses++
    }

    private fun checkPartial(): Iterator<Boolean?> = sequence {
        try {
            val required = ArrayList<PolynomialAtom>()
            val premises = ArrayList<Literal>()
            for ((index, node) in nodes.withIndex()) {
                arithmetic.step()
                if (node !is FormulaNode.Atom) continue
                val value = state.value(index)
                if (value == UNASSIGNED) continue
                val atom = atoms[node.atom]
                required.add(if (value == TRUE) atom else PolynomialAtom(atom.polynomial, when (atom.relation) {
                    PolynomialRelation.EQ -> PolynomialRelation.NE
                    PolynomialRelation.NE -> PolynomialRelation.EQ
                    PolynomialRelation.LT -> PolynomialRelation.GE
                    PolynomialRelation.LE -> PolynomialRelation.GT
                    PolynomialRelation.GT -> PolynomialRelation.LE
                    PolynomialRelation.GE -> PolynomialRelation.LT
                }))
                premises.add(Literal(index, value))
            }
            if (premises.isEmpty()) {
                discardPartial()
                yield(true)
                return@sequence
            }
            val retracted = partialValues.any { (node, value) ->
                arithmetic.step()
                state.value(node) != value
            }
            if (retracted) discardPartial()
            val added = ArrayList<PolynomialAtom>()
            for ((index, premise) in premises.withIndex()) {
                arithmetic.step()
                if (partialValues[premise.node] != premise.value) added.add(required[index])
            }
            if (added.isEmpty()) {
                yield(true)
                return@sequence
            }
            progress?.let { it.partialChecks++ }
            val retained = partialSigns
            val propagation = retained ?: PolynomialSignPropagation(signs.realCount, arithmetic).also {
                partialSigns = it
                progress?.let { observed -> observed.partialBuilds++ }
            }
            progress?.let {
                if (retained != null) it.partialExtensions++
                it.partialInputAtoms += added.size
            }
            val checking = propagation.prepare(added)
            var conflict: Boolean? = null
            while (conflict == null) {
                conflict = checking.next()
                if (conflict == null) yield(null)
            }
            arithmetic.step()
            if (conflict) {
                discardPartial()
                // A completed arithmetic contradiction excludes these signed predicates under every Boolean prefix.
                exclusions.add(Exclusion(premises, numeric = true))
                progress?.let {
                    it.partialConflicts++
                    it.partialConflictPremises += premises.size
                }
                onConflict?.invoke(required.toList())
            } else {
                for (premise in premises) {
                    arithmetic.step()
                    partialValues[premise.node] = premise.value
                }
            }
            yield(!conflict)
        } catch (_: PolynomialResourceLimit) {
            discardPartial()
            partialAvailable = false
            progress?.let { it.partialDeclines++ }
            yield(true)
        }
    }.iterator()

    private fun discardPartial() {
        partialSigns = null
        partialValues.clear()
    }

    private fun proof(): Proof? {
        val witnessed = BooleanArray(nodes.size)
        val selected = IntArray(nodes.size) { -1 }
        for ((index, node) in nodes.withIndex()) {
            arithmetic.step()
            val value = state.value(index)
            witnessed[index] = when (node) {
                is FormulaNode.Constant -> value == if (node.value) TRUE else FALSE
                is FormulaNode.BooleanVariable, is FormulaNode.Atom -> value != UNASSIGNED
                is FormulaNode.Not -> witnessed[node.child] && value == TRUE - state.value(node.child)
                is FormulaNode.Junction -> witnessed(index, node, witnessed, selected)
            }
        }
        if (!witnessed[root]) return null
        return capture(selected)
    }

    private fun witnessed(
        index: Int,
        node: FormulaNode.Junction,
        witnessed: BooleanArray,
        selected: IntArray,
    ): Boolean {
        val value = state.value(index)
        if (value == UNASSIGNED) return false
        val decisive = if (node.conjunction) FALSE else TRUE
        if (value == decisive) {
            for (child in node.children) {
                arithmetic.step()
                if (witnessed[child] && state.value(child) == value) {
                    selected[index] = child
                    return true
                }
            }
            return false
        }
        for (child in node.children) {
            arithmetic.step()
            if (!witnessed[child] || state.value(child) != value) return false
        }
        return true
    }

    private fun capture(selected: IntArray): Proof {
        val visited = BooleanArray(nodes.size)
        val pending = ArrayDeque<Int>()
        pending.addLast(root)
        while (pending.isNotEmpty()) {
            arithmetic.step()
            val index = pending.removeLast()
            if (visited[index]) continue
            visited[index] = true
            when (val node = nodes[index]) {
                is FormulaNode.Not -> pending.addLast(node.child)
                is FormulaNode.Junction -> {
                    if (selected[index] >= 0) pending.addLast(selected[index])
                    else for (child in node.children) {
                        arithmetic.step()
                        pending.addLast(child)
                    }
                }
                else -> Unit
            }
        }
        signs.expand(visited)
        val atoms = LinkedHashMap<Int, Boolean>()
        val bools = LinkedHashMap<Int, Boolean>()
        val literals = ArrayList<Literal>()
        for ((index, node) in nodes.withIndex()) {
            arithmetic.step()
            if (!visited[index]) continue
            val value = state.value(index)
            when (node) {
                is FormulaNode.Atom -> {
                    atoms[node.atom] = value == TRUE
                    literals.add(Literal(index, value))
                }
                is FormulaNode.BooleanVariable -> {
                    bools[node.variable] = value == TRUE
                    literals.add(Literal(index, value))
                }
                else -> Unit
            }
        }
        return Proof(PolynomialBooleanRegion(atoms, bools), literals)
    }

    private class Literal(val node: Int, val value: Int)
    private class Exclusion(val literals: List<Literal>, val numeric: Boolean, var uses: Long = 0)
    private class Proof(val region: PolynomialBooleanRegion, val literals: List<Literal>)
}
