package com.eignex.klause.bench.profile

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.portfolio.EngineMix
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.objective.LinearObjective
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.jsonArray
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

class MpsLpAdmissionTest {
    @ParameterizedTest
    @EnumSource(value = EngineMix::class, names = ["MIXED", "BACKTRACK"])
    fun `recipe inspection accepts the current portfolio fact API`(mix: EngineMix) {
        val problem = Problem(0, 1, arrayOf(IntDomain(0, 2)),
            listOf(Linear(intArrayOf(1), intArrayOf(0), LinearOp.GE, 1))).bake()
        val objective = LinearObjective(intCoefficients = longArrayOf(1))

        val report = MpsLpAdmission.recipes(problem, objective, mix)

        assertTrue(assertNotNull(report["arms"]).jsonArray.isNotEmpty())
    }
}
