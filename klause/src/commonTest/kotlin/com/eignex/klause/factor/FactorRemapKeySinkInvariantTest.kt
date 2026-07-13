package com.eignex.klause.factor

import com.eignex.klause.factor.arithmetic.ArrayMinMax
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.LinearOp
import com.eignex.klause.factor.arithmetic.Product
import com.eignex.klause.factor.arithmetic.ReifiedCardinality
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.factor.arithmetic.ReifiedPseudoBoolean
import com.eignex.klause.factor.bool.Cardinality
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.factor.bool.PseudoBoolean
import com.eignex.klause.factor.bool.Xor
import com.eignex.klause.factor.circuit.Circuit
import com.eignex.klause.factor.global.AllDifferent
import com.eignex.klause.factor.global.Increasing
import com.eignex.klause.factor.global.Inverse
import com.eignex.klause.factor.global.LexLess
import com.eignex.klause.factor.global.Sort
import com.eignex.klause.factor.global.SymmetricAllDifferent
import com.eignex.klause.factor.global.ValuePrecede
import com.eignex.klause.factor.objective.MutableObjectiveBound
import com.eignex.klause.factor.objective.ObjectiveBoundFactor
import com.eignex.klause.factor.scheduling.Cumulative
import com.eignex.klause.factor.scheduling.Diffn
import com.eignex.klause.factor.scheduling.Disjunctive
import com.eignex.klause.factor.table.Mdd
import com.eignex.klause.factor.table.Regular
import com.eignex.klause.factor.table.Table
import com.eignex.klause.model.PbOp
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.Lit
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The byte-identity gate for the [com.eignex.klause.solver.KeySink] migration: a factor's
 * allocation-free [Factor.remapStructuralHash] must equal `remap(maps).structuralKey().hashCode()`
 * exactly — that equality is what keeps symmetry colouring (and the symmetries found) unchanged.
 * A non-monotonic permutation is used so `sortedIntVars` / `pairsByVarKey` sort-by-image genuinely
 * differs from sort-by-original.
 */
class FactorRemapKeySinkInvariantTest {

    // A bijection on 0..63 (45 is coprime to 64), so images are a non-monotonic permutation — no id
    // collisions (remap stays injective) yet image order ≠ original order.
    private val intMap = IntArray(64) { (it * 45) % 64 }
    private val boolMap = IntArray(64) { (it * 45) % 64 }
    private val identity = IntArray(64) { it }

    private fun pos(v: Int) = Lit.make(v, true)
    private fun neg(v: Int) = Lit.make(v, false)

    private val migrated: List<Pair<String, Factor>> = listOf(
        "Increasing" to Increasing(intArrayOf(3, 1, 5), strict = true),
        "Sort" to Sort(intArrayOf(4, 0, 2), intArrayOf(6, 1, 7)),
        "Inverse" to Inverse(intArrayOf(5, 0, 3), intArrayOf(2, 7, 4), fOffset = 1, gOffset = 0),
        "AllDifferent" to AllDifferent(intArrayOf(6, 2, 9), 0, 10),
        "AllDifferent(opt)" to AllDifferent(intArrayOf(6, 2), 0, 10, intArrayOf(pos(4), neg(1))),
        "ArrayMinMax" to ArrayMinMax(0, intArrayOf(7, 2, 5), max = true),
        "Product" to Product(8, 1, 4),
        "ReifiedLinear" to ReifiedLinear(2, intArrayOf(3, -1, 2), intArrayOf(5, 0, 7), LinearOp.LE, 3),
        "Linear" to Linear(intArrayOf(3, -1, 2), intArrayOf(5, 0, 7), LinearOp.LE, 3),
        "Cardinality" to Cardinality(intArrayOf(pos(4), neg(1), pos(6)), 1, 2),
        "Clause" to Clause(intArrayOf(pos(4), neg(1), pos(6))),
        "Xor" to Xor(intArrayOf(pos(3), neg(5), pos(0)), 1),
        "PseudoBoolean" to PseudoBoolean(longArrayOf(2, 1, 3), intArrayOf(pos(4), neg(2), pos(7)), PbOp.LE, 2),
        "Regular" to Regular(
            intArrayOf(6, 2),
            numStates = 2,
            alphabetSize = 2,
            transitions = longArrayOf(1, 2, 2, 1),
            q0 = 1,
            accepting = intArrayOf(1),
        ),
        "Table" to Table(intArrayOf(3, 7), longArrayOf(0, 0, 1, 4, 2, 2)),
        "Circuit" to Circuit(intArrayOf(3, 1, 5)),
        "Subcircuit" to Circuit(intArrayOf(4, 2, 6), subcircuit = true),
        "LexLess" to LexLess(intArrayOf(1, 3), intArrayOf(5, 0), strict = true),
        "SymmetricAllDifferent" to SymmetricAllDifferent(intArrayOf(2, 4, 6), indexOffset = 0),
        "ValuePrecede" to ValuePrecede(2L, 5L, intArrayOf(1, 3, 0)),
        "ReifiedCardinality" to ReifiedCardinality(0, intArrayOf(pos(4), neg(1), pos(6)), 1, 2),
        "ReifiedPseudoBoolean" to
            ReifiedPseudoBoolean(0, longArrayOf(2, 1, 3), intArrayOf(pos(4), neg(2), pos(7)), PbOp.LE, 2),
        "Cumulative" to Cumulative(
            intArrayOf(1, 2),
            longArrayOf(3, 4),
            longArrayOf(1, 1),
            5,
            intArrayOf(pos(0), neg(6)),
            intArrayOf(7, 8),
            intArrayOf(9, 10),
            11,
        ),
        "Diffn" to Diffn(
            intArrayOf(1, 2),
            intArrayOf(3, 4),
            longArrayOf(5, 5),
            longArrayOf(6, 6),
            intArrayOf(7, 8),
            intArrayOf(9, 10),
            false,
        ),
        "Disjunctive" to Disjunctive(intArrayOf(1, 2), longArrayOf(3, 4), intArrayOf(pos(0), neg(5)), intArrayOf(6, 7)),
        "Mdd" to Mdd(
            intArrayOf(1, 2),
            intArrayOf(1, 1, 1),
            intArrayOf(0, 4, 8),
            longArrayOf(0, 0, 0, 1, 0, 1, 0, 2),
            0,
            intArrayOf(0),
            4,
            11,
        ),
        "ObjectiveBound" to ObjectiveBoundFactor(
            intArrayOf(0, 3),
            longArrayOf(1, 1),
            intArrayOf(2, 4),
            longArrayOf(1, 1),
            MutableObjectiveBound(0L),
        ),
    )

    @Test
    fun `remapStructuralHash equals the remapped key hash for every migrated factor`() {
        for ((name, f) in migrated) {
            assertEquals(
                f.remap(boolMap, intMap).structuralKey().hashCode(),
                f.remapStructuralHash(boolMap, intMap),
                "$name: allocation-free remap hash must match remap().structuralKey().hashCode()",
            )
        }
    }

    @Test
    fun `remapStructuralHash under the identity map equals the plain key hash`() {
        for ((name, f) in migrated) {
            assertEquals(
                f.structuralKey().hashCode(),
                f.remapStructuralHash(identity, identity),
                "$name: identity remap hash must match structuralKey().hashCode()",
            )
        }
    }
}
