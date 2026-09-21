package com.eignex.klause.presolve

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.bool.Cardinality
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.factor.bool.PseudoBoolean
import com.eignex.klause.factor.global.AllDifferent
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntBounds
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.model.PbOp
import com.eignex.klause.presolve.PresolveShared.withPassDelta
import com.eignex.klause.presolve.PresolveShared.withSourcePassDelta
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.propagation.Propagator
import com.eignex.klause.propagation.bake
import com.eignex.klause.propagation.propagate
import com.eignex.klause.propagation.propagatorProjection
import com.eignex.klause.util.Bits
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Dual fixing (#448). Pinning a dominated variable to a bound must not change the **optimum** — each
 * test enumerates the whole assignment space, computes the minimum objective before and after, and
 * asserts they match (and that the expected variables were pinned).
 */
class DominatedVariablesTest {

    @Test
    fun `a declared Boolean inequality permits a safe pure-literal fixing`() {
        val source = PseudoBoolean(longArrayOf(2, 3), intArrayOf(Lit.make(0, true), Lit.make(1, false)), PbOp.LE, 3)
        val factor = object : Factor by source, Propagator by source.propagatorProjection() {}
        val model = Problem(2, 0, emptyArray(), listOf(factor)).bake()

        val delta = Presolve.fixDominatedVariables(model, emptyMap())

        val units = delta.addedFactors.map { (it as Clause).literals.single() }.toSet()
        assertEquals(setOf(Lit.make(0, false), Lit.make(1, true)), units)
    }

    private fun isFeasible(problem: Problem, ints: LongArray): Boolean {
        var a = Assumptions.None
        for (v in 0 until problem.numIntVars) a = a.withInt(v, ints[v])
        return problem.propagate(a) !is PropagationResult.Unsat
    }

    /** Minimum of `Σ coeffs·x` over the feasible assignments of [problem], or `null` if infeasible. */
    private fun minObjective(problem: Problem, coeffs: Map<Int, Long>): Long? {
        val n = problem.numIntVars
        val ints = LongArray(n) { problem.finiteIntDomain(it).min }
        var best: Long? = null
        while (true) {
            if (isFeasible(problem, ints.copyOf())) {
                var obj = 0L
                for (v in 0 until n) obj += (coeffs[v] ?: 0L) * ints[v]
                if (best == null || obj < best) best = obj
            }
            var i = 0
            while (i < n) {
                ints[i]++
                if (ints[i] <= problem.finiteIntDomain(i).max) break
                ints[i] = problem.finiteIntDomain(i).min
                i++
            }
            if (i == n) break
        }
        return best
    }

    private fun fixed(problem: Problem, intCoeffs: Map<Int, Long>, boolCoeffs: Map<Int, Long> = emptyMap()): Problem {
        val baked = problem.bake()
        return baked.withPassDelta(Presolve.fixDominatedVariables(baked, intCoeffs, boolCoeffs), BakeConfig.NONE)
    }

    private fun checkDualFix(name: String, problem: Problem, coeffs: Map<Int, Long>, expectFixed: Set<Int>) {
        val out = fixed(problem, coeffs)
        assertEquals(minObjective(problem, coeffs), minObjective(out, coeffs), "$name: optimum changed")
        for (v in expectFixed) {
            assertTrue(
                out.finiteIntDomain(v).min == out.finiteIntDomain(v).max,
                "$name: var $v should be pinned",
            )
        }
        if (expectFixed.isEmpty()) {
            assertTrue(Presolve.fixDominatedVariables(problem.bake(), coeffs).isEmpty, "$name: expected no fixing")
        }
    }

    @Test
    fun `non-objective down-safe variables are pinned to their lower bound`() {
        // x0, x1 ∈ [0,3], x0 + x1 <= 3, no objective: both appear only with +coeff in a ≤ row, so
        // lowering is always safe ⇒ both pinned to 0.
        val problem = Problem(
            0,
            2,
            Array(2) { IntDomain(0, 3) },
            listOf(Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.LE, 3)),
        )
        checkDualFix("down-safe", problem, emptyMap(), setOf(0, 1))
        val out = fixed(problem, emptyMap())
        assertEquals(0L, out.finiteIntDomain(0).min)
        assertEquals(0L, out.finiteIntDomain(0).max)
    }

    @Test
    fun `positive-cost down-safe variable is pinned to its lower bound`() {
        // min 2·x0, x0 + x1 <= 4. c0 = 2 ≥ 0 and lowering x0 is safe ⇒ pin x0 = 0.
        val problem = Problem(
            0,
            2,
            Array(2) { IntDomain(0, 5) },
            listOf(Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.LE, 4)),
        )
        checkDualFix("pos-cost", problem, mapOf(0 to 2L), setOf(0))
    }

    @Test
    fun `negative-cost up-safe variable is pinned to its upper bound`() {
        // min −x0 (i.e. maximize x0), with −x0 + x1 <= 4 (x0 has a negative coeff in a ≤ row ⇒
        // raising x0 is safe). c0 = −1 ≤ 0 ⇒ pin x0 to its upper bound 5.
        val problem = Problem(
            0,
            2,
            Array(2) { IntDomain(0, 5) },
            listOf(Linear(intArrayOf(-1, 1), intArrayOf(0, 1), LinearOp.LE, 4)),
        )
        val out = fixed(problem, mapOf(0 to -1L))
        assertEquals(minObjective(problem, mapOf(0 to -1L)), minObjective(out, mapOf(0 to -1L)), "optimum changed")
        assertEquals(5L, out.finiteIntDomain(0).min)
        assertEquals(5L, out.finiteIntDomain(0).max)
    }

    @Test
    fun `a variable that is neither up- nor down-safe is left free`() {
        // x0 appears with +coeff in both a ≤ row (lowering safe) and a ≥ row (raising safe) ⇒ neither
        // direction is globally safe, so x0 is not pinned. x1 / x2 sit only in their own row, so they
        // are pinned — assert x0 specifically stays free.
        val problem = Problem(
            0,
            3,
            Array(3) { IntDomain(0, 4) },
            listOf(
                Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.LE, 4),
                Linear(intArrayOf(1, 1), intArrayOf(0, 2), LinearOp.GE, 2),
            ),
        )
        val out = fixed(problem, emptyMap())
        assertEquals(minObjective(problem, emptyMap()), minObjective(out, emptyMap()), "optimum changed")
        assertTrue(out.finiteIntDomain(0).min != out.finiteIntDomain(0).max, "x0 must stay free")
    }

    @Test
    fun `variables in a global constraint are excluded`() {
        // AllDifferent makes its variables' dual-fixing safety undecidable ⇒ nothing is pinned.
        val problem = Problem(
            0,
            3,
            Array(3) { IntDomain(0, 2) },
            listOf(AllDifferent(intArrayOf(0, 1, 2), domainMin = 0, domainSize = 3)),
        )
        checkDualFix("global-excluded", problem, emptyMap(), emptySet())
    }

    // ---- Boolean dual fixing (#469) ----

    private fun pos(v: Int) = Lit.make(v, true)

    /** Minimum of `Σ weights·b` over the feasible Boolean assignments, or `null` if infeasible. */
    private fun minObjectiveBools(problem: Problem, weights: Map<Int, Long>): Long? {
        val nb = problem.numBoolVars
        var best: Long? = null
        for (mask in 0 until (1 shl nb)) {
            var a = Assumptions.None
            val bits = BooleanArray(nb) { (mask shr it) and 1 == 1 }
            for (v in 0 until nb) a = a.withBool(v, bits[v])
            if (problem.propagate(a) is PropagationResult.Unsat) continue
            var obj = 0L
            for (v in 0 until nb) if (bits[v]) obj += weights[v] ?: 0L
            if (best == null || obj < best) best = obj
        }
        return best
    }

    private fun hasUnit(problem: Problem, lit: Int) =
        problem.factors.any { it is Clause && it.literals.size == 1 && it.literals[0] == lit }

    @Test
    fun `pure-positive boolean is fixed true`() {
        // b0, b1 appear only positively (in a single clause) ⇒ setting them true satisfies it and is
        // always safe ⇒ both pinned true with a unit clause; the optimum (no objective) is preserved.
        val problem = Problem(2, 0, emptyArray(), listOf(Clause(intArrayOf(pos(0), pos(1)))))
        val out = fixed(problem, emptyMap(), emptyMap())
        assertEquals(minObjectiveBools(problem, emptyMap()), minObjectiveBools(out, emptyMap()), "optimum changed")
        assertTrue(hasUnit(out, Lit.make(0, true)), "b0 should be pinned true")
        assertTrue(hasUnit(out, Lit.make(1, true)), "b1 should be pinned true")
    }

    @Test
    fun `positive-cost pure-positive boolean stays free`() {
        // b0 is pure-positive (true is safe) but costs +2, and false is unsafe (the clause may need it)
        // ⇒ it cannot be pinned either way. b1 (zero cost) is still pinned true.
        val problem = Problem(2, 0, emptyArray(), listOf(Clause(intArrayOf(pos(0), pos(1)))))
        val coeffs = mapOf(0 to 2L)
        val out = fixed(problem, emptyMap(), coeffs)
        assertEquals(minObjectiveBools(problem, coeffs), minObjectiveBools(out, coeffs), "optimum changed")
        assertTrue(!hasUnit(out, Lit.make(0, true)) && !hasUnit(out, Lit.make(0, false)), "b0 must stay free")
    }

    @Test
    fun `negative-cost pure-positive boolean is fixed true`() {
        // c0 = −1 (true is beneficial) and true is safe ⇒ pin b0 true.
        val problem = Problem(2, 0, emptyArray(), listOf(Clause(intArrayOf(pos(0), pos(1)))))
        val coeffs = mapOf(0 to -1L)
        val out = fixed(problem, emptyMap(), coeffs)
        assertEquals(minObjectiveBools(problem, coeffs), minObjectiveBools(out, coeffs), "optimum changed")
        assertTrue(hasUnit(out, Lit.make(0, true)), "b0 should be pinned true")
    }

    @Test
    fun `an integer column no factor mentions is not pinned`() {
        // The mirror of the Boolean rule: x1 occurs nowhere, so no occurrence earned a pin and its value
        // is not the model's to state. Pinning it would only discard solutions — a column nothing reads
        // prunes nothing.
        val problem = Problem(
            0,
            2,
            Array(2) { IntDomain(0, 3) },
            listOf(Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 3)),
        )

        val delta = Presolve.fixDominatedVariables(problem.bake(), emptyMap(), emptyMap())

        assertEquals(
            IntDomain(0, 3),
            delta.domains?.get(1) ?: problem.finiteIntDomain(1),
            "the unreferenced column keeps its whole range",
        )
    }

    @Test
    fun `a boolean no factor mentions is not fixed`() {
        // b1 occurs nowhere: an earlier pass can fold a variable's defining factor away and leave it
        // referenced by nothing while its value stays tied to the model, so absence is not freedom.
        val problem = Problem(2, 0, emptyArray(), listOf(Clause(intArrayOf(pos(0)))))
        val delta = Presolve.fixDominatedVariables(problem.bake(), emptyMap(), emptyMap())
        assertTrue(
            delta.addedFactors.none { f -> f.boolVars.contains(1) },
            "expected no pin for the unreferenced bool",
        )
    }

    @Test
    fun `booleans in a two-sided cardinality are excluded`() {
        // Exactly-one (min == max == 1, both sides active) is not monotone in a single literal: both
        // satisfying and unsatisfying a literal can violate it ⇒ its bools can't be dual-fixed.
        val problem = Problem(
            3,
            0,
            emptyArray(),
            listOf(Cardinality(intArrayOf(pos(0), pos(1), pos(2)), min = 1, max = 1)),
        )
        assertTrue(
            Presolve.fixDominatedVariables(problem.bake(), emptyMap(), emptyMap()).isEmpty,
            "expected no fixing",
        )
    }

    @Test
    fun `pure-positive booleans in a one-sided cardinality are fixed to the safe polarity`() {
        // `b0+b1+b2 >= 1` (max == #lits, only the lower side active) is monotone like a clause: satisfying
        // a +literal is always safe ⇒ pin all true. `b0+b1+b2 <= 1` (min == 0, only the upper side active)
        // flips it: unsatisfying a +literal is always safe ⇒ pin all false. No objective either way.
        for ((min, max, expected) in listOf(Triple(1, 3, true), Triple(0, 1, false))) {
            val problem = Problem(
                3,
                0,
                emptyArray(),
                listOf(Cardinality(intArrayOf(pos(0), pos(1), pos(2)), min = min, max = max)),
            )
            val out = fixed(problem, emptyMap(), emptyMap())
            assertEquals(
                minObjectiveBools(problem, emptyMap()),
                minObjectiveBools(out, emptyMap()),
                "optimum changed for min=$min max=$max",
            )
            for (b in 0..2) {
                assertTrue(hasUnit(out, Lit.make(b, expected)), "b$b should be pinned $expected for min=$min max=$max")
            }
        }
    }

    @Test
    fun `boolean in a one-sided pseudo-boolean is fixed to the safe polarity`() {
        // LE: rising sum violates ⇒ positive-weight literals are true-unsafe ⇒ pin false.
        // GE: falling sum violates ⇒ positive-weight literals are false-unsafe ⇒ pin true.
        for ((op, bound, expected) in listOf(Triple(PbOp.LE, 4L, false), Triple(PbOp.GE, 1L, true))) {
            val problem = Problem(
                2,
                0,
                emptyArray(),
                listOf(PseudoBoolean(longArrayOf(2, 3), intArrayOf(pos(0), pos(1)), op, bound)),
            )
            val out = fixed(problem, emptyMap(), emptyMap())
            assertEquals(
                minObjectiveBools(problem, emptyMap()),
                minObjectiveBools(out, emptyMap()),
                "$op optimum changed",
            )
            assertTrue(
                hasUnit(out, Lit.make(0, expected)) && hasUnit(out, Lit.make(1, expected)),
                "$op bools pinned $expected",
            )
        }
    }

    @Test
    fun `negative-weight pseudo-boolean LE flips the safe direction`() {
        // `-b0 <= 0` (always true): a rising sum violates LE, and with weight -1 the rising value is
        // b0 = false ⇒ false-unsafe ⇒ the safe pin is true. Optimum (no objective) is preserved.
        val problem = Problem(
            1,
            0,
            emptyArray(),
            listOf(PseudoBoolean(longArrayOf(-1), intArrayOf(pos(0)), PbOp.LE, 0L)),
        )
        val out = fixed(problem, emptyMap(), emptyMap())
        assertEquals(minObjectiveBools(problem, emptyMap()), minObjectiveBools(out, emptyMap()), "optimum changed")
        assertTrue(hasUnit(out, Lit.make(0, true)), "b0 should be pinned true")
    }

    @Test
    fun `booleans in an equality pseudo-boolean are excluded`() {
        // `Σ = b` couples both directions ⇒ not monotone ⇒ no fixing.
        val problem = Problem(
            2,
            0,
            emptyArray(),
            listOf(PseudoBoolean(longArrayOf(1, 1), intArrayOf(pos(0), pos(1)), PbOp.EQ, 1L)),
        )
        assertTrue(
            Presolve.fixDominatedVariables(problem.bake(), emptyMap(), emptyMap()).isEmpty,
            "expected no fixing",
        )
    }

    // ---- the source lane: the same reduction before any finite projection exists ----

    /** One column, lower bound [lo], open above — the shape a finite domain cannot state. */
    private fun openAbove(lo: Long, vararg factors: Factor): Problem = Problem(
        numBoolVars = 0,
        intBounds = IntBounds.fromModelBounds(
            longArrayOf(lo),
            longArrayOf(0L),
            null,
            Bits(1).also { it.set(0) },
        ),
        factors = arrayOf(*factors),
    )

    /** One column open below, upper bound [hi]. */
    private fun openBelow(hi: Long, vararg factors: Factor): Problem = Problem(
        numBoolVars = 0,
        intBounds = IntBounds.fromModelBounds(
            longArrayOf(0L),
            longArrayOf(hi),
            Bits(1).also { it.set(0) },
            null,
        ),
        factors = arrayOf(*factors),
    )

    @Test
    fun `a down-safe column open above is pinned to its lower bound`() {
        // x >= 0, open above, occurring only as `+x <= 7`: lowering is always safe, so an optimum sits
        // at 0. The open upper side is irrelevant to that argument — which is the whole point.
        val problem = openAbove(0L, Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 7))

        val delta = Presolve.fixDominatedSourceVariables(problem, emptyMap())

        val out = assertNotNull(problem.withSourcePassDelta(delta), "the pin must not refute the model")
        assertEquals(0L, out.intBounds.lower(0))
        assertEquals(0L, out.intBounds.upper(0))
    }

    @Test
    fun `a down-safe column open below is left free`() {
        // Same safe direction, but nothing to pin to: the reduction proves that lowering never costs,
        // never where the lowering stops. Pinning to the invented endpoint would assert a bound the
        // model does not state.
        val problem = openBelow(7L, Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 7))

        val delta = Presolve.fixDominatedSourceVariables(problem, emptyMap())

        assertTrue(delta.isEmpty, "a column open on its safe side admits no pin")
    }

    @Test
    fun `an up-safe column open above is left free while its bounded partner pins`() {
        // -x0 + x1 <= 4 makes raising x0 safe and lowering x1 safe. x0 is open above, so its pin has no
        // endpoint; x1 is closed below and pins. One open column must not cost the other its reduction.
        val problem = Problem(
            numBoolVars = 0,
            intBounds = IntBounds.fromModelBounds(
                longArrayOf(0L, 0L),
                longArrayOf(0L, 5L),
                null,
                Bits(2).also { it.set(0) },
            ),
            factors = arrayOf(Linear(intArrayOf(-1, 1), intArrayOf(0, 1), LinearOp.LE, 4)),
        )

        val delta = Presolve.fixDominatedSourceVariables(problem, mapOf(0 to -1L))

        val out = assertNotNull(problem.withSourcePassDelta(delta))
        assertFalse(out.intBounds.hasUpper(0), "x0 stays open above")
        assertEquals(0L, out.intBounds.lower(1))
        assertEquals(0L, out.intBounds.upper(1))
    }

    @Test
    fun `a safe-direction boolean is pinned on a model with an open column`() {
        // The Boolean half reads no range at all, so an open integer column beside it changes nothing.
        // The clause needs two literals: a unit clause is already a pin, and re-emitting it is what the
        // idempotence rule suppresses.
        val problem = Problem(
            numBoolVars = 2,
            intBounds = IntBounds.fromModelBounds(
                longArrayOf(0L),
                longArrayOf(0L),
                null,
                Bits(1).also { it.set(0) },
            ),
            factors = arrayOf(
                Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 7),
                Clause(intArrayOf(pos(0), pos(1))),
            ),
        )

        val delta = Presolve.fixDominatedSourceVariables(problem, emptyMap())

        assertTrue(
            delta.addedFactors.any { it is Clause && it.literals.contentEquals(intArrayOf(Lit.make(0, true))) },
            "the pure-positive boolean is pinned true",
        )
    }

    @Test
    fun `both lanes pin the same columns on a closed model`() {
        // The source form is the finite one minus the domains it cannot read, so on a model that states
        // every bound the two must agree — the property that keeps the port from being a second pass.
        val problem = Problem(
            0,
            2,
            Array(2) { IntDomain(0, 5) },
            listOf(Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.LE, 4)),
        )

        val source = Presolve.fixDominatedSourceVariables(problem, mapOf(0 to 2L))
        val finite = Presolve.fixDominatedVariables(problem.bake(), mapOf(0 to 2L))

        val fromSource = assertNotNull(problem.withSourcePassDelta(source))
        assertEquals(0L, fromSource.intBounds.upper(0))
        val pinned = assertNotNull(finite.domains, "the finite lane pins the same column")
        assertEquals(0L, pinned[0].max)
    }

    @Test
    fun `a pin lands on the declared value set rather than the wider model range`() {
        // A column declaring `0..1000` inside a model range of `0..1000000`: the range's endpoint is a
        // value the declaration excludes, so pinning there refutes a model `x = 1000` satisfies.
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 1,
            intDomains = arrayOf(IntDomain(0, 1000)),
            factors = listOf(Linear(intArrayOf(1), intArrayOf(0), LinearOp.GE, 5)),
            modelBounds = IntBounds.fromModelBounds(longArrayOf(0L), longArrayOf(1_000_000L), null, null),
        )

        val delta = Presolve.fixDominatedSourceVariables(problem, emptyMap())

        val out = assertNotNull(problem.withSourcePassDelta(delta), "the pin must not refute the model")
        assertEquals(1000L, out.intBounds.lower(0))
        assertEquals(1000L, out.intBounds.upper(0))
    }
}
