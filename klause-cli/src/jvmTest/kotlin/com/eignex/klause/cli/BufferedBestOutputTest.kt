package com.eignex.klause.cli

import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.Sample
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class BufferedBestOutputTest(private val format: String) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun formats(): List<String> = listOf("wcnf", "opb/wbo")
    }

    private val mode = if (format == "wcnf") WcnfMode else OpbMode
    private val first = if (format == "wcnf") "v 10" else "v x1 -x2"
    private val second = if (format == "wcnf") "v 01" else "v -x1 x2"

    @Test
    fun `multiple improvements should flush projected witnesses even without completion`() {
        val output = mode.newSession().output(CommonOptions())
        val samples = listOf(
            Sample(booleanArrayOf(true, false, true), LongArray(0)),
            Sample(booleanArrayOf(false, true, false), LongArray(0)),
        )
        val models = samples.map {
            if (format == "wcnf") renderWcnfModel(2, it) else renderPbModel(2, it)
        }

        capture { bytes ->
            output.begin(optimize = true, maximize = false)
            output.onSolution(models[0], Long.MAX_VALUE)
            assertEquals("o 9223372036854775807\n$first\n", bytes.toString())
            output.onSolution(models[1], 9007199254740993L)

            assertEquals("o 9223372036854775807\n$first\no 9007199254740993\n$second\n", bytes.toString())
        }
    }

    @Test
    fun `exact improvements should flush witnesses and complete without duplicating the optimum`() {
        val output = mode.newSession().output(CommonOptions())

        val text = capture { bytes ->
            output.begin(optimize = true, maximize = false)
            output.onExactSolution(first, BigFraction.ofLong(Long.MAX_VALUE))
            output.onExactSolution(second, BigFraction.ofLong(9007199254740993L))
            assertEquals("o 9223372036854775807\n$first\no 9007199254740993\n$second\n", bytes.toString())
            output.onComplete(Verdict.OPTIMAL)
        }

        assertEquals("o 9223372036854775807\n$first\no 9007199254740993\n$second\ns OPTIMUM FOUND\n", text)
    }

    @Test
    fun `incomplete optimization should report feasibility without repeating its streamed witness`() {
        val output = mode.newSession().output(CommonOptions())

        val text = capture {
            output.begin(optimize = true, maximize = false)
            output.onSolution(first, 7L)
            output.onComplete(Verdict.BEST_FOUND)
        }

        assertEquals("o 7\n$first\ns SATISFIABLE\n", text)
    }

    @Test
    fun `satisfaction should retain its assignment until terminal status`() {
        val output = mode.newSession().output(CommonOptions())

        val text = capture { bytes ->
            output.begin(optimize = false, maximize = false)
            output.onSolution(first, null)
            assertEquals("", bytes.toString())
            output.onComplete(Verdict.SATISFIABLE)
        }

        assertEquals("s SATISFIABLE\n$first\n", text)
    }

    private fun capture(block: (ByteArrayOutputStream) -> Unit): String {
        val bytes = ByteArrayOutputStream()
        val previous = System.out
        val stream = PrintStream(BufferedOutputStream(bytes), false)
        try {
            System.setOut(stream)
            block(bytes)
        } finally {
            System.setOut(previous)
            stream.close()
        }
        return bytes.toString()
    }
}
