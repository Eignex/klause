package com.eignex.klause.portfolio

import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.incumbent.ModelIdentity
import kotlin.test.Test
import kotlin.test.assertFailsWith

class PortfolioWorkerTest {
    @Test
    fun `a bound worker cannot replace its model or objective identity`() {
        val source = Any()
        val objective = Any()
        val identity = ModelIdentity.of(source, objective)
        for (foreign in listOf(ModelIdentity.of(Any(), objective), ModelIdentity.of(source, Any()))) {
            val worker = PortfolioWorker.ofSolve("fixture", 0) { _, _ -> SolveResult.Unsat() }
                .bindEvidence(identity)

            assertFailsWith<IllegalArgumentException> { worker.bindEvidence(foreign) }
        }
    }
}
