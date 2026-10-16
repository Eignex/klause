package com.eignex.klause.cli

import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertTrue

@RunWith(Parameterized::class)
class SourceSolvableTest(private val direction: String) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun directions(): List<String> = listOf("minimize", "maximize")
    }

    @Test
    fun `XCSP optimization should publish exact source units beyond binary64 integer precision`() {
        val source = File.createTempFile("xcsp-source-setup", ".xml").apply {
            writeText(
                """
                <instance format="XCSP3" type="COP">
                  <variables><var id="x">0..2</var></variables>
                  <objectives><$direction type="sum">
                    <list>x</list><coeffs>9007199254740993</coeffs>
                  </$direction></objectives>
                </instance>
                """.trimIndent(),
            )
        }
        val bytes = ByteArrayOutputStream()
        val previous = System.out
        val stream = PrintStream(bytes)

        try {
            System.setOut(stream)
            main(arrayOf("--format", "xcsp3", "-e", "cp", "-p", "1", source.absolutePath))
        } finally {
            System.setOut(previous)
            stream.close()
            source.delete()
        }

        val output = bytes.toString(Charsets.UTF_8.name())
        val objective = if (direction == "maximize") "18014398509481986" else "0"
        val value = if (direction == "maximize") "2" else "0"
        assertTrue("o $objective" in output.lines(), output)
        assertTrue("s OPTIMUM FOUND" in output.lines(), output)
        assertTrue("<values> $value </values>" in output, output)
    }
}
