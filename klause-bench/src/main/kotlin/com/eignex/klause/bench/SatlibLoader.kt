package com.eignex.klause.bench

import com.eignex.klause.cnf.Dimacs
import java.io.File

/** Discovers SATLIB-style DIMACS instances under `klause-bench/build/satlib/<set>/`,
 *  populated by `:klause-bench:downloadSatlib`. Capped via `-Dklause.bench.satlib.max=N`
 *  per set (default 10). */
object SatlibLoader {

    private val sets: Map<String, Boolean> = linkedMapOf(
        "uf20-91" to true,
        "uuf50-218" to false,
    )

    private val defaultMax: Int = System.getProperty("klause.bench.satlib.max")?.toIntOrNull() ?: 10

    fun discover(maxPerSet: Int = defaultMax): List<Portfolio.Entry> {
        val root = File("build/satlib").takeIf { it.isDirectory } ?: return emptyList()
        val out = mutableListOf<Portfolio.Entry>()
        for ((setName, expectedSat) in sets) {
            val setDir = File(root, setName)
            if (!setDir.isDirectory) continue
            val cnfs = setDir.walk()
                .filter { it.isFile && it.name.endsWith(".cnf") }
                .sortedBy { it.name }
                .take(maxPerSet)
                .toList()
            for (cnf in cnfs) {
                val problem = Dimacs.parse(cnf.readText())
                out += Portfolio.Entry("$setName/${cnf.nameWithoutExtension}", problem, expectedSat)
            }
        }
        return out
    }
}
