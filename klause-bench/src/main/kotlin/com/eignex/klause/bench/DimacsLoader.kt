package com.eignex.klause.bench

import com.eignex.klause.cnf.Dimacs
import com.eignex.klause.solver.Problem

/**
 * Loads pre-made DIMACS-CNF problem instances bundled in
 * `klause-bench/src/main/resources/dimacs/`. Each entry is a [Portfolio.Entry] with
 * `expectedSat` set; the verifier and benchmarker consume them the same way as the
 * hard-coded portfolio.
 */
object DimacsLoader {

    /** Bundled instance metadata: resource name (without `.cnf`) and the expected outcome. */
    private val bundled: List<Bundled> = listOf(
        Bundled("php4", expectedSat = false),
        Bundled("random3sat-20-80", expectedSat = true),
        Bundled("random3sat-50-200", expectedSat = true),
    )

    fun loadBundled(): List<Portfolio.Entry> = bundled.map { meta ->
        val text = readResource("/dimacs/${meta.name}.cnf")
        Portfolio.Entry(meta.name, Dimacs.parse(text), meta.expectedSat)
    }

    /** Read a CNF file from the filesystem and wrap as a [Portfolio.Entry]. */
    fun loadFromPath(path: String, name: String, expectedSat: Boolean? = null): Portfolio.Entry {
        val text = java.io.File(path).readText()
        // Default to assuming SAT if not provided; verifier still reports the actual outcome.
        return Portfolio.Entry(name, Dimacs.parse(text), expectedSat ?: true)
    }

    private fun readResource(path: String): String =
        DimacsLoader::class.java.getResourceAsStream(path)
            ?.bufferedReader()
            ?.use { it.readText() }
            ?: error("Bundled DIMACS resource not found: $path")

    private data class Bundled(val name: String, val expectedSat: Boolean)

    /** Convenience: a fresh [Problem] (without the [Portfolio.Entry] wrapper) by bundle name. */
    fun loadProblem(name: String): Problem = Dimacs.parse(readResource("/dimacs/$name.cnf"))
}
