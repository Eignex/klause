package com.eignex.klause.factor.bool

import com.eignex.klause.factor.arithmetic.ReifiedPseudoBoolean
import com.eignex.klause.model.PbOp
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.VarRemap
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PseudoBooleanFactorTest {

    @Test
    fun `mixed repeated literals retain their source terms`() {
        val factor = PseudoBoolean(
            weights = longArrayOf(4, 3),
            literals = intArrayOf(Lit.make(0, true), Lit.make(0, false)),
            op = PbOp.LE,
            bound = 3,
        )
        assertContentEquals(longArrayOf(4, 3), factor.weights)
        assertContentEquals(intArrayOf(Lit.make(0, true), Lit.make(0, false)), factor.literals)
    }

    @Test
    fun `remapping an identified pair retains both terms`() {
        val factor = PseudoBoolean(
            weights = longArrayOf(2, 3),
            literals = intArrayOf(Lit.make(0, true), Lit.make(1, true)),
            op = PbOp.LE,
            bound = 4,
        )
        val mapped = factor.remap(VarRemap(intArrayOf(0, 0), intArrayOf())) as PseudoBoolean

        assertContentEquals(longArrayOf(2, 3), mapped.weights)
        assertContentEquals(intArrayOf(Lit.make(0, true), Lit.make(0, true)), mapped.literals)
        assertEquals(4L, mapped.bound)
    }

    @Test
    fun `parallel pseudo Boolean arrays must have equal lengths`() {
        assertFailsWith<IllegalArgumentException> {
            PseudoBoolean(longArrayOf(1), intArrayOf(Lit.make(0, true), Lit.make(1, true)), PbOp.LE, 1)
        }
    }

    @Test
    fun `pseudo Boolean sums outside Long range are rejected`() {
        assertFailsWith<IllegalArgumentException> {
            PseudoBoolean(longArrayOf(Long.MAX_VALUE, 1), intArrayOf(Lit.make(0, true), Lit.make(1, true)), PbOp.LE, 0)
        }
    }

    @Test
    fun `reified pseudo Boolean body cannot contain its indicator`() {
        assertFailsWith<IllegalArgumentException> {
            ReifiedPseudoBoolean(0, longArrayOf(1), intArrayOf(Lit.make(0, true)), PbOp.EQ, 1)
        }
    }

    @Test
    fun `reified pseudo Boolean retains an extremal bound`() {
        val factor = ReifiedPseudoBoolean(0, longArrayOf(1), intArrayOf(Lit.make(1, true)), PbOp.LE, Long.MAX_VALUE)

        assertEquals(Long.MAX_VALUE, factor.bound)
    }
}
