package com.eignex.klause.bench

import com.eignex.klause.formats.flatzinc.parseFlatZinc

/** Loads bundled FlatZinc instances from `klause-bench/src/main/resources/flatzinc/`. */
object FlatZincLoader {

    private val bundled: List<Bundled> = listOf(
        Bundled("cardinality", expectedSat = true),
        Bundled("permutation4", expectedSat = true),
        Bundled("small-linear", expectedSat = true),
    )

    fun loadBundled(): List<Portfolio.Entry> = bundled.map { meta ->
        val text = readResource("/flatzinc/${meta.name}.fzn")
        Portfolio.Entry(meta.name, parseFlatZinc(text).problem, meta.expectedSat)
    }

    fun loadFromPath(path: String, name: String, expectedSat: Boolean = true): Portfolio.Entry {
        val text = java.io.File(path).readText()
        return Portfolio.Entry(name, parseFlatZinc(text).problem, expectedSat)
    }

    private fun readResource(path: String): String =
        FlatZincLoader::class.java.getResourceAsStream(path)
            ?.bufferedReader()
            ?.use { it.readText() }
            ?: error("Bundled FlatZinc resource not found: $path")

    private data class Bundled(val name: String, val expectedSat: Boolean)
}
