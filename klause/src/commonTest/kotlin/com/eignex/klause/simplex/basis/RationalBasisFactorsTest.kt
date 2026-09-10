package com.eignex.klause.simplex.basis

import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.Cancellation
import com.ionspin.kotlin.bignum.integer.BigInteger
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration

class RationalBasisFactorsTest {
    @Test
    fun `nonsymmetric solves preserve both source permutations`() {
        val matrix = matrix(listOf(3, 2, 1), listOf(1, 4, 3), listOf(2, 1, 5))
        val orders = listOf(intArrayOf(0, 1, 2), intArrayOf(2, 0, 1), intArrayOf(1, 2, 0))
        for (rows in orders) {
            for (columns in orders) {
                val built = ready(matrix, RationalBasisOrder(rows, columns))
                assertEquals(0, built.stats.fallbacks)
                assertContentEquals(rows, built.factors.ordering().rows)
                assertContentEquals(columns, built.factors.ordering().columns)
                checkSolves(matrix, built.factors)
            }
        }
    }

    @Test
    fun `zero proposed pivots reorder from exact source support`() {
        for (matrix in listOf(
            matrix(listOf(0, 2), listOf(3, 1)),
            matrix(listOf(1, 1, 0), listOf(1, 1, 1), listOf(0, 1, 1)),
        )) {
            val hint = identityOrder(matrix.size)
            val built = ready(matrix, hint)
            assertEquals(1, built.stats.fallbacks)
            assertEquals(2, built.stats.builds)
            assertEquals(1, built.stats.proposedAttempts)
            checkSolves(matrix, built.factors)
            val standalone = ready(matrix)
            assertTrue(built.stats.work > standalone.stats.work)
            assertTrue(built.stats.allocationBytes > standalone.stats.allocationBytes)
            val limited = RationalBasisFactors.factor(matrix, hint, RationalBasisLimits(work = standalone.stats.work))
            assertEquals(RationalBasisDecline.WORK, assertIs<RationalBasisBuild.Declined>(limited).reason)
        }
    }

    @Test
    fun `exact support retains a nonzero that underflows floating point`() {
        val tiny = power(1100).reciprocal()
        assertEquals(0.0, tiny.toDouble())
        val matrix = listOf(listOf(BigFraction.ZERO, tiny), listOf(BigFraction.ONE, BigFraction.ONE))
        val built = ready(matrix, identityOrder(2))
        assertEquals(1, built.stats.restarts)
        assertEquals(1, built.stats.fallbacks)
        checkSolves(matrix, built.factors)
    }

    @Test
    fun `completed exact rank distinguishes singular matrices`() {
        val cases = listOf(
            matrix(listOf(0, 0), listOf(0, 0)) to 0,
            matrix(listOf(1, 2), listOf(2, 4)) to 1,
            matrix(listOf(1, 0, 1), listOf(0, 1, 1), listOf(1, 1, 2)) to 2,
        )
        for ((matrix, rank) in cases) {
            for (hint in listOf(null, identityOrder(matrix.size))) {
                val result = assertIs<RationalBasisBuild.Singular>(RationalBasisFactors.factor(matrix, hint))
                assertEquals(rank, result.rank)
                val limited = RationalBasisFactors.factor(
                    matrix,
                    hint,
                    RationalBasisLimits(work = result.stats.work - 1),
                )
                assertEquals(RationalBasisDecline.WORK, assertIs<RationalBasisBuild.Declined>(limited).reason)
            }
        }
    }

    @Test
    fun `build overflow restarts the entire elimination in big fractions`() {
        val large = power(100)
        val matrix = listOf(listOf(large, BigFraction.ONE), listOf(BigFraction.ONE, large))
        val built = ready(matrix)
        assertEquals(1, built.stats.restarts)
        assertEquals(2, built.stats.builds)
        assertTrue(built.stats.maxBits > 128)
        checkSolves(matrix, built.factors)
        val limited = RationalBasisFactors.factor(matrix, limits = RationalBasisLimits(work = built.stats.work - 1))
        assertEquals(RationalBasisDecline.WORK, assertIs<RationalBasisBuild.Declined>(limited).reason)
        assertEquals(1, limited.stats.restarts)
    }

    @Test
    fun `solve overflow restarts from source for each direction and RHS`() {
        val h = power(100).negated()
        val z = BigFraction.ZERO
        val o = BigFraction.ONE
        val matrix = listOf(listOf(o, h, z), listOf(z, o, h), listOf(z, z, o))
        val built = ready(matrix, identityOrder(3))
        assertEquals(0, built.stats.restarts)
        for (transpose in listOf(false, true)) {
            val rhs = if (transpose) listOf(o, z, z) else listOf(z, z, o)
            val solved = solved(built.factors, rhs, transpose)
            assertEquals(1, solved.stats.restarts)
            assertEquals(1, solved.stats.builds)
            checkAnswer(matrix, rhs, transpose, solved.values)
            val limited = built.factors.solve(rhs, transpose, RationalBasisLimits(work = solved.stats.work - 1))
            assertEquals(RationalBasisDecline.WORK, assertIs<RationalBasisSolve.Declined>(limited).reason)
            assertEquals(1, limited.stats.restarts)
            assertEquals(solved.stats, solved(built.factors, rhs, transpose).stats)
        }
        val rhs = listOf(power(200), z, z)
        val wide = solved(built.factors, rhs)
        assertEquals(1, wide.stats.restarts)
        checkAnswer(matrix, rhs, false, wide.values)
    }

    @Test
    fun `signed word boundaries convert without losing exact bits`() {
        val wide = power(100) + BigFraction.ofLong(7)
        val values = listOf(
            power(64),
            power(100),
            power(127),
            power(127).negated(),
            power(127) - BigFraction.ONE,
            wide.negated(),
            wide.reciprocal(),
        )
        for (value in values) {
            val matrix = listOf(listOf(value))
            val built = ready(matrix)
            checkSolves(matrix, built.factors)
        }
    }

    @Test
    fun `negative signed endpoint stays fixed in off diagonal entries and RHS`() {
        val endpoint = power(127).negated()
        val matrix = listOf(listOf(BigFraction.ONE, endpoint), listOf(BigFraction.ZERO, BigFraction.ONE))
        val built = ready(matrix, identityOrder(2))
        assertEquals(0, built.stats.restarts)
        val factors = ready(matrix(listOf(1, 0), listOf(0, 1))).factors
        for (transpose in listOf(false, true)) {
            val rhs = listOf(endpoint, BigFraction.ZERO)
            val result = solved(factors, rhs, transpose)
            assertEquals(0, result.stats.restarts)
            assertEquals(rhs, result.values)
        }
        checkSolves(matrix, built.factors)
    }

    @Test
    fun `input hint output and RHS mutations cannot alter retained factors`() {
        val matrix = mutableListOf(
            mutableListOf(BigFraction.ofLong(2), BigFraction.ONE),
            mutableListOf(BigFraction.ZERO, BigFraction.ofLong(3)),
        )
        val original = matrix.map { it.toList() }
        val hint = identityOrder(2)
        val factors = ready(matrix, hint).factors
        matrix[0][0] = BigFraction.ZERO
        matrix.clear()
        hint.rows.fill(1)
        hint.columns.fill(1)
        factors.ordering().rows.fill(1)
        factors.ordering().columns.fill(1)
        val rhs = mutableListOf(BigFraction.ONE, BigFraction.ONE)
        val first = solved(factors, rhs)
        rhs.fill(BigFraction.ZERO)
        (first.values as MutableList<BigFraction>).fill(BigFraction.ZERO)
        checkSolves(original, factors)
    }

    @Test
    fun `build limits decline without a rank claim`() {
        val matrix = matrix(listOf(2, 1), listOf(1, 3))
        val cases = listOf(
            RationalBasisLimits(dimension = 1) to RationalBasisDecline.DIMENSION,
            RationalBasisLimits(work = 0) to RationalBasisDecline.WORK,
            RationalBasisLimits(allocationBytes = 0) to RationalBasisDecline.MEMORY,
            RationalBasisLimits(fill = 0) to RationalBasisDecline.FILL,
            RationalBasisLimits(bits = 0) to RationalBasisDecline.BITS,
            RationalBasisLimits(time = Duration.ZERO) to RationalBasisDecline.TIME,
        )
        for ((limits, reason) in cases) {
            val declined = assertIs<RationalBasisBuild.Declined>(RationalBasisFactors.factor(matrix, limits = limits))
            assertEquals(reason, declined.reason)
            assertTrue(declined.stats.work <= limits.work)
            assertTrue(declined.stats.allocationBytes <= limits.allocationBytes)
        }
    }

    @Test
    fun `solve limits decline while factors remain reusable`() {
        val matrix = matrix(listOf(2, 1), listOf(1, 3))
        val factors = ready(matrix).factors
        val rhs = listOf(BigFraction.ONE, BigFraction.ONE)
        val cases = listOf(
            RationalBasisLimits(dimension = 1) to RationalBasisDecline.DIMENSION,
            RationalBasisLimits(work = 0) to RationalBasisDecline.WORK,
            RationalBasisLimits(allocationBytes = 0) to RationalBasisDecline.MEMORY,
            RationalBasisLimits(fill = 0) to RationalBasisDecline.FILL,
            RationalBasisLimits(bits = 0) to RationalBasisDecline.BITS,
            RationalBasisLimits(time = Duration.ZERO) to RationalBasisDecline.TIME,
        )
        for ((limits, reason) in cases) {
            for (transpose in listOf(false, true)) {
                val declined = assertIs<RationalBasisSolve.Declined>(factors.solve(rhs, transpose, limits))
                assertEquals(reason, declined.reason)
                assertTrue(declined.stats.work <= limits.work)
                assertTrue(declined.stats.allocationBytes <= limits.allocationBytes)
            }
        }
        checkSolves(matrix, factors)
    }

    @Test
    fun `cancellation interrupts construction and solves without partial publication`() {
        val matrix = matrix(listOf(3, 2, 1), listOf(1, 4, 2), listOf(2, 1, 5))
        for (after in listOf(0, 80)) {
            var polls = 0
            val result = RationalBasisFactors.factor(matrix, cancellation = Cancellation { polls++ >= after })
            assertEquals(RationalBasisDecline.CANCELLED, assertIs<RationalBasisBuild.Declined>(result).reason)
            if (after > 0) assertTrue(result.stats.work > 0)
        }
        val factors = ready(matrix).factors
        for (transpose in listOf(false, true)) {
            var polls = 0
            val result = factors.solve(
                List(3) { BigFraction.ONE },
                transpose,
                cancellation = Cancellation { polls++ >= 80 },
            )
            assertEquals(RationalBasisDecline.CANCELLED, assertIs<RationalBasisSolve.Declined>(result).reason)
            assertTrue(result.stats.work > 0)
        }
        checkSolves(matrix, factors)
    }

    @Test
    fun `resource reservations are repeatable at successful boundaries`() {
        val matrix = matrix(listOf(3, 2, 1), listOf(1, 4, 2), listOf(2, 1, 5))
        val baseline = ready(matrix)
        val limits = RationalBasisLimits(
            work = baseline.stats.work,
            allocationBytes = baseline.stats.allocationBytes,
            fill = baseline.stats.peakFill,
        )
        val repeated = assertIs<RationalBasisBuild.Ready>(RationalBasisFactors.factor(matrix, limits = limits))
        assertEquals(baseline.stats, repeated.stats)
        for (limits in listOf(
            limits.copy(work = limits.work - 1),
            limits.copy(allocationBytes = limits.allocationBytes - 1),
            limits.copy(fill = limits.fill - 1),
        )) {
            assertIs<RationalBasisBuild.Declined>(RationalBasisFactors.factor(matrix, limits = limits))
        }
        assertEquals(0, baseline.stats.restarts)
    }

    @Test
    fun `fill growth is reserved before storing Schur entries`() {
        val matrix = matrix(listOf(3, 1, 0, 0), listOf(0, 3, 1, 0), listOf(0, 0, 3, 1), listOf(1, 0, 0, 3))
        val result = RationalBasisFactors.factor(matrix, limits = RationalBasisLimits(fill = 8))
        assertEquals(RationalBasisDecline.FILL, assertIs<RationalBasisBuild.Declined>(result).reason)
        assertEquals(8, result.stats.peakFill)
        assertTrue(result.stats.work > 0)
        checkSolves(matrix, ready(matrix).factors)
    }

    @Test
    fun `bit preflight declines before an oversized unreduced operation`() {
        val matrix = matrix(listOf(7, 1), listOf(1, 7))
        val result = RationalBasisFactors.factor(matrix, limits = RationalBasisLimits(bits = 3))
        assertEquals(RationalBasisDecline.BITS, assertIs<RationalBasisBuild.Declined>(result).reason)
        assertEquals(3, result.stats.maxBits)
        assertTrue(result.stats.maxIntermediateBits <= 3)
        val factors = ready(listOf(listOf(BigFraction.ONE))).factors
        val solved = assertIs<RationalBasisSolve.Solved>(
            factors.solve(listOf(BigFraction.ONE), limits = RationalBasisLimits(bits = 2)),
        )
        assertEquals(listOf(BigFraction.ONE), solved.values)
    }

    @Test
    fun `singularity after overflow requires a completed big rank scan`() {
        val h = power(100).reciprocal()
        val matrix = listOf(listOf(h, h), listOf(h, h))
        val result = assertIs<RationalBasisBuild.Singular>(RationalBasisFactors.factor(matrix))
        assertEquals(1, result.rank)
        assertEquals(1, result.stats.restarts)
        assertEquals(2, result.stats.builds)
        val limited = RationalBasisFactors.factor(matrix, limits = RationalBasisLimits(work = result.stats.work - 1))
        assertEquals(RationalBasisDecline.WORK, assertIs<RationalBasisBuild.Declined>(limited).reason)
        assertEquals(1, limited.stats.restarts)
    }

    @Test
    fun `dimension is checked before a huge matrix can be read or allocated`() {
        val huge = object : AbstractList<List<BigFraction>>() {
            override val size: Int = Int.MAX_VALUE
            override fun get(index: Int): List<BigFraction> = error("must not read an oversized matrix")
        }
        val result = RationalBasisFactors.factor(huge, limits = RationalBasisLimits(dimension = Int.MAX_VALUE))
        assertEquals(RationalBasisDecline.DIMENSION, assertIs<RationalBasisBuild.Declined>(result).reason)
        assertEquals(0L, result.stats.allocationBytes)
    }

    @Test
    fun `invalid shapes and permutations are rejected`() {
        assertFailsWith<IllegalArgumentException> { RationalBasisFactors.factor(listOf(emptyList())) }
        val matrix = matrix(listOf(1, 0), listOf(0, 1))
        for (order in listOf(intArrayOf(0), intArrayOf(0, 0), intArrayOf(-1, 1), intArrayOf(0, 2))) {
            assertFailsWith<IllegalArgumentException> {
                RationalBasisFactors.factor(
                    matrix,
                    RationalBasisOrder(order, intArrayOf(0, 1)),
                )
            }
            assertFailsWith<IllegalArgumentException> {
                RationalBasisFactors.factor(
                    matrix,
                    RationalBasisOrder(intArrayOf(0, 1), order),
                )
            }
        }
        assertFailsWith<IllegalArgumentException> { ready(matrix).factors.solve(emptyList()) }
    }

    @Test
    fun `empty basis has empty normal and transpose solutions`() {
        for (transpose in listOf(false, true)) {
            assertEquals(emptyList(), solved(emptyFactors, emptyList(), transpose).values)
        }
    }

    @Test
    fun `sparse frozen workload accounts for proposed and standalone work`() {
        measure("sparse", matrix(listOf(3, 1, 0, 0), listOf(0, 3, 1, 0), listOf(0, 0, 3, 1), listOf(1, 0, 0, 3)))
    }

    @Test
    fun `spiked frozen workload accounts for proposed and standalone work`() {
        measure("spiked", matrix(listOf(1, 2, 3, 4), listOf(2, 1, 0, 0), listOf(1, 0, 1, 0), listOf(3, 0, 0, 1)))
    }

    @Test
    fun `dense frozen workload accounts for proposed and standalone work`() {
        measure("dense", matrix(listOf(5, 2, 1, 3), listOf(1, 7, 2, 1), listOf(3, 1, 8, 2), listOf(2, 3, 1, 9)))
    }

    @Test
    fun `near singular frozen workload retains its exact nonzero determinant`() {
        val o = BigFraction.ONE
        measure("near-singular", listOf(listOf(o, o), listOf(o, o + power(80).reciprocal())))
    }

    private fun measure(name: String, matrix: List<List<BigFraction>>) {
        val standalone = ready(matrix)
        val proposed = ready(matrix, standalone.factors.ordering())
        val expected = when (name) {
            "sparse" -> RationalBasisStats(249, 35456, 10, 7, 8, 1, 0, 0, 0)
            "spiked" -> RationalBasisStats(249, 34688, 10, 3, 5, 1, 0, 0, 0)
            "dense" -> RationalBasisStats(322, 69568, 16, 8, 13, 1, 0, 0, 0)
            "near-singular" -> RationalBasisStats(79, 25920, 4, 81, 83, 1, 0, 0, 0)
            else -> error("unfrozen workload")
        }
        assertEquals(expected, standalone.stats)
        assertEquals(
            expected.copy(
                work = expected.work - if (matrix.size == 2) 5 else 54,
                allocationBytes = expected.allocationBytes + if (matrix.size == 2) 320 else 384,
                proposedAttempts = 1,
            ),
            proposed.stats,
        )
        for ((mode, built) in listOf("standalone" to standalone, "proposed" to proposed)) {
            assertEquals(0, built.stats.fallbacks)
            assertTrue(built.stats.work > 0 && built.stats.allocationBytes > 0 && built.stats.peakFill > 0)
            assertTrue(built.stats.maxBits > 0 && built.stats.maxIntermediateBits > 0)
            println("B6a $name $mode build ${built.stats}")
            for (transpose in listOf(false, true)) {
                val rhs = List(matrix.size) { BigFraction.ofLong(it + 1L) }
                val result = solved(built.factors, rhs, transpose)
                checkAnswer(matrix, rhs, transpose, result.values)
                assertEquals(result.stats, solved(built.factors, rhs, transpose).stats)
                println("B6a $name $mode transpose=$transpose solve ${result.stats}")
            }
            checkSolves(matrix, built.factors)
        }
        assertEquals(standalone.stats.peakFill, proposed.stats.peakFill)
        assertEquals(standalone.stats.maxBits, proposed.stats.maxBits)
        assertEquals(standalone.stats.restarts, proposed.stats.restarts)
        assertEquals(standalone.stats, ready(matrix).stats)
        assertEquals(proposed.stats, ready(matrix, standalone.factors.ordering()).stats)
    }

    private fun checkSolves(matrix: List<List<BigFraction>>, factors: RationalBasisFactors) {
        for (transpose in listOf(false, true)) {
            for (offset in 0..1) {
                val rhs = List(matrix.size) { BigFraction.ofLong(it * 2L - offset) }
                checkAnswer(matrix, rhs, transpose, solved(factors, rhs, transpose).values)
            }
        }
    }

    private fun checkAnswer(
        matrix: List<List<BigFraction>>,
        rhs: List<BigFraction>,
        transpose: Boolean,
        answer: List<BigFraction>,
    ) {
        val source = if (transpose) List(matrix.size) { i -> List(matrix.size) { j -> matrix[j][i] } } else matrix
        assertEquals(reference(source, rhs), answer)
        for (i in source.indices) {
            val residual = source[i].indices.fold(BigFraction.ZERO) { sum, j -> sum + source[i][j] * answer[j] }
            assertEquals(rhs[i], residual)
        }
    }

    private fun reference(matrix: List<List<BigFraction>>, rhs: List<BigFraction>): List<BigFraction> {
        val n = matrix.size
        val augmented = Array(n) { i -> (matrix[i] + rhs[i]).toMutableList() }
        for (column in 0 until n) {
            val pivot = (column until n).first { !augmented[it][column].isZero }
            val row = augmented[column]
            augmented[column] = augmented[pivot]
            augmented[pivot] = row
            val inverse = augmented[column][column].reciprocal()
            for (j in column..n) augmented[column][j] = augmented[column][j] * inverse
            for (i in 0 until n) {
                if (i != column) {
                    val multiplier = augmented[i][column]
                    for (j in column..n) augmented[i][j] = augmented[i][j] - multiplier * augmented[column][j]
                }
            }
        }
        return List(n) { augmented[it][n] }
    }

    private fun ready(matrix: List<List<BigFraction>>, order: RationalBasisOrder? = null) =
        assertIs<RationalBasisBuild.Ready>(RationalBasisFactors.factor(matrix, order))

    private fun solved(factors: RationalBasisFactors, rhs: List<BigFraction>, transpose: Boolean = false) =
        assertIs<RationalBasisSolve.Solved>(factors.solve(rhs, transpose))

    private fun matrix(vararg rows: List<Int>): List<List<BigFraction>> =
        rows.map { row -> row.map { BigFraction.ofLong(it.toLong()) } }

    private fun identityOrder(n: Int) = RationalBasisOrder(IntArray(n) { it }, IntArray(n) { it })
    private fun power(bits: Int) = BigFraction.of(BigInteger.ONE shl bits, BigInteger.ONE)

    companion object {
        private val emptyFactors = assertIs<RationalBasisBuild.Ready>(
            RationalBasisFactors.factor(emptyList(), RationalBasisOrder(intArrayOf(), intArrayOf())),
        ).factors
    }
}
