package com.eignex.klause.compile

import com.eignex.klause.ast.Not
import com.eignex.klause.config.KlauseConfig
import com.eignex.klause.schema.VariableSchema
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
        init { constraint(Not(x.present.toExpr())) } // force absent
    }

    private class AbsentIntClamped : VariableSchema() {
        val x by optIntVar(min = 2, max = 7) // 0 ∉ domain → clamp to min = 2
        init { constraint(Not(x.present.toExpr())) }
    }

    private class AbsentBool : VariableSchema() {
        val b by optBoolVar()
        init { constraint(Not(b.present.toExpr())) }
    }

    private fun firstFeasible(compiled: CompiledProblem): com.eignex.klause.solver.Sample {
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
