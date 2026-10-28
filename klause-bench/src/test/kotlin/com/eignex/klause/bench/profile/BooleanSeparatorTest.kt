package com.eignex.klause.bench.profile

import com.eignex.klause.ir.Lit
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BooleanSeparatorTest {
    private val cycles = (0 until 2).flatMap { cycle ->
        (0 until 5).map { i ->
            listOf(Lit.make(cycle * 5 + i, true), Lit.make(cycle * 5 + (i + 1) % 5, true))
        }
    }

    @Test
    fun `separator decision should preserve the enforcing false branch`() {
        val clauses = cycles + listOf(listOf(20, 0, 10))

        val choice = assertNotNull(BooleanSeparators.choose(clauses, 11, emptyMap(), { true }))

        assertEquals(10, choice.variable)
        assertEquals(listOf(false, true), choice.branches.map { it.value })
        assertEquals(listOf(10), choice.branches[0].components.map { it.size })
        assertEquals(listOf(5, 5), choice.branches[1].components.map { it.size })
        assertTrue(choice.branches.none { it.conflict })
    }

    @ParameterizedTest
    @ValueSource(ints = [0, 1, 2])
    fun `discharged bridge should be independent of literal order`(position: Int) {
        val bridge = mutableListOf(0, 10).apply { add(position, 20) }
        val partition = assertNotNull(
            BooleanSeparators.partition(
            cycles + listOf(bridge),
            11,
            mapOf(10 to true),
            { true },
        )
        )

        assertEquals(listOf((0..4).toList(), (5..9).toList()), partition.components)
    }

    @Test
    fun `stopped analysis should publish no separator or partition`() {
        var visits = 0
        val choice = BooleanSeparators.choose(
            cycles + listOf(listOf(20, 0, 10)),
            11,
            emptyMap(),
            { ++visits < 30 },
        )

        assertNull(choice)
        assertNull(BooleanSeparators.partition(cycles, 11, emptyMap(), { false }))
    }

    @Test
    fun `fixed decision should remain fixed during separator scoring`() {
        val choice = BooleanSeparators.choose(
            cycles + listOf(listOf(20, 0, 10)),
            11,
            mapOf(10 to false),
            { true },
        )

        assertFalse(choice?.variable == 10)
    }

    @Test
    fun `natural decomposition should not count as a separator improvement`() {
        val choice = BooleanSeparators.choose(cycles, 10, emptyMap(), { true })

        assertNull(choice)
    }

    @Test
    fun `empty residual clause should remain a conflict`() {
        val result = assertNotNull(
            BooleanSeparators.partition(
            listOf(listOf(0)),
            1,
            mapOf(0 to false),
            { true },
        )
        )

        assertTrue(result.conflict)
    }
}
