package com.eignex.klause.bench

import com.eignex.klause.cnf.Opb
import com.eignex.klause.cnf.OpbProblem

/** Loads bundled OPB instances from `klause-bench/src/main/resources/opb/`.
 *  Use [loadOpb] when the harness needs the carried objective for `minimize`. */
object OpbLoader {

    private val bundled: List<Bundled> = listOf(
        Bundled("setcover-tiny", expectedSat = true),
    )

    fun loadBundled(): List<Portfolio.Entry> = bundled.map { meta ->
        val opb = loadOpb(meta.name)
        Portfolio.Entry(meta.name, opb.problem, meta.expectedSat)
    }

    fun loadOpb(name: String): OpbProblem {
        val text = readResource("/opb/$name.opb")
        return Opb.parse(text)
    }

    fun loadFromPath(path: String, name: String, expectedSat: Boolean = true): Portfolio.Entry {
        val text = java.io.File(path).readText()
        return Portfolio.Entry(name, Opb.parse(text).problem, expectedSat)
    }

    private fun readResource(path: String): String =
        OpbLoader::class.java.getResourceAsStream(path)
            ?.bufferedReader()
            ?.use { it.readText() }
            ?: error("Bundled OPB resource not found: $path")

    private data class Bundled(val name: String, val expectedSat: Boolean)
}
