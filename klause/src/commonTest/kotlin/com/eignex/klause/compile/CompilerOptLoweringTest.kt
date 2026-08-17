package com.eignex.klause.compile

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.config.KlauseConfig
import com.eignex.klause.localsearch.LocalSearchParams
import com.eignex.klause.localsearch.LocalSearchSolver
import com.eignex.klause.model.AllDifferent
import com.eignex.klause.model.AllDifferentOpt
import com.eignex.klause.model.BoolRef
import com.eignex.klause.model.BoolSpec
import com.eignex.klause.model.Iff
import com.eignex.klause.model.IntRef
import com.eignex.klause.model.IntSpec
import com.eignex.klause.model.NamedConstraint
import com.eignex.klause.model.Not
import com.eignex.klause.schema.VariableSchema
import com.eignex.klause.solver.Assumptions
import com.eignex.klause.solver.BakedProblem
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.SolveResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Opt-var pinning: when an optional variable is absent (its `__present` bool false), its value
 * is fixed to a canonical in-domain default — `0` coerced into `[min, max]` for ints, `false`
 * for bools. Gated by [KlauseConfig.pinAbsentOptVars].
 */
class CompilerOptLoweringTest {

    private class AbsentIntZero : VariableSchema() {
        val x by optIntVar(min = 0, max = 5)
        init {
            constraint(Not(x.present.toExpr()))
        } // force absent
    }

    private class AbsentIntClamped : VariableSchema() {
        val x by optIntVar(min = 2, max = 7) // 0 ∉ domain → clamp to min = 2
        init {
            constraint(Not(x.present.toExpr()))
        }
    }

    private class AbsentBool : VariableSchema() {
        val b by optBoolVar()
        init {
            constraint(Not(b.present.toExpr()))
        }
    }

    // 0.0 ∈ [-1, 1]; scale = 2/4 = 0.5, so the canonical default 0.0 is bucket 2.
    private class AbsentFloatZero : VariableSchema() {
        val f by optFloatVar(min = -1.0, max = 1.0, buckets = 5)
        init {
            constraint(Not(f.present.toExpr()))
        }
    }

    // 0.0 ∉ [2, 7] → clamp to min = 2.0, which is bucket 0.
    private class AbsentFloatClamped : VariableSchema() {
        val f by optFloatVar(min = 2.0, max = 7.0, buckets = 11)
        init {
            constraint(Not(f.present.toExpr()))
        }
    }

    // present forced true, value pinned to 0.0 → decode reads back the real value.
    private class PresentFloat : VariableSchema() {
        val f by optFloatVar(min = -1.0, max = 1.0, buckets = 5)
        init {
            constraint(f eq 0.0)
        }
    }

    private fun firstFeasible(compiled: CompiledSchema): Sample {
        val solver = LocalSearchSolver(compiled.problem.bake())
        val s = solver.samples(LocalSearchParams(maxFlips = 20_000, randomSeed = 7)).firstOrNull()
        assertTrue(s != null, "solver found no feasible sample")
        return s
    }

    @Test
    fun `absent int pins to zero when zero in domain`() {
        val s = AbsentIntZero()
        val compiled = s.compile()
        val sample = firstFeasible(compiled)
        assertEquals(0, sample.ints[compiled.intVarIdByName.getValue("x")])
    }

    @Test
    fun `absent int clamps zero into domain`() {
        val s = AbsentIntClamped()
        val compiled = s.compile()
        val sample = firstFeasible(compiled)
        assertEquals(2, sample.ints[compiled.intVarIdByName.getValue("x")])
    }

    @Test
    fun `absent bool pins to false`() {
        val s = AbsentBool()
        val compiled = s.compile()
        val sample = firstFeasible(compiled)
        assertEquals(false, sample.bools[compiled.boolVarIdByName.getValue("b")])
    }

    @Test
    fun `absent float pins to bucket of canonical default`() {
        val s = AbsentFloatZero()
        val compiled = s.compile()
        val sample = firstFeasible(compiled)
        // canonical default 0.0 → bucket 2 of 5 over [-1, 1].
        assertEquals(2, sample.ints[compiled.intVarIdByName.getValue("f")])
        assertEquals(null, compiled.decode(s.f, sample))
    }

    @Test
    fun `absent float clamps default into domain`() {
        val s = AbsentFloatClamped()
        val compiled = s.compile()
        val sample = firstFeasible(compiled)
        // 0.0 clamped to min 2.0 → bucket 0.
        assertEquals(0, sample.ints[compiled.intVarIdByName.getValue("f")])
        assertEquals(null, compiled.decode(s.f, sample))
    }

    @Test
    fun `present float decodes to its real value`() {
        val s = PresentFloat()
        val compiled = s.compile()
        val sample = firstFeasible(compiled)
        assertEquals(0.0, compiled.decode(s.f, sample))
    }

    @Test
    fun `float pin adds a constraint only when enabled`() {
        val pinned = AbsentFloatZero().compile(KlauseConfig(pinAbsentOptVars = true))
        val unpinned = AbsentFloatZero().compile(KlauseConfig(pinAbsentOptVars = false))
        assertTrue(
            pinned.problem.factors.size > unpinned.problem.factors.size,
            "pinning should add a constraint (pinned=${pinned.problem.factors.size}, " +
                "unpinned=${unpinned.problem.factors.size})",
        )
    }

    /** `b ↔ all_different(x0 … x{n-1})` over `[0, n - 1]`. */
    private class ReifiedAllDiff(n: Int) : VariableSchema() {
        init {
            for (i in 0 until n) add("x$i", IntSpec(0, n - 1))
            add("b", BoolSpec)
            add("c", NamedConstraint(Iff(BoolRef("b"), AllDifferent((0 until n).map { IntRef("x$it") }))))
        }
    }

    /** `b ↔ all_different_opt(x0 … x{n-1})` over `[0, 1]` with free presence bits. */
    private class ReifiedAllDiffOpt(n: Int) : VariableSchema() {
        init {
            for (i in 0 until n) add("x$i", IntSpec(0, 1))
            for (i in 0 until n) add("p$i", BoolSpec)
            add("b", BoolSpec)
            add(
                "c",
                NamedConstraint(
                    Iff(
                        BoolRef("b"),
                        AllDifferentOpt(
                            (0 until n).map { IntRef("x$it") },
                            (0 until n).map { BoolRef("p$it") },
                        ),
                    ),
                ),
            )
        }
    }

    /** Whether `b = [flag]` is satisfiable with every term pinned to [terms] and every presence bit to
     *  [presents] — one complete solve per combination, so both `Unsat` and `Sat` are conclusive. */
    private fun satisfiable(
        compiled: CompiledSchema,
        baked: BakedProblem,
        terms: List<Long>,
        presents: List<Boolean>,
        flag: Boolean,
    ): Boolean {
        val ints = terms.indices.associate { compiled.intVarIdByName.getValue("x$it") to terms[it] }
        val bools = presents.indices.associate { compiled.boolVarIdByName.getValue("p$it") to presents[it] } +
            (compiled.boolVarIdByName.getValue("b") to flag)
        val params = BacktrackParams(assumptions = Assumptions(ints = ints, bools = bools))
        return BacktrackSolver(baked).solve(params) is SolveResult.Sat
    }

    private fun tuples(n: Int, values: Int): List<List<Long>> = (0 until n).fold(listOf(emptyList())) { acc, _ ->
        acc.flatMap { prefix -> (0 until values).map { prefix + it.toLong() } }
    }

    @Test
    fun `reified all different holds exactly when the terms are distinct`() {
        val n = 4
        val compiled = ReifiedAllDiff(n).compile()
        val baked = compiled.problem.bake()
        for (terms in tuples(n, n)) {
            val distinct = terms.toSet().size == n
            assertTrue(
                satisfiable(compiled, baked, terms, emptyList(), distinct),
                "no model with the reified literal $distinct for $terms",
            )
            assertTrue(
                !satisfiable(compiled, baked, terms, emptyList(), !distinct),
                "a model accepts the reified literal ${!distinct} for $terms",
            )
        }
    }

    @Test
    fun `reified opt all different holds exactly when the present terms are distinct`() {
        val n = 4
        val compiled = ReifiedAllDiffOpt(n).compile()
        val baked = compiled.problem.bake()
        for (terms in tuples(n, 2)) {
            for (mask in 0 until (1 shl n)) {
                val presents = (0 until n).map { (mask shr it) and 1 == 1 }
                val shown = terms.indices.filter { presents[it] }.map { terms[it] }
                val distinct = shown.toSet().size == shown.size
                assertTrue(
                    satisfiable(compiled, baked, terms, presents, distinct),
                    "no model with the reified literal $distinct for $terms presents $presents",
                )
                assertTrue(
                    !satisfiable(compiled, baked, terms, presents, !distinct),
                    "a model accepts the reified literal ${!distinct} for $terms presents $presents",
                )
            }
        }
    }

    @Test
    fun `reified all different encoding size stops growing with the term count`() {
        val sizes = (4..8).map { ReifiedAllDiff(it).compile().problem.factors.size }
        assertEquals(setOf(sizes.first()), sizes.toSet(), "encoding size varies with arity: $sizes")
    }

    @Test
    fun `reified all different stays pairwise below the witness threshold`() {
        val three = ReifiedAllDiff(3).compile().problem.factors.size
        val four = ReifiedAllDiff(4).compile().problem.factors.size
        assertTrue(three > four, "three pairwise terms ($three) should not undercut four witness terms ($four)")
    }

    @Test
    fun `pin can be disabled via config`() {
        val pinned = AbsentIntZero().compile(KlauseConfig(pinAbsentOptVars = true))
        val unpinned = AbsentIntZero().compile(KlauseConfig(pinAbsentOptVars = false))
        assertTrue(
            pinned.problem.factors.size > unpinned.problem.factors.size,
            "pinning should add a constraint (pinned=${pinned.problem.factors.size}, " +
                "unpinned=${unpinned.problem.factors.size})",
        )
    }
}
