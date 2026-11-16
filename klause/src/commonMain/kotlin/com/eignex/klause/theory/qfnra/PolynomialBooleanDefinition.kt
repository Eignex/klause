package com.eignex.klause.theory.qfnra

import com.eignex.klause.arithmetic.polynomial.PolynomialArithmetic

internal data class PolynomialBooleanDefinition(val atom: PolynomialAtom, val sameTruth: Boolean)

internal fun polynomialBooleanDefinitions(
    nodes: List<FormulaNode>,
    root: Int,
    atoms: List<PolynomialAtom>,
    arithmetic: PolynomialArithmetic,
): Map<Int, PolynomialBooleanDefinition> = BooleanDefinitionFinder(nodes, root, atoms, arithmetic).find()

private class BooleanDefinitionFinder(
    private val nodes: List<FormulaNode>,
    private val root: Int,
    private val atoms: List<PolynomialAtom>,
    private val arithmetic: PolynomialArithmetic,
) {
    private val clauses = HashMap<DefinitionKey, Int>()
    private val definitions = LinkedHashMap<Int, PolynomialBooleanDefinition>()

    fun find(): Map<Int, PolynomialBooleanDefinition> {
        val state = PolynomialBooleanPropagation(nodes, root, arithmetic, skipConfirmedParents = true)
        if (!state.initialize() || !state.propagate()) return emptyMap()
        for ((index, node) in nodes.withIndex()) {
            arithmetic.step()
            if (state.value(index) != PolynomialFormula.TRUE || node !is FormulaNode.Junction ||
                node.conjunction || node.children.size != 2
            ) continue
            val clause = term(node.children)
            if (clause != null) recordClause(clause) else recordAlternatives(node.children)
        }
        arithmetic.step()
        return definitions
    }

    private fun recordClause(term: DefinitionTerm) {
        arithmetic.step()
        val key = DefinitionKey(term.variable, term.atom, term.booleanTruth != term.atomTruth)
        val mask = (clauses[key] ?: 0) or (if (term.booleanTruth) 2 else 1)
        clauses[key] = mask
        if (mask == 3) record(key)
    }

    private fun recordAlternatives(children: IntArray) {
        arithmetic.step()
        val left = nodes[children[0]] as? FormulaNode.Junction ?: return
        val right = nodes[children[1]] as? FormulaNode.Junction ?: return
        if (!left.conjunction || !right.conjunction) return
        val first = term(left.children) ?: return
        val second = term(right.children) ?: return
        if (first.variable != second.variable || first.atom != second.atom ||
            first.booleanTruth == second.booleanTruth || first.atomTruth == second.atomTruth
        ) return
        record(DefinitionKey(first.variable, first.atom, first.booleanTruth == first.atomTruth))
    }

    private fun record(key: DefinitionKey) {
        arithmetic.step()
        if (key.variable !in definitions) {
            definitions[key.variable] = PolynomialBooleanDefinition(atoms[key.atom], key.sameTruth)
        }
    }

    private fun term(children: IntArray): DefinitionTerm? {
        arithmetic.step()
        if (children.size != 2) return null
        val left = literal(children[0])
        val right = literal(children[1])
        return term(left, right) ?: term(right, left)
    }

    private fun literal(index: Int): DefinitionLiteral {
        arithmetic.step()
        val node = nodes[index]
        return if (node is FormulaNode.Not) DefinitionLiteral(nodes[node.child], false)
        else DefinitionLiteral(node, true)
    }

    private fun term(boolean: DefinitionLiteral, numeric: DefinitionLiteral): DefinitionTerm? {
        arithmetic.step()
        val variable = boolean.node as? FormulaNode.BooleanVariable ?: return null
        val atom = numeric.node as? FormulaNode.Atom ?: return null
        return DefinitionTerm(variable.variable, atom.atom, boolean.truth, numeric.truth)
    }
}

private data class DefinitionKey(val variable: Int, val atom: Int, val sameTruth: Boolean)
private data class DefinitionLiteral(val node: FormulaNode, val truth: Boolean)
private data class DefinitionTerm(val variable: Int, val atom: Int, val booleanTruth: Boolean, val atomTruth: Boolean)
