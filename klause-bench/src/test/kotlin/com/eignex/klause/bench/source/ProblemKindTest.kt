package com.eignex.klause.bench.source

import com.eignex.klause.bench.catalog.Category
import com.eignex.klause.bench.catalog.Expected
import com.eignex.klause.bench.catalog.Format
import com.eignex.klause.bench.catalog.ProblemRef
import com.eignex.klause.bench.catalog.corpus
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProblemKindTest {

    @Test
    fun `weighted maxsat should be COP`() {
        val ref = ProblemRef(
            "conditional-cost",
            Format.WCNF,
            corpus("conditional-symmetry/conditional-cost.wcnf"),
            Category.OPTIMIZATION,
            Expected.Unknown,
        )

        assertTrue(ProblemKind.isCop(ref))
    }

    @Test
    fun `single-line solve minimize should be COP`() {
        assertTrue(ProblemKind.hasSolveObjective("solve minimize objective;"))
    }

    @Test
    fun `multi-line solve item with search annotation should be COP`() {
        val model = """
            constraint forall(i in 1..n)(x[i] >= 0);
            solve
                :: int_search(u ++ [x[i, j] | i in 1..N, j in 1..N],
                    first_fail, indomain_min, complete)
                minimize objective;
        """.trimIndent()
        assertTrue(ProblemKind.hasSolveObjective(model))
    }

    @Test
    fun `solve satisfy should not be COP`() {
        assertFalse(ProblemKind.hasSolveObjective("solve satisfy;"))
    }

    @Test
    fun `commented-out objective should not be COP`() {
        assertFalse(ProblemKind.hasSolveObjective("% solve minimize objective;\nsolve satisfy;"))
    }

    @Test
    fun `solve-prefixed identifier should not match as a solve item`() {
        assertFalse(ProblemKind.hasSolveObjective("constraint solve_count = maximize_calls;\nsolve satisfy;"))
    }
}
