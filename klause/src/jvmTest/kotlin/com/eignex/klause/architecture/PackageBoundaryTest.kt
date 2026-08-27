package com.eignex.klause.architecture

import kotlin.io.path.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.walk
import kotlin.test.Test
import kotlin.test.assertEquals

class PackageBoundaryTest {
    @Test
    fun `lowering does not import LP engine checked arithmetic helpers`() {
        val root = locateProjectRoot() ?: error("Failed to locate repository root")
        val target = root.resolve("klause/src/commonMain/kotlin/com/eignex/klause/lowering")
        val files = target.walk().filter { it.toString().endsWith(".kt") }.toList()
        val violations = files.flatMap { file ->
            file.readText().lineSequence()
                .map { it.trim() }
                .filter { it.startsWith("import ") }
                .filter { it.startsWith("import com.eignex.klause.lp.engine.") }
                .map { "${file.fileName}: $it" }
                .toList()
        }
        assertEquals(
            emptyList<String>(),
            violations,
            buildString {
                append("Unexpected lowering→engine dependency on lp.engine checked arithmetic helpers:")
                append('\n')
                append(violations.joinToString("\n"))
            },
        )
    }

    @Test
    fun `solver pipeline does not import formats or lowering internals`() {
        val root = locateProjectRoot() ?: error("Failed to locate repository root")
        val target = root.resolve("klause/src/commonMain/kotlin/com/eignex/klause/solver/pipeline")
        val files = target.walk().filter { it.toString().endsWith(".kt") }.toList()
        val violations = files.flatMap { file ->
            file.readText().lineSequence()
                .map { it.trim() }
                .filter { it.startsWith("import ") }
                .filter {
                    it.startsWith("import com.eignex.klause.formats.") ||
                        it.startsWith("import com.eignex.klause.lowering.")
                }
                .map { "${file.fileName}: $it" }
                .toList()
        }
        assertEquals(
            emptyList<String>(),
            violations,
            buildString {
                append("Unexpected solver.pipeline→format/lowering dependency:")
                append('\n')
                append(violations.joinToString("\n"))
            },
        )
    }
}

private fun locateProjectRoot(): Path? {
    val candidates = generateSequence(Path(System.getProperty("user.dir"))) { it.parent }
        .take(6)
        .flatMap { base ->
            sequenceOf(
                base,
                base.resolve("klause"),
            )
        }
        .distinct()
    return candidates.firstOrNull { it.resolve("klause/src/commonMain/kotlin/com/eignex/klause").exists() }
}
