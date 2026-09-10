package com.eignex.klause.lp.engine

import com.eignex.klause.simplex.basis.BasisRepair
import com.eignex.klause.simplex.basis.BasisSnapshot
import com.eignex.klause.simplex.basis.BasisSolver
import com.eignex.klause.simplex.basis.KotlinBasisSolver
import com.eignex.klause.util.Cancellation
import com.eignex.koblas.SparseMatrix
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BasisRepairTest {
    @Test
    fun `repair decoding preserves permutation and maps synthesized units to logical columns`() {
        val repair = BasisRepair(intArrayOf(2, -1, 0), intArrayOf(-1, 1, -1))
        val bounds = Array(6) { BasisBoundState(hasLower = true, hasUpper = false, fixed = false) }
        val statuses = arrayOf(
            VarStatus.BASIC,
            VarStatus.BASIC,
            VarStatus.BASIC,
            VarStatus.AT_LOWER,
            VarStatus.AT_LOWER,
            VarStatus.AT_LOWER,
        )

        val decoded = assertNotNull(decodeBasisRepair(repair, 3, bounds, statuses))

        assertContentEquals(intArrayOf(2, 4, 0), decoded.headings)
        assertContentEquals(intArrayOf(2, -1, 0), decoded.ownerColumns)
        assertContentEquals(intArrayOf(-1, 1, -1), decoded.ownerUnitRows)
        assertEquals(VarStatus.AT_LOWER, decoded.statuses[1])
        assertEquals(VarStatus.BASIC, decoded.statuses[4])
        val basicColumns = decoded.statuses.indices.filter { decoded.statuses[it] == VarStatus.BASIC }
        assertEquals(setOf(0, 2, 4), basicColumns.toSet())
    }

    @Test
    fun `status normalization uses current exact side presence`() {
        val headings = intArrayOf(0)
        val bounds = arrayOf(
            BasisBoundState(true, false, false),
            BasisBoundState(true, false, false),
        )
        val statuses = arrayOf(VarStatus.AT_LOWER, VarStatus.AT_UPPER)

        val normalized = assertNotNull(normalizeBasisState(headings, bounds, statuses))

        assertEquals(VarStatus.BASIC, normalized.statuses[0])
        assertEquals(VarStatus.AT_LOWER, normalized.statuses[1])
    }

    @Test
    fun `invalid repair falls back to logicals atomically`() {
        val matrix = repairMatrix()
        val delegate = KotlinBasisSolver(matrix)
        val solver = object : BasisSolver by delegate {
            override fun refactorizeRepairing(basicIndex: IntArray): BasisRepair =
                BasisRepair(intArrayOf(0, 0), intArrayOf(-1, -1))
        }
        val repairer = EngineBasisRepairer()
        val bounds = Array(4) { BasisBoundState(true, false, false) }
        val statuses = arrayOf(VarStatus.BASIC, VarStatus.BASIC, VarStatus.AT_LOWER, VarStatus.AT_LOWER)

        val result = repairer.recover(solver, intArrayOf(0, 1), 2, bounds, statuses, null)

        val recovered = result as BasisRecoveryResult.Recovered
        assertFalse(recovered.repaired)
        assertContentEquals(intArrayOf(2, 3), recovered.state.headings)
        assertEquals(1, repairer.metrics.logicalFallbacks)
        assertEquals(1, repairer.metrics.logicalFallbackSuccesses)
        assertEquals(1, repairer.metrics.declines[BasisRepairDecline.INVALID_REPAIR])
        delegate.close()
    }

    @Test
    fun `failed fallback remains a numerical decline rather than exact singularity`() {
        val delegate = KotlinBasisSolver(repairMatrix())
        val solver = object : BasisSolver by delegate {
            override fun refactorizeRepairing(basicIndex: IntArray): BasisRepair? = null
            override fun refactorize(basicIndex: IntArray): Boolean = false
        }
        val repairer = EngineBasisRepairer()
        val bounds = Array(4) { BasisBoundState(true, false, false) }
        val statuses = arrayOf(VarStatus.BASIC, VarStatus.BASIC, VarStatus.AT_LOWER, VarStatus.AT_LOWER)

        val result = repairer.recover(solver, intArrayOf(0, 1), 2, bounds, statuses, null)

        val failed = result as BasisRecoveryResult.Failed
        assertEquals(BasisRepairDecline.LOGICAL_FALLBACK_FAILED, failed.decline)
        assertEquals(ExactBasisRankEvidence.UNAVAILABLE, failed.rankEvidence)
        assertEquals(1, repairer.metrics.logicalFallbackFailures)
        assertEquals(1, repairer.metrics.rankEvidence[ExactBasisRankEvidence.UNAVAILABLE])
        delegate.close()
    }

    @Test
    fun `small exact rank evidence separates singular and nonsingular bases`() {
        val model = exactWorkingModel(
            listOf(
                listOf(0 to 1L, 1 to 1L),
                listOf(0 to 1L, 1 to 1L),
            ),
        )

        assertEquals(ExactBasisRankEvidence.SINGULAR, exactBasisRankEvidence(model, intArrayOf(0, 1)))
        assertEquals(ExactBasisRankEvidence.NONSINGULAR, exactBasisRankEvidence(model, intArrayOf(0, 2)))
    }

    @Test
    fun `exact rank evidence declines outside its bounded dimension`() {
        val dimension = 17
        val columns = List(dimension) { column -> listOf(column to 1L) }
        val model = exactWorkingModel(columns)

        assertEquals(
            ExactBasisRankEvidence.RESOURCE_DECLINED,
            exactBasisRankEvidence(model, IntArray(dimension) { it }),
        )
    }

    @Test
    fun `exact rank evidence observes arithmetic and cancellation budgets`() {
        val model = exactWorkingModel(
            listOf(
                listOf(0 to 1L, 1 to 1L),
                listOf(0 to 1L, 1 to 2L),
            ),
        )

        assertEquals(
            ExactBasisRankEvidence.RESOURCE_DECLINED,
            exactBasisRankEvidence(
                model,
                intArrayOf(0, 1),
                ExactRankLimits(maxIntermediateDigits = 1),
            ),
        )
        assertEquals(
            ExactBasisRankEvidence.CANCELLED,
            exactBasisRankEvidence(model, intArrayOf(0, 1), cancellation = Cancellation { true }),
        )
    }

    @Test
    fun `snapshot restores an earlier basis on the same owner and current bounds`() {
        val matrix = repairMatrix()
        val solver = KotlinBasisSolver(matrix)
        assertTrue(solver.refactorize(intArrayOf(0, 1)))
        val identity = BasisMatrixIdentity(3L, 2, listOf(10L, 11L))
        val captured = EngineBasisState(
            intArrayOf(0, 1),
            arrayOf(VarStatus.BASIC, VarStatus.BASIC, VarStatus.AT_UPPER, VarStatus.AT_LOWER),
        )
        val snapshot = assertNotNull(EngineBasisRestartSnapshot.capture(solver, identity, captured))
        assertTrue(solver.refactorize(intArrayOf(2, 3)))
        val currentBounds = arrayOf(
            BasisBoundState(true, false, false),
            BasisBoundState(true, false, false),
            BasisBoundState(true, false, false),
            BasisBoundState(true, false, false),
        )

        val restored = assertNotNull(snapshot.restore(solver, identity, currentBounds)) as BasisRestartResult.Restored

        assertTrue(restored.factorsRestored)
        assertFalse(restored.factorRestoreDeclined)
        assertContentEquals(intArrayOf(0, 1), restored.state.headings)
        assertEquals(VarStatus.AT_LOWER, restored.state.statuses[2])
        snapshot.close()
        snapshot.close()
        assertNull(snapshot.restore(solver, identity, currentBounds))
        solver.close()
    }

    @Test
    fun `status-only snapshot rejects foreign owners and cancellation`() {
        val first = statusOnlySolver(2)
        val second = statusOnlySolver(2)
        val identity = BasisMatrixIdentity(0L, 0, listOf(0L, 1L))
        val state = EngineBasisState(intArrayOf(0, 1), arrayOf(VarStatus.BASIC, VarStatus.BASIC))
        val bounds = Array(2) { BasisBoundState(true, false, false) }
        val snapshot = assertNotNull(EngineBasisRestartSnapshot.capture(first, identity, state))

        assertNull(snapshot.restore(second, identity, bounds))
        val restored = assertNotNull(snapshot.restore(first, identity, bounds)) as BasisRestartResult.Restored
        assertFalse(restored.factorsRestored)
        assertFalse(restored.factorRestoreDeclined)
        snapshot.close()
        val cancelledSnapshot = assertNotNull(EngineBasisRestartSnapshot.capture(first, identity, state))
        val cancelled = assertNotNull(
            cancelledSnapshot.restore(first, identity, bounds, Cancellation { true }),
        ) as BasisRestartResult.Cancelled
        assertFalse(cancelled.factorsMayHaveChanged)
    }

    @Test
    fun `restore false preserves a compatible discrete restart`() {
        var closes = 0
        val factor = object : BasisSnapshot {
            override fun close() {
                closes++
            }
        }
        val solver = object : BasisSolver by statusOnlySolver(1) {
            override fun snapshot(): BasisSnapshot = factor
            override fun restore(snapshot: BasisSnapshot): Boolean = false
        }
        val identity = BasisMatrixIdentity(0L, 0, listOf(0L))
        val snapshot = assertNotNull(
            EngineBasisRestartSnapshot.capture(
                solver,
                identity,
                EngineBasisState(intArrayOf(0), arrayOf(VarStatus.BASIC)),
            ),
        )

        val restored = assertNotNull(
            snapshot.restore(solver, identity, arrayOf(BasisBoundState(true, false, false))),
        ) as BasisRestartResult.Restored

        assertFalse(restored.factorsRestored)
        assertTrue(restored.factorRestoreDeclined)
        snapshot.close()
        snapshot.close()
        assertEquals(1, closes)
    }

    @Test
    fun `invalid snapshot shape does not create a factor handle`() {
        var snapshots = 0
        val delegate = statusOnlySolver(2)
        val solver = object : BasisSolver by delegate {
            override fun snapshot(): BasisSnapshot? {
                snapshots++
                return null
            }
        }
        val invalid = EngineBasisState(intArrayOf(0), arrayOf(VarStatus.BASIC, VarStatus.AT_LOWER))
        val missingColumns = EngineBasisState(
            intArrayOf(0, 1),
            arrayOf(VarStatus.BASIC, VarStatus.BASIC),
            ownerColumns = intArrayOf(),
        )
        val missingUnits = EngineBasisState(
            intArrayOf(0, 1),
            arrayOf(VarStatus.BASIC, VarStatus.BASIC),
            ownerUnitRows = intArrayOf(),
        )

        val identity = BasisMatrixIdentity(0L, 0, listOf(0L, 1L))
        assertNull(EngineBasisRestartSnapshot.capture(solver, identity, invalid))
        assertNull(EngineBasisRestartSnapshot.capture(solver, identity, missingColumns))
        assertNull(EngineBasisRestartSnapshot.capture(solver, identity, missingUnits))
        assertEquals(0, snapshots)
    }

    private fun repairMatrix(): SparseMatrix = SparseMatrix.ofColumns(
        2,
        4,
        listOf(
            listOf(0 to 1.0),
            listOf(1 to 1.0),
            listOf(0 to 1.0),
            listOf(1 to 1.0),
        ),
    )

    private fun statusOnlySolver(size: Int): BasisSolver = object : BasisSolver {
        override val n = size
        override val nnz = 0
        override val updateCount = 0
        override val singular = false
        override val rcond = 1.0
        override fun refactorize(basicIndex: IntArray) = false
        override fun ftran(x: com.eignex.klause.simplex.basis.IndexedVector, expectedDensity: Double) = Unit
        override fun btran(x: com.eignex.klause.simplex.basis.IndexedVector, expectedDensity: Double) = Unit
        override fun update(
            pivotRow: Int,
            entering: Int,
            spike: com.eignex.klause.simplex.basis.IndexedVector,
            pivotEta: com.eignex.klause.simplex.basis.IndexedVector?,
        ) = com.eignex.klause.simplex.basis.BasisUpdate.SINGULAR

        override fun solveQuality(
            rhs: DoubleArray,
            solution: com.eignex.klause.simplex.basis.IndexedVector,
            transpose: Boolean,
        ) = com.eignex.klause.simplex.basis.BasisSolveQuality(0.0, 0.0)
    }

    private fun exactWorkingModel(columns: List<List<Pair<Int, Long>>>): LpModel {
        val rows = columns.maxOfOrNull { column -> column.maxOfOrNull { it.first } ?: -1 }?.plus(1) ?: 0
        val zero = ExactLpNumber.of(0L)
        val source = ExactLpModel(
            columns.map { column -> column.map { (row, value) -> ExactLpEntry(row, ExactLpNumber.of(value)) } },
            List(rows) { zero },
            List(columns.size + rows) { ExactLpColumn(ExactLpBounds(ExactLpSide(zero))) },
            List(rows) { ExactLpRow() },
            ExactLpObjective(List(columns.size + rows) { zero }),
        )
        return assertNotNull(LpExactState(source).toWorkingModel())
    }
}
