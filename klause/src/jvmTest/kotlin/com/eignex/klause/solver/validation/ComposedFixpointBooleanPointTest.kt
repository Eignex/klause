package com.eignex.klause.solver.validation

import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.incumbent.Candidate
import com.eignex.klause.solver.incumbent.Verification
import com.eignex.klause.util.Cancellation
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import kotlin.test.Test
import kotlin.test.assertIs

@RunWith(Parameterized::class)
class ComposedFixpointBooleanPointTest(private val point: String, private val feasible: Boolean) {
    @Test
    fun `complete Boolean points should satisfy every original clause`() {
        val model = Problem(3, 0, emptyArray(), arrayOf(
            Clause(intArrayOf(0, 2, 4)),
            Clause(intArrayOf(1, 3)),
            Clause(intArrayOf(3, 5)),
        )).bake()
        val candidate = Candidate(Sample(BooleanArray(3) { point[it] == '1' }, longArrayOf()), Unit)

        val result = composedFixpoint(model, candidate, Cancellation.Never)

        if (feasible) assertIs<Verification.Accepted<*, *>>(result)
        else assertIs<Verification.Rejected>(result)
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun points(): List<Array<Any>> = listOf(
            arrayOf("000", false),
            arrayOf("001", true),
            arrayOf("010", true),
            arrayOf("011", false),
            arrayOf("100", true),
            arrayOf("101", true),
            arrayOf("110", false),
            arrayOf("111", false),
        )
    }
}
