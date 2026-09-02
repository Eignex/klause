package com.eignex.klause.backtrack

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.incumbent.Candidate
import com.eignex.klause.solver.incumbent.Publication
import com.eignex.klause.solver.incumbent.Verification
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * An untrusted producer's proposal reaches the incumbent only through composed verification and a
 * strict improvement: the whole factor set re-derives the assignment's feasibility and its objective,
 * and only then does the exchange weigh it against what already stands.
 */
class IncumbentCandidatesTest {

    private val objective = LinearObjective(intCoefficients = longArrayOf(1, 1))

    /** `b0`, plus `x0 + x1 ≤ 4` over two `[0, 5]` columns. */
    private fun problem(numRealVars: Int = 0) = Problem(
        numBoolVars = 1,
        numIntVars = 2,
        intDomains = Array(2) { IntDomain(0, 5) },
        factors = arrayOf(
            Clause(intArrayOf(Lit.make(0, true))),
            Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.LE, 4),
        ),
        numRealVars = numRealVars,
        realLower = DoubleArray(numRealVars),
        realUpper = DoubleArray(numRealVars) { 1.0 },
    )

    private fun sample(b0: Boolean, x0: Long, x1: Long, reals: DoubleArray = DoubleArray(0)) =
        Sample(booleanArrayOf(b0), longArrayOf(x0, x1), reals)

    private fun verifier(problem: Problem, cancellation: Cancellation = Cancellation.Never) =
        ComposedSampleVerifier(problem, objective, cancellation)

    private fun verify(problem: Problem, sample: Sample, objectiveValue: Double = objective.evaluate(sample)) =
        verifier(problem).verify(Candidate(sample, objectiveValue))

    @Test
    fun `a proposal the composed model satisfies is accepted`() {
        val proposal = sample(b0 = true, x0 = 1, x1 = 2)
        val accepted = assertIs<Verification.Accepted<Sample, Double>>(verify(problem(), proposal))
        assertEquals(3.0, accepted.candidate.objective)
    }

    @Test
    fun `a proposal an integer factor refutes is rejected`() {
        val rejected = assertIs<Verification.Rejected>(verify(problem(), sample(b0 = true, x0 = 3, x1 = 3)))
        assertTrue(rejected.reason.startsWith("int "), rejected.reason)
    }

    @Test
    fun `a proposal a Boolean factor refutes is rejected`() {
        val rejected = assertIs<Verification.Rejected>(verify(problem(), sample(b0 = false, x0 = 1, x1 = 2)))
        assertEquals("bool 0 = false conflicts", rejected.reason)
    }

    @Test
    fun `a value outside its declared domain is rejected`() {
        assertIs<Verification.Rejected>(verify(problem(), sample(b0 = true, x0 = 9, x1 = 0)))
    }

    @Test
    fun `an objective the assignment does not carry is rejected`() {
        val rejected = assertIs<Verification.Rejected>(
            verify(problem(), sample(b0 = true, x0 = 1, x1 = 2), objectiveValue = -100.0),
        )
        assertEquals("objective is 3.0, not the claimed -100.0", rejected.reason)
    }

    @Test
    fun `an assignment that does not cover every discrete variable is rejected`() {
        val short = Sample(booleanArrayOf(true), longArrayOf(1), DoubleArray(0))
        assertIs<Verification.Rejected>(verify(problem(), short, objectiveValue = objective.evaluate(short)))
    }

    @Test
    fun `a cancelled fixpoint leaves the proposal undecided`() {
        val verdict = verifier(problem(), Cancellation { true })
            .verify(Candidate(sample(b0 = true, x0 = 1, x1 = 2), 3.0))
        assertIs<Verification.Indeterminate>(verdict, "an unfinished fixpoint decides nothing either way")
    }

    @Test
    fun `a proposal without certified real values is rejected`() {
        val withReals = problem(numRealVars = 1)
        assertIs<Verification.Rejected>(verify(withReals, sample(b0 = true, x0 = 1, x1 = 2)))
        val certified = sample(b0 = true, x0 = 1, x1 = 2, reals = doubleArrayOf(0.5))
        assertIs<Verification.Accepted<Sample, Double>>(verify(withReals, certified))
    }

    @Test
    fun `a non-finite objective never becomes an incumbent`() {
        val exchange = minimizingSampleExchange(problem())
        assertIs<Publication.Rejected>(exchange.offer(sample(b0 = true, x0 = 1, x1 = 2), Double.NaN))
        assertNull(exchange.current())
    }

    @Test
    fun `only a strict improvement installs`() {
        val exchange = minimizingSampleExchange(problem())
        assertIs<Publication.Installed<Sample, Double>>(exchange.offer(sample(true, 1, 2), 3.0))
        assertIs<Publication.NotImproving>(exchange.offer(sample(true, 0, 3), 3.0))
        assertIs<Publication.NotImproving>(exchange.offer(sample(true, 2, 2), 4.0))
        val better = assertIs<Publication.Installed<Sample, Double>>(exchange.offer(sample(true, 0, 1), 1.0))
        assertEquals(2L, better.incumbent.version, "two installations over four offers")
    }
}
