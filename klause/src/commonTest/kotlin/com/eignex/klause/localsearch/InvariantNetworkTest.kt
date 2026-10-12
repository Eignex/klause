package com.eignex.klause.localsearch

import com.eignex.klause.factor.arithmetic.Product
import com.eignex.klause.factor.table.Element
import com.eignex.klause.formats.flatzinc.parseFlatZincExecution
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.bake
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.IntArrayList
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class InvariantNetworkTest {
    @Test
    fun `shared definition preparation resumes without exposing unfinished readers`() {
        val problem = Problem(
            0, 515, Array(515) { IntDomain(0, 9) },
            Array<Factor>(513) { Product(it, 514, it + 1) },
        )
        val sweep = assertNotNull(DefinitionalSweep.infer(problem))
        val direct = sweep.network(515, 0)
        val preparation = InvariantNetworkPreparation(sweep, 515, 0)
        var polls = 0

        assertFalse(preparation.get(Cancellation { ++polls >= 12 }))
        assertFailsWith<IllegalStateException> { preparation.value }
        assertFalse(preparation.get(Cancellation { true }))
        assertTrue(preparation.get(Cancellation.Never))
        val network = assertNotNull(preparation.value)
        assertTrue(preparation.get(Cancellation { true }))

        assertSame(network, preparation.value)
        assertContentEquals(direct.affectedNodes(intArrayOf(0), intArrayOf()),
            network.affectedNodes(intArrayOf(0), intArrayOf()))
        assertEquals(direct.size, network.size)
        assertTrue(network.isDefinedInt(513))
    }

    @Test
    fun `native Element cones follow selected cells and every index write`() {
        for (offset in listOf(2, Int.MAX_VALUE)) {
            val domains = Array(7) { IntDomain(0, 18) }
            domains[0] = IntDomain(offset.toLong(), offset.toLong() + 3)
            domains[6] = IntDomain(2, 2)
            val problem = Problem(
                0, 7, domains, arrayOf<Factor>(
                    Element(0, 4, longArrayOf(1, 2, 3), true, offset),
                    Product(4, 6, 5),
                ),
            )
            val state = LocalSearchState(LocalSearchModel.open(problem), Random(3))
            val network = assertNotNull(DefinitionalSweep.infer(problem, intArrayOf(4))).network(7, 0)

            val traversal = DefinitionTraversal(network)
            val ints = IntArrayList()
            val bools = IntArrayList()
            for (position in 0..3) {
                state.assignment.setInt(0, offset.toLong() + position)

                val cellCone = network.affectedNodes(intArrayOf(1), intArrayOf(), state.assignment)
                val indexCone = network.affectedNodes(intArrayOf(0), intArrayOf(), state.assignment)
                val structuralCone = network.affectedNodes(intArrayOf(1), intArrayOf())

                ints.clear()
                ints.add(1)
                ints.add(1)
                val liveCellCone = network.affectedNodes(ints, bools, state.assignment, traversal).toIntArray()
                ints.clear()
                ints.add(0)
                val liveIndexCone = network.affectedNodes(ints, bools, state.assignment, traversal).toIntArray()

                assertContentEquals(cellCone, liveCellCone)
                assertContentEquals(indexCone, liveIndexCone)
                assertEquals(if (position == 0) listOf(4, 5) else emptyList(), cellCone.map { network.node(it).out })
                assertEquals(listOf(4, 5), indexCone.map { network.node(it).out })
                assertEquals(listOf(4, 5), structuralCone.map { network.node(it).out })
            }
        }
    }

    @Test
    fun `definition propagation preserves implicitly owned outputs`() {
        val problem = Problem(
            0, 3, arrayOf(IntDomain(0, 1), IntDomain(3, 3), IntDomain(0, 3)),
            arrayOf<Factor>(Product(0, 1, 2)),
        )
        val state = LocalSearchState(LocalSearchModel.open(problem), Random(3))
        state.invariants = assertNotNull(DefinitionalSweep.infer(problem.factors, 3)).network(3, 0)
        state.moveSink.setOwners(intArrayOf(-1, -1, 7))
        state.assignment.setInt(0, 0)
        state.assignment.setInt(1, 3)
        state.assignment.setInt(2, 0)
        state.recompute()

        val predicted = state.netDelta(Move.IntSet(0, 1))
        state.apply(Move.IntSet(0, 1))

        assertEquals(0L, state.assignment.intValue(2))
        assertTrue(state.cost > 0L)
        assertEquals(state.cost, predicted)
        val cost = state.cost
        state.recompute()
        assertEquals(cost, state.cost)
    }

    @Test
    fun `definition traversal clears a larger cone before visiting an empty cone`() {
        val network = InvariantNetwork(
            List(40) { DefinitionalSweep.SweepNode.Bool2Int(it, 0) }, 41, 1,
        )
        val problem = Problem(1, 41, Array(41) { IntDomain(0, 1) }, emptyArray())
        val state = LocalSearchState(LocalSearchModel.open(problem), Random(3))
        val ints = IntArrayList()
        val bools = IntArrayList().also { it.add(0) }
        val traversal = DefinitionTraversal(network)
        val snapshot = network.affectedNodes(intArrayOf(), intArrayOf(0))

        assertContentEquals(snapshot, network.affectedNodes(ints, bools, state.assignment, traversal).toIntArray())
        bools.clear()
        ints.add(40)
        assertEquals(0, network.affectedNodes(ints, bools, state.assignment, traversal).size)
        bools.add(0)
        assertContentEquals(IntArray(40) { it },
            network.affectedNodes(ints, bools, state.assignment, traversal).toIntArray())
        assertContentEquals(IntArray(40) { it }, snapshot)
    }

    @Test
    fun `replacing a network uses its own definition cone`() {
        val problem = Problem(1, 2, Array(2) { IntDomain(0, 1) }, emptyArray())
        val state = LocalSearchState(LocalSearchModel.open(problem), Random(3))
        val first = InvariantNetwork(listOf(DefinitionalSweep.SweepNode.Bool2Int(0, 0)), 2, 1)
        val second = InvariantNetwork(listOf(DefinitionalSweep.SweepNode.Bool2Int(1, 0)), 2, 1)
        state.invariants = first
        state.recompute()
        state.apply(Move.BoolFlip(0))

        state.invariants = second
        state.netDelta(Move.BoolFlip(0))
        state.apply(Move.BoolFlip(0))

        assertEquals(1L, state.assignment.intValue(0))
        assertEquals(0L, state.assignment.intValue(1))
    }

    private val src = """
        var 0..10: x;
        var 0..10: y;
        var -10..10: dx;
        var -10..10: dy;
        var 0..10: a;
        var 0..10: b;
        var 0..10: m;
        var 0..20: s;
        constraint int_lin_eq([1, -1], [x, dx], 7) :: defines_var(dx);
        constraint int_lin_eq([1, -1], [y, dy], 2) :: defines_var(dy);
        constraint int_abs(dx, a) :: defines_var(a);
        constraint int_abs(dy, b) :: defines_var(b);
        constraint int_min(a, b, m) :: defines_var(m);
        constraint int_lin_eq([1, 1, -1], [a, b, s], 0) :: defines_var(s);
        constraint int_le(s, 6);
        solve satisfy;
    """.trimIndent()

    @Test
    fun `states sharing a definition network propagate their own assignments`() {
        val execution = parseFlatZincExecution(src)
        val program = execution.program
        val network = assertNotNull(execution.definitionalSweep)
            .network(program.problem.numIntVars, program.problem.numBoolVars)
        val problem = program.problem.bake()
        val first = LocalSearchState(problem, Random(5))
        val second = LocalSearchState(problem, Random(7))
        val iv = program.intVarsByName
        for (state in listOf(first, second)) {
            state.recompute()
            state.invariants = network
            state.apply(Move.IntSet(iv.getValue("x"), 7))
            state.apply(Move.IntSet(iv.getValue("y"), 0))
        }

        first.apply(Move.IntSet(iv.getValue("x"), 10))
        second.apply(Move.IntSet(iv.getValue("y"), 2))

        assertEquals(5, first.assignment.intValue(iv.getValue("s")))
        assertEquals(0, second.assignment.intValue(iv.getValue("s")))
    }

    @Test
    fun `nested definition scores agree with committed moves`() {
        val execution = parseFlatZincExecution(src)
        val problem = execution.program.problem.bake()
        val state = LocalSearchState(problem, Random(5))
        state.invariants = assertNotNull(execution.definitionalSweep)
            .network(problem.numIntVars, problem.numBoolVars)
        val x = execution.program.intVarsByName.getValue("x")
        val y = execution.program.intVarsByName.getValue("y")
        state.recompute()
        state.apply(Move.IntSet(x, 7))
        state.apply(Move.IntSet(y, 0))
        val before = LongArray(problem.numIntVars) { state.assignment.intValue(it) }
        val beforeCost = state.cost
        val beforeDegrees = state.factorDegree.copyOf()
        val weights = state.weights.factorWeights
        for (i in weights.indices) weights[i] = (i + 1).toDouble()

        for (move in listOf(
            Move.IntSet(x, 10), Move.IntSet(y, 7), Move.Compound(listOf(Move.IntSet(x, 10), Move.IntSet(y, 7))),
        )) {
            val predicted = state.netDelta(move)
            val weighted = state.weightedNetDelta(move)
            assertEquals(weighted, state.weightedNetDelta(move))
            assertContentEquals(before, LongArray(problem.numIntVars) { state.assignment.intValue(it) })
            assertContentEquals(beforeDegrees, state.factorDegree)
            assertEquals(beforeCost, state.cost)
            state.apply(move)
            state.recompute()

            assertEquals(state.cost - beforeCost, predicted)
            assertEquals(weights.indices.sumOf { weights[it] * (state.factorDegree[it] - beforeDegrees[it]) }, weighted)

            state.apply(Move.IntSet(x, 7))
            state.apply(Move.IntSet(y, 0))
        }
    }

    @Test
    fun `defined vars track their inputs across applied moves`() {
        val execution = parseFlatZincExecution(src)
        val program = execution.program
        val sweep = assertNotNull(execution.definitionalSweep)
        val net = sweep.network(program.problem.numIntVars, program.problem.numBoolVars)
        val iv = program.intVarsByName
        val state = LocalSearchState(program.problem.bake(), Random(5))
        state.recompute()
        state.invariants = net
        state.apply(Move.IntSet(iv.getValue("x"), 10)) // dx = 3, a = 3
        state.apply(Move.IntSet(iv.getValue("y"), 0)) // dy = -2, b = 2, m = 2, s = 5
        assertEquals(3, state.assignment.intValue(iv.getValue("a")))
        assertEquals(2, state.assignment.intValue(iv.getValue("b")))
        assertEquals(2, state.assignment.intValue(iv.getValue("m")))
        assertEquals(5, state.assignment.intValue(iv.getValue("s")))
        // Move x again: the whole cone re-propagates from one move.
        state.apply(Move.IntSet(iv.getValue("x"), 7)) // dx = 0, a = 0, m = 0, s = 2
        assertEquals(0, state.assignment.intValue(iv.getValue("a")))
        assertEquals(0, state.assignment.intValue(iv.getValue("m")))
        assertEquals(2, state.assignment.intValue(iv.getValue("s")))
        // Incremental state stayed consistent with a from-scratch recompute.
        val incCost = state.cost
        state.recompute()
        assertEquals(state.cost, incCost, "propagated incremental cost must match recompute")
    }

    @Test
    fun `defined vars are excluded from move generation`() {
        val execution = parseFlatZincExecution(src)
        val program = execution.program
        val sweep = assertNotNull(execution.definitionalSweep)
        val net = sweep.network(program.problem.numIntVars, program.problem.numBoolVars)
        val iv = program.intVarsByName
        assertTrue(net.isDefinedInt(iv.getValue("s")))
        assertFalse(net.isDefinedInt(iv.getValue("x")))
        val state = LocalSearchState(program.problem.bake(), Random(5))
        state.invariants = net
        val sink = state.moveSink
        sink.clear()
        sink.addIntSet(iv.getValue("s"), 3) // defined: dropped
        sink.addIntSet(iv.getValue("x"), 3) // free: kept
        assertEquals(1, sink.list.size, "defined-var move must be filtered at the sink")
        sink.clear()
        // Compound: the defined part drops; the lone survivor demotes to a primitive move.
        sink.addCompound(listOf(Move.IntSet(iv.getValue("s"), 3), Move.IntSet(iv.getValue("x"), 4)))
        val kept = sink.list.single() as Move.IntSet
        assertEquals(iv.getValue("x"), kept.varId)
    }
}
