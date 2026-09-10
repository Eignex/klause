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
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.BakedProblem
import com.eignex.klause.propagation.bake
import com.eignex.klause.schema.VariableSchema
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.SolveResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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

    private class AbsentFloatZero : VariableSchema() {
        val f by optFloatVar(min = -1.0, max = 1.0, buckets = 5)
        init {
            constraint(Not(f.present.toExpr()))
        }
    }

    private class AbsentFloatClamped : VariableSchema() {
        val f by optFloatVar(min = 2.0, max = 7.0, buckets = 11)
        init {
            constraint(Not(f.present.toExpr()))
        }
    }

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
        assertEquals(2, sample.ints[compiled.intVarIdByName.getValue("f")])
        assertEquals(null, compiled.decode(s.f, sample))
    }

    @Test
    fun `absent float clamps default into domain`() {
        val s = AbsentFloatClamped()
        val compiled = s.compile()
        val sample = firstFeasible(compiled)
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
    fun `float pin rejects a non-default bucket only when enabled`() {
        val pinned = AbsentFloatZero().compile(KlauseConfig(pinAbsentOptVars = true))
        val unpinned = AbsentFloatZero().compile(KlauseConfig(pinAbsentOptVars = false))
        val nonDefault = mapOf(unpinned.intVarIdByName.getValue("f") to 1L)
        assertTrue(
            BacktrackSolver(unpinned.problem.bake()).solve(
                BacktrackParams(assumptions = Assumptions(ints = nonDefault)),
            ) is SolveResult.Sat,
        )
        assertTrue(
            BacktrackSolver(pinned.problem.bake()).solve(BacktrackParams(assumptions = Assumptions(ints = nonDefault)))
                is SolveResult.Unsat,
        )
    }

    private class ReifiedAllDiff(n: Int) : VariableSchema() {
        init {
            for (i in 0 until n) add("x$i", IntSpec(0, n - 1))
            add("b", BoolSpec)
            add("c", NamedConstraint(Iff(BoolRef("b"), AllDifferent((0 until n).map { IntRef("x$it") }))))
        }
    }

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
    fun `reified all different preserves truth across lowering arities`() {
        for (n in listOf(3, 4, 8)) {
            val compiled = ReifiedAllDiff(n).compile()
            val baked = compiled.problem.bake()
            val distinct = (0 until n).map(Int::toLong)
            val duplicate = List(n) { 0L }
            assertTrue(
                satisfiable(compiled, baked, distinct, emptyList(), flag = true),
                "distinct terms rejected at n=$n",
            )
            assertTrue(
                !satisfiable(compiled, baked, distinct, emptyList(), flag = false),
                "distinct terms accepted as false at n=$n",
            )
            assertTrue(
                satisfiable(compiled, baked, duplicate, emptyList(), flag = false),
                "duplicate terms rejected at n=$n",
            )
            assertTrue(
                !satisfiable(compiled, baked, duplicate, emptyList(), flag = true),
                "duplicate terms accepted as true at n=$n",
            )
        }
    }
}
