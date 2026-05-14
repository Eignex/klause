package com.eignex.klause.bench

import com.eignex.klause.formats.dimacs.Dimacs
import com.eignex.klause.solver.Problem

/** Loads bundled DIMACS-CNF instances from `klause-bench/src/main/resources/dimacs/`. */
object DimacsLoader {

    private val bundled: List<Bundled> = listOf(
        Bundled("php4", expectedSat = false),
        Bundled("random3sat-20-80", expectedSat = true),
        Bundled("random3sat-50-200", expectedSat = true),
    )

    fun loadBundled(): List<Portfolio.Entry> = bundled.map { meta ->
        val text = readResource("/dimacs/${meta.name}.cnf")
        Portfolio.Entry(meta.name, Dimacs.parse(text), meta.expectedSat)
    }

    fun loadFromPath(path: String, name: String, expectedSat: Boolean? = null): Portfolio.Entry {
        val text = java.io.File(path).readText()
        return Portfolio.Entry(name, Dimacs.parse(text), expectedSat ?: true)
    }

    private fun readResource(path: String): String =
        DimacsLoader::class.java.getResourceAsStream(path)
            ?.bufferedReader()
            ?.use { it.readText() }
            ?: error("Bundled DIMACS resource not found: $path")

    private data class Bundled(val name: String, val expectedSat: Boolean)

    fun loadProblem(name: String): Problem = Dimacs.parse(readResource("/dimacs/$name.cnf"))
}
