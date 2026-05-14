package com.eignex.klause.bench

import com.eignex.klause.formats.dimacs.Dimacs
import com.eignex.klause.formats.flatzinc.parseFlatZinc
import com.eignex.klause.formats.json.JsonSchema
import com.eignex.klause.formats.opb.Opb
import com.eignex.klause.formats.opb.OpbProblem
import com.eignex.klause.solver.Problem
import java.io.File

/**
 * One bundled-sample loader per supported format. Each implementation lists which sample
 * files ship under `klause-bench/src/main/resources/<dir>/` and wraps the format-specific
 * parser from `com.eignex.klause.formats.*` into [Portfolio.Entry]s the bench harness can
 * verify and benchmark.
 */
interface ProblemLoader {
    /** Short tag used in headers (e.g. "DIMACS", "FlatZinc"). */
    val format: String

    /** Sample problems shipped with this loader. */
    fun loadBundled(): List<Portfolio.Entry>

    /** Parse a single problem from a file path. */
    fun loadFromPath(path: String, name: String, expectedSat: Boolean = true): Portfolio.Entry
}

/** DIMACS CNF samples from `resources/dimacs/`. */
object DimacsLoader : ProblemLoader {
    override val format = "DIMACS"

    private val bundled = listOf(
        Bundled("php4", expectedSat = false),
        Bundled("random3sat-20-80", expectedSat = true),
        Bundled("random3sat-50-200", expectedSat = true),
    )

    override fun loadBundled(): List<Portfolio.Entry> = bundled.map { meta ->
        Portfolio.Entry(meta.name, Dimacs.parse(readBenchResource("/dimacs/${meta.name}.cnf")), meta.expectedSat)
    }

    override fun loadFromPath(path: String, name: String, expectedSat: Boolean): Portfolio.Entry =
        Portfolio.Entry(name, Dimacs.parse(File(path).readText()), expectedSat)

    fun loadProblem(name: String): Problem = Dimacs.parse(readBenchResource("/dimacs/$name.cnf"))
}

/** Pseudo-Boolean OPB samples from `resources/opb/`. */
object OpbLoader : ProblemLoader {
    override val format = "OPB"

    private val bundled = listOf(
        Bundled("setcover-tiny", expectedSat = true),
    )

    override fun loadBundled(): List<Portfolio.Entry> = bundled.map { meta ->
        Portfolio.Entry(meta.name, loadOpb(meta.name).problem, meta.expectedSat)
    }

    override fun loadFromPath(path: String, name: String, expectedSat: Boolean): Portfolio.Entry =
        Portfolio.Entry(name, Opb.parse(File(path).readText()).problem, expectedSat)

    /** Get the full [OpbProblem] (carries the linear objective) for `:minimize` benchmarks. */
    fun loadOpb(name: String): OpbProblem = Opb.parse(readBenchResource("/opb/$name.opb"))
}

/** JSON `SchemaDef<SchemaEntry>` samples from `resources/schema/`. */
object JsonSchemaLoader : ProblemLoader {
    override val format = "JSON-Schema"

    private val bundled = listOf(
        Bundled("campaign", expectedSat = true),
    )

    override fun loadBundled(): List<Portfolio.Entry> = bundled.map { meta ->
        Portfolio.Entry(meta.name, JsonSchema.parseProblem(readBenchResource("/schema/${meta.name}.json")), meta.expectedSat)
    }

    override fun loadFromPath(path: String, name: String, expectedSat: Boolean): Portfolio.Entry =
        Portfolio.Entry(name, JsonSchema.parseProblem(File(path).readText()), expectedSat)
}

/** FlatZinc samples from `resources/flatzinc/`. */
object FlatZincLoader : ProblemLoader {
    override val format = "FlatZinc"

    private val bundled = listOf(
        Bundled("cardinality", expectedSat = true),
        Bundled("permutation4", expectedSat = true),
        Bundled("small-linear", expectedSat = true),
    )

    override fun loadBundled(): List<Portfolio.Entry> = bundled.map { meta ->
        Portfolio.Entry(meta.name, parseFlatZinc(readBenchResource("/flatzinc/${meta.name}.fzn")).problem, meta.expectedSat)
    }

    override fun loadFromPath(path: String, name: String, expectedSat: Boolean): Portfolio.Entry =
        Portfolio.Entry(name, parseFlatZinc(File(path).readText()).problem, expectedSat)
}

/**
 * SATLIB-style DIMACS instances under `klause-bench/build/satlib/<set>/`, populated by
 * `:klause-bench:downloadSatlib`. Capped via `-Dklause.bench.satlib.max=N` per set
 * (default 10). Unlike the bundled-resource loaders, this discovers files on disk; it's
 * still a [ProblemLoader] so the bench harness can drive it uniformly.
 */
object SatlibLoader : ProblemLoader {
    override val format = "SATLIB"

    private val sets: Map<String, Boolean> = linkedMapOf(
        "uf20-91" to true,
        "uuf50-218" to false,
    )

    private val defaultMax: Int = System.getProperty("klause.bench.satlib.max")?.toIntOrNull() ?: 10

    override fun loadBundled(): List<Portfolio.Entry> = discover(defaultMax)

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
                out += Portfolio.Entry("$setName/${cnf.nameWithoutExtension}", Dimacs.parse(cnf.readText()), expectedSat)
            }
        }
        return out
    }

    override fun loadFromPath(path: String, name: String, expectedSat: Boolean): Portfolio.Entry =
        Portfolio.Entry(name, Dimacs.parse(File(path).readText()), expectedSat)
}

private data class Bundled(val name: String, val expectedSat: Boolean)

/** Read a classpath resource bundled with `klause-bench/src/main/resources/`. */
private fun readBenchResource(path: String): String =
    Bundled::class.java.getResourceAsStream(path)
        ?.bufferedReader()
        ?.use { it.readText() }
        ?: error("Bundled bench resource not found: $path")
