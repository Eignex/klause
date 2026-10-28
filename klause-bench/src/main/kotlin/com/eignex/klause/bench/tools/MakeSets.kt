package com.eignex.klause.bench.tools

import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import kotlin.io.path.createDirectories
import kotlin.io.path.name
import kotlin.io.path.readLines
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Draw klause-bench's named problem sets from classified problems and the lab's reference results:
 *
 *     klause-bench/scripts/draw-sets.sh <features-dir> <references.csv> <sets-dir> [set,…]
 *
 * `<features-dir>` holds `klause-bench select suite=<s> per-family=1000000 features=true` output, one `<suite>.jsonl`
 * per suite. `<references.csv>` is the lab's reference_rows as `collection,problem,feasible,proven,elapsed_ms`
 * (feasible blank when undecided). Each focused set takes its [SIZES] count of problems of one theme, mixing
 * difficulties by how fast the reference proved them, spread over suites and families; a problem the reference did not
 * prove is left out. A problem goes in the first set that takes it, so `sweep`, which includes them all, holds each
 * once. Naming sets draws only those and leaves the other set files as they are: a new theme joins `sweep` without
 * redrawing the rest. The draws are those the Python script this replaces made: its seeded Mersenne Twister and shuffle.
 */
internal object MakeSets {
    /** Focused sets in the order they draw, each the theme it covers. */
    val SETS = listOf("open-int", "linear-real", "nonlinear-real", "mip", "scheduling", "routing", "packing", "globals", "sat",
        "maxsat", "pb")
    /**
     * Formats a set leaves out: MPS models with continuous columns are covered by `mip`, so `linear-real` is SMT and
     * MiniZinc, where they would otherwise outnumber the SMT-LIB logics it is for.
     */
    private val EXCLUDED_FORMATS = mapOf("linear-real" to setOf("MPS"))
    /** Each set's size: CSP/COP and SMT weigh more than MPS, SAT and PB. 275 in all. */
    val SIZES = mapOf("globals" to 30, "scheduling" to 25, "routing" to 20, "packing" to 20, "open-int" to 35, "linear-real" to 35,
        "nonlinear-real" to 25, "mip" to 25, "sat" to 20, "pb" to 20, "maxsat" to 20)
    /**
     * The most of a set's picks in a tier one suite may take: hakank's many small models would otherwise fill the
     * MiniZinc share of every set it has a theme in.
     */
    private val SUITE_SHARE = mapOf("hakank" to 0.15)
    /** Sets made of others: `linear` is open-domain integers with linear reals as well. */
    private val UNIONS = mapOf("linear" to listOf("open-int", "linear-real"), "sweep" to SETS)
    /**
     * Difficulty tiers by the reference's time to prove. A set takes [EASY_SHARE] easy, [HARD] hard and medium for the
     * rest: over the 250, about 100 easy, 140 medium and 10 hard.
     */
    private val TIERS = listOf(Tier("easy", 0, 1_000), Tier("medium", 1_000, 10_000), Tier("hard", 10_000, 60_001))
    private const val EASY_SHARE = 0.4
    private const val HARD = 1
    private const val SEED = 1L

    private data class Tier(val name: String, val low: Long, val high: Long)

    /** A problem as a set lists it: `<suite>/<problem>`, drawn per family. */
    data class Candidate(val suite: String, val family: String, val problem: String)

    fun main(args: List<String>): Int {
        if (args.size !in 3..4) {
            System.err.println("usage: klause-bench/scripts/draw-sets.sh <features-dir> <references.csv> <sets-dir> [set,…]")
            return 2
        }
        draw(Path.of(args[0]), Path.of(args[1]), Path.of(args[2]), args.getOrNull(3)?.split(",")?.toSet() ?: SETS.toSet())
        return 0
    }

    fun draw(featuresDir: Path, referencesCsv: Path, setsDir: Path, only: Set<String>, today: LocalDate = LocalDate.now()) {
        // Each problem's tier by how fast the reference proved it, over every reference solver.
        val fastest = HashMap<Pair<String, String>, Long>()
        for (row in csvRows(referencesCsv.readText())) {
            if (row["proven"] == "1" && row["feasible"] != "") {
                val key = row.getValue("collection") to row.getValue("problem")
                fastest[key] = minOf(fastest[key] ?: (1L shl 62), row.getValue("elapsed_ms").trim().toLong())
            }
        }

        // Candidates per (theme, tier).
        val pools = HashMap<Pair<String, String>, MutableList<Candidate>>()
        val features = Files.list(featuresDir).use { list -> list.filter { it.name.endsWith(".jsonl") }.toList() }
            .sortedWith { a, b -> compareCodePoints(a.name, b.name) }
        for (path in features) {
            for (line in path.readLines()) {
                if (!line.startsWith("{")) continue
                val p = Json.parseToJsonElement(line) as JsonObject
                fun text(key: String) = (p[key] as? JsonPrimitive)?.content ?: error("$path: a problem without $key")
                val tier = fastest[text("collection") to text("problem")]?.let(::tierOf) ?: continue
                for (theme in (p["themes"] as? JsonArray).orEmpty()) {
                    val name = (theme as JsonPrimitive).content
                    if (text("format") !in EXCLUDED_FORMATS[name].orEmpty())
                        pools.getOrPut(name to tier) { mutableListOf() } += Candidate(text("suite"), text("family"), text("problem"))
                }
            }
        }

        val rng = PythonRandom(SEED)
        val taken = HashSet<Pair<String, String>>()
        setsDir.createDirectories()
        for (name in SETS) {
            if (name !in only) continue
            val size = SIZES.getValue(name)
            val easy = Math.rint(size * EASY_SHARE).toInt()
            val quotas = linkedMapOf("easy" to easy, "hard" to HARD, "medium" to size - easy - HARD)
            val picked = LinkedHashMap<String, List<Candidate>>()
            // A tier short of candidates hands its quota on, easiest-first fallback last.
            for (tier in TIERS.sortedBy { -it.low }.map { it.name }) {
                val open = pools[name to tier].orEmpty().filter { (it.suite to it.problem) !in taken }
                val chosen = spread(open, quotas.getValue(tier), rng)
                picked[tier] = chosen
                chosen.forEach { taken += it.suite to it.problem }
                val short = quotas.getValue(tier) - chosen.size
                if (short > 0) {
                    TIERS.map { it.name }.lastOrNull { quotas.getValue(it) != 0 && it != tier && it !in picked }
                        ?.let { quotas[it] = quotas.getValue(it) + short }
                }
            }
            val lines = mutableListOf(
                "# $name: problems with the $name theme, drawn $today by klause-bench/scripts/draw-sets.sh",
                "# tiers by the reference's time to prove: easy < 1 s, medium < 10 s, hard < 60 s",
            )
            for (tier in TIERS) {
                val chosen = picked[tier.name].orEmpty().sortedWith(CANDIDATE_ORDER)
                lines += "# ${tier.name}: ${chosen.size}"
                lines += chosen.map { "${it.suite}/${it.problem}" }
            }
            setsDir.resolve("$name.txt").writeText(lines.joinToString("\n") + "\n")
            System.err.println("$name " + TIERS.joinToString(", ", "{", "}") { "'${it.name}': ${picked[it.name].orEmpty().size}" })
        }
        for ((name, parts) in UNIONS) {
            setsDir.resolve("$name.txt").writeText("# $name: " + parts.joinToString(", ") + "\n" + parts.joinToString("") { "@$it\n" })
        }
    }

    private fun tierOf(elapsedMs: Long): String? = TIERS.firstOrNull { elapsedMs >= it.low && elapsedMs < it.high }?.name

    /**
     * [n] of [candidates], one per (suite, family) per round, suites and families taken in a seeded order, no suite
     * beyond its [SUITE_SHARE] of [n] (at least one).
     */
    fun spread(candidates: List<Candidate>, n: Int, rng: PythonRandom): List<Candidate> {
        val caps = SUITE_SHARE.mapValues { (_, share) -> maxOf(1, (n * share).toInt()) }
        val taken = HashMap<String, Int>()
        val families = LinkedHashMap<Pair<String, String>, MutableList<Candidate>>()
        for (c in candidates) families.getOrPut(c.suite to c.family) { mutableListOf() } += c
        families.values.forEach(rng::shuffle)
        val bySuite = LinkedHashMap<String, MutableList<Pair<String, String>>>()
        for (key in families.keys) bySuite.getOrPut(key.first) { mutableListOf() } += key
        bySuite.values.forEach(rng::shuffle)
        // Interleave suites, so one large suite does not fill a set alone.
        val order = mutableListOf<Pair<String, String>>()
        val suites = bySuite.keys.sortedWith(::compareCodePoints).toMutableList()
        rng.shuffle(suites)
        while (suites.any { bySuite.getValue(it).isNotEmpty() }) {
            for (s in suites) bySuite.getValue(s).let { if (it.isNotEmpty()) order += it.removeLast() }
        }
        fun open(k: Pair<String, String>) = families.getValue(k).isNotEmpty() && (taken[k.first] ?: 0) < (caps[k.first] ?: n)
        val chosen = mutableListOf<Candidate>()
        while (chosen.size < n && order.any(::open)) {
            for (k in order) {
                if (open(k) && chosen.size < n) {
                    chosen += families.getValue(k).removeLast()
                    taken[k.first] = (taken[k.first] ?: 0) + 1
                }
            }
        }
        return chosen
    }

    /** The rows of [text], a CSV with a header line, as Python's `csv.DictReader` reads them: blank lines skipped. */
    fun csvRows(text: String): List<Map<String, String>> {
        val records = mutableListOf<List<String>>()
        var fields = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var started = false
        var i = 0
        fun endRecord() {
            if (started || fields.isNotEmpty()) {
                fields += field.toString()
                records += fields
            }
            fields = mutableListOf()
            field.clear()
            started = false
        }
        while (i < text.length) {
            val c = text[i]
            when {
                quoted && c == '"' && text.getOrNull(i + 1) == '"' -> { field.append('"'); i++ }
                quoted && c == '"' -> quoted = false
                quoted -> field.append(c)
                c == '"' -> { quoted = true; started = true }
                c == ',' -> { fields += field.toString(); field.clear(); started = true }
                c == '\r' && text.getOrNull(i + 1) == '\n' -> {}
                c == '\n' || c == '\r' -> endRecord()
                else -> { field.append(c); started = true }
            }
            i++
        }
        endRecord()
        val header = records.firstOrNull() ?: return emptyList()
        return records.drop(1).map { row -> header.zip(row).toMap() }
    }

    private val CANDIDATE_ORDER = Comparator<Candidate> { a, b ->
        compareCodePoints(a.suite, b.suite).takeIf { it != 0 } ?: compareCodePoints(a.family, b.family).takeIf { it != 0 }
            ?: compareCodePoints(a.problem, b.problem)
    }

    private fun compareCodePoints(a: String, b: String): Int {
        val left = a.codePoints().toArray()
        val right = b.codePoints().toArray()
        for (i in 0 until minOf(left.size, right.size)) left[i].compareTo(right[i]).let { if (it != 0) return it }
        return left.size.compareTo(right.size)
    }
}

/**
 * Python's `random.Random(seed)` for a non-negative integer seed, as far as [MakeSets] uses it: MT19937 seeded by
 * `init_by_array`, `getrandbits` and `shuffle`, so the sets it draws are the ones Python drew.
 */
internal class PythonRandom(seed: Long) {
    private val mt = IntArray(N)
    private var index = N

    init {
        require(seed >= 0)
        val key = if (seed == 0L) intArrayOf(0) else
            generateSequence(seed) { (it ushr 32).takeIf { rest -> rest != 0L } }.map { it.toInt() }.toList().toIntArray()
        mt[0] = 19650218
        for (i in 1 until N) mt[i] = 1812433253 * (mt[i - 1] xor (mt[i - 1] ushr 30)) + i
        var i = 1
        var j = 0
        repeat(maxOf(N, key.size)) {
            mt[i] = (mt[i] xor ((mt[i - 1] xor (mt[i - 1] ushr 30)) * 1664525)) + key[j] + j
            i++
            j++
            if (i >= N) { mt[0] = mt[N - 1]; i = 1 }
            if (j >= key.size) j = 0
        }
        repeat(N - 1) {
            mt[i] = (mt[i] xor ((mt[i - 1] xor (mt[i - 1] ushr 30)) * 1566083941)) - i
            i++
            if (i >= N) { mt[0] = mt[N - 1]; i = 1 }
        }
        mt[0] = Int.MIN_VALUE
    }

    private fun next(): Int {
        if (index >= N) {
            for (k in 0 until N) {
                val y = (mt[k] and Int.MIN_VALUE) or (mt[(k + 1) % N] and Int.MAX_VALUE)
                mt[k] = mt[(k + M) % N] xor (y ushr 1) xor (if (y and 1 != 0) MATRIX_A else 0)
            }
            index = 0
        }
        var y = mt[index++]
        y = y xor (y ushr 11)
        y = y xor ((y shl 7) and 0x9d2c5680.toInt())
        y = y xor ((y shl 15) and 0xefc60000.toInt())
        return y xor (y ushr 18)
    }

    /** `getrandbits(k)` for k up to 32. */
    fun bits(k: Int): Long {
        require(k in 0..32)
        return if (k == 0) 0 else (next().toLong() and 0xffffffffL) ushr (32 - k)
    }

    private fun below(n: Int): Int {
        val k = 32 - Integer.numberOfLeadingZeros(n)
        var r = bits(k)
        while (r >= n) r = bits(k)
        return r.toInt()
    }

    fun <T> shuffle(items: MutableList<T>) {
        for (i in items.indices.reversed()) {
            if (i == 0) break
            val j = below(i + 1)
            items[i] = items[j].also { items[j] = items[i] }
        }
    }

    private companion object {
        const val N = 624
        const val M = 397
        const val MATRIX_A = 0x9908b0df.toInt()
    }
}

fun main(args: Array<String>) { kotlin.system.exitProcess(MakeSets.main(args.toList())) }
