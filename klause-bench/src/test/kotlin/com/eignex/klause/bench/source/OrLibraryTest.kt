package com.eignex.klause.bench.source

import com.eignex.klause.formats.mps.Mps
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OrLibraryTest {
    @Test
    fun `a set covering instance covers each row at least once`() {
        val (name, mps) = OrLibrary.setCovering("scp0", "2 3\n1 2 3\n2 1 2\n1 3\n").single()

        assertEquals("scp0", name)
        assertTrue(" G r1" in mps && " G r2" in mps && "x2 r1 1" in mps && "x3 r2 1" in mps, mps)
    }

    @Test
    fun `a generalised assignment file yields one maximised model per problem`() {
        val text = "2\n1 2\n5 6\n1 1\n2\n1 1\n7\n3\n4\n"

        val models = OrLibrary.generalisedAssignment("gap0", text)

        assertEquals(listOf("gap0-1", "gap0-2"), models.map { it.first })
        assertTrue(models.all { "OBJSENSE\n    MAX" in it.second })
    }

    @Test
    fun `a bin packing model has a tenth spare bins above the best known count`() {
        val text = "1\nu0\n10 20 10\n" + List(20) { "5" }.joinToString(" ")

        val mps = OrLibrary.binPacking("binpack0", text).single().second

        assertEquals(11, Regex("^ {4}y\\d+ obj", RegexOption.MULTILINE).findAll(mps).count())
    }

    @Test
    fun `a converted model reads back as MPS`() {
        val mps = OrLibrary.multidimensionalKnapsack("mknap0", "1\n2 1 0\n3 4\n1 2\n2\n").single().second

        val model = Mps.parse(mps)

        assertEquals(2 to 1, model.variables.size to model.constraints.size)
    }
}
