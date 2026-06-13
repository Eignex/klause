package com.eignex.klause.compile

import com.eignex.klause.config.KlauseConfig
import com.eignex.klause.model.Not
import com.eignex.klause.schema.VariableSchema
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.localsearch.LocalSearchParams
import com.eignex.klause.solver.localsearch.LocalSearchSolver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Opt-var pinning: when an optional variable is absent (its `__present` bool false), its value
 * is fixed to a canonical in-domain default — `0` coerced into `[min, max]` for ints, `false`
 * for bools. Gated by [KlauseConfig.pinAbsentOptVars].
 */
class OptPinTest {

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

    private fun firstFeasible(compiled: CompiledProblem): Sample {
        val solver = LocalSearchSolver(compiled.problem)
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
