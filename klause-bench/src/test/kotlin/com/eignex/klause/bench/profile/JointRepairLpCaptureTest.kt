package com.eignex.klause.bench.profile

import kotlin.test.Test
import kotlin.test.assertEquals

class JointRepairLpCaptureTest {
    @Test
    fun `root queries and repeated conditions should preserve conditional capacity`() {
        val window = JointRepairLpCapture.ConditionWindow(2)
        val first = Any()
        val second = Any()
        val captured = ArrayList<Int>()

        window.observe(null, capture = captured::add)
        window.observe(first, capture = captured::add)
        window.observe(first, capture = captured::add)
        window.observe(second, capture = captured::add)
        window.observe(Any(), capture = captured::add)

        assertEquals(listOf(0, 1), captured)
        assertEquals(2, window.size)
    }

    @Test
    fun `equal conditions with separate identities should retain separate attempts`() {
        val window = JointRepairLpCapture.ConditionWindow(2)
        val captured = ArrayList<Int>()
        val first = String(charArrayOf('x'))
        val second = String(charArrayOf('x'))

        window.observe(first, capture = captured::add)
        window.observe(second, capture = captured::add)

        assertEquals(listOf(0, 1), captured)
    }
    @Test
    fun `changed model versions should retain a fresh snapshot within the capture cap`() {
        val window = JointRepairLpCapture.ConditionWindow(2)
        val condition = Any()
        val captured = ArrayList<Int>()

        window.observe(condition, "first", capture = captured::add)
        window.observe(condition, "first", capture = captured::add)
        window.observe(condition, "second", capture = captured::add)
        window.observe(condition, "third", capture = captured::add)

        assertEquals(listOf(0, 1), captured)
        assertEquals(2, window.size)
    }

}
