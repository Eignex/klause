package com.eignex.klause.bench

import com.eignex.klause.cnf.Dimacs
import java.io.File

/**
 * Discovers SATLIB-style DIMACS instances under `klause-bench/build/satlib/<set>/`.
 * Empty when the bench has not been run with `:klause-bench:downloadSatlib` yet.
 *
 * Two SATLIB sets ship out of the box: `uf20-91` (1000 SAT instances, 20 vars / 91 clauses)
 * and `uuf50-218` (1000 UNSAT instances, 50 vars / 218 clauses). To keep run time bounded,
 * the harness samples up to [maxPerSet] (default 10) instances per set, sorted by
 * filename, deterministic by default. Override via the `klause.bench.satlib.max` JVM
 * system property.
 */
object SatlibLoader {

    /** Sets to scan, mapped to the expected SAT outcome. */
    private val sets: Map<String, Boolean> = linkedMapOf(
        "uf20-91" to true,
        "uuf50-218" to false,
    )

    /** Default sample count per set; override with `-Dklause.bench.satlib.max=N`. */
    private val defaultMax: Int = System.getProperty("klause.bench.satlib.max")?.toIntOrNull() ?: 10

    /** Discover instances. Resolves `build/satlib/` relative to the JVM working directory
     *  (which is the `klause-bench/` module root when launched via `:klause-bench:run`). */
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
