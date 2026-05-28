package com.eignex.klause.bench.parity

import java.io.File

/**
 * Discovery for MiniZinc parity instances. Each instance is a `(model, optional data)` pair
 * with a stable name; corpora pull from on-disk roots and produce the same shape so the
 * parity runner doesn't care where instances came from.
 *
 * Roots and their layouts:
 *
 *  - **smoke** — `klause-mzn-lib/test-models/`. Tiny hand-curated models that we
 *    consider mandatory parity (run in CI). One file per instance, no data files.
 *  - **mzn-bench** — `klause-bench/build/mzn/minizinc-benchmarks/<problem>/`. The MiniZinc
 *    Challenge benchmarks repo (cloned by `:klause-bench:downloadMzn`). Layout is one
 *    directory per problem; each contains one or more `.mzn` models plus a sibling
 *    `data/` directory of `.dzn` instances, or `.mzn` files that already inline their
 *    data. We pair each `.dzn` with the first `.mzn` in the problem directory.
 *  - **libminizinc-tests** — `klause-bench/build/mzn/libminizinc/tests/spec/unit/`. The
 *    MiniZinc compiler's correctness test suite (cloned by `:klause-bench:downloadMznTestSuite`).
 *    Layout is `.mzn` files with `// solver: …` / `// expected:` directives in comments.
 *  - **hakank** — `klause-bench/build/mzn/hakank/`. Hakank's MiniZinc model collection
 *    (cloned by `:klause-bench:downloadMznHakank`). Models with optional `.dzn`s next to
 *    them; very large and stylistically varied, so it's gated behind an opt-in
 *    [Source.HAKANK] flag.
 */
object MznParityCorpus {

    data class Instance(
        val name: String,
        val mzn: File,
        val dzn: File? = null,
    )

    enum class Source { SMOKE, MZN_BENCH, LIBMINIZINC_TESTS, HAKANK }

    /** Resolve the workspace root by walking up from this class's location until we find
     *  `klause-mzn-lib/`. Tests / Gradle tasks invoking the harness should set
     *  `klause.workspace.root` to skip the walk. */
    fun workspaceRoot(): File {
        System.getProperty("klause.workspace.root")?.let { return File(it) }
        // ../../../ from klause-bench/build/classes/.../parity at runtime.
        var cur: File? = File(".").absoluteFile
        while (cur != null) {
            if (File(cur, "klause-mzn-lib").isDirectory) return cur
            cur = cur.parentFile
        }
        error("could not locate workspace root (no klause-mzn-lib parent)")
    }

    fun discover(source: Source, root: File = workspaceRoot()): List<Instance> = when (source) {
        Source.SMOKE -> discoverSmoke(root)
        Source.MZN_BENCH -> discoverMznBench(root)
        Source.LIBMINIZINC_TESTS -> discoverLibMinizincTests(root)
        Source.HAKANK -> discoverHakank(root)
    }

    private fun discoverSmoke(root: File): List<Instance> {
        val dir = File(root, "klause-mzn-lib/test-models")
        if (!dir.isDirectory) return emptyList()
        return dir.listFiles { f -> f.isFile && f.extension == "mzn" }
            ?.sortedBy { it.name }
            ?.map { Instance(it.nameWithoutExtension, it) }
            ?: emptyList()
    }

    private fun discoverMznBench(root: File): List<Instance> {
        val benchRoot = File(root, "klause-bench/build/mzn/minizinc-benchmarks")
        if (!benchRoot.isDirectory) return emptyList()
        val problemDirs = benchRoot.listFiles { f -> f.isDirectory && !f.name.startsWith(".") }
            ?.sortedBy { it.name } ?: return emptyList()
        // Per-family instance lists. Built first, then interleaved across families so the
        // global ordering is "1 from family A, 1 from family B, ..., 2nd from A, ...".
        // The interleave makes `.take(N)` naturally yield a spread across families instead
        // of consuming all of the first family's instances before reaching the second.
        val perFamily = mutableListOf<List<Instance>>()
        for (pd in problemDirs) {
            // Find the model. Many families ship multiple `.mzn` files (alternate
            // formulations, deprecated forerunners, year-prefixed competition variants).
            // Prefer one whose basename matches the family name closely — `<family>.mzn`
            // or `<family>_model.mzn` — before falling back to alphabetical. This avoids
            // landing on stale variants like `mznc2009_roster_model.mzn` (uses obsolete
            // `:: is_output`) or `zephyrus-FH-2-15.mzn` (different parameter set than the
            // shipped .dzns).
            val candidates = pd.listFiles { f -> f.isFile && f.extension == "mzn" }
                ?.toList()
                ?: pd.walkTopDown().maxDepth(3).filter { it.isFile && it.extension == "mzn" }.toList()
            if (candidates.isEmpty()) continue
            val primaryMzn = pickPrimaryMzn(pd.name, candidates)
            // Find every .dzn under the problem dir (recursive). Real-world layouts vary:
            //   - `<problem>/instance.dzn` (alpha, queens, …)
            //   - `<problem>/data/*.dzn` (some benchmarks)
            //   - `<problem>/class_1/*.dzn` (2DBinPacking, bin-packing classes)
            //   - `<problem>/<year>/*.dzn` (amaze, …)
            val dzns = pd.walkTopDown().maxDepth(3)
                .filter { it.isFile && it.extension == "dzn" }
                .sortedBy { it.relativeTo(pd).path }
                .toList()
            val familyInstances = if (dzns.isEmpty()) {
                // No data file: the .mzn must be self-contained.
                listOf(Instance(pd.name, primaryMzn))
            } else {
                dzns.map { dzn ->
                    val relPath = dzn.relativeTo(pd).path.removeSuffix(".dzn")
                    Instance("${pd.name}/$relPath", primaryMzn, dzn)
                }
            }
            if (familyInstances.isNotEmpty()) perFamily += familyInstances
        }
        return interleave(perFamily)
    }

    /** Pick the canonical .mzn for [familyName] from [candidates]. Tries an exact
     *  basename match first, then a `<family>_model.mzn` / `<family>_alt.mzn` / `model.mzn`
     *  / `main.mzn` fallback, finally a name containing the family as a prefix without a
     *  competition-year prefix. Falls back to sorted-first when none of these apply. */
    internal fun pickPrimaryMzn(familyName: String, candidates: List<File>): File {
        val lower = familyName.lowercase()
        val priorities = listOf<(File) -> Boolean>(
            { it.nameWithoutExtension.equals(familyName, ignoreCase = true) },
            { it.nameWithoutExtension.equals("${familyName}_model", ignoreCase = true) },
            { it.nameWithoutExtension.equals("model", ignoreCase = true) },
            { it.nameWithoutExtension.equals("main", ignoreCase = true) },
            { it.nameWithoutExtension.lowercase().startsWith(lower) &&
                !it.nameWithoutExtension.lowercase().startsWith("mznc") },
        )
        for (pred in priorities) candidates.firstOrNull(pred)?.let { return it }
        return candidates.sortedBy { it.name }.first()
    }

    /** Round-robin merge of [lists]. Iteration `k` takes element `k` from each list that
     *  still has one; stops when all lists are exhausted. Stable: relative order within a
     *  list is preserved, and ties (same depth) follow the order of `lists`. */
    private fun <T> interleave(lists: List<List<T>>): List<T> {
        if (lists.isEmpty()) return emptyList()
        val maxLen = lists.maxOf { it.size }
        val out = ArrayList<T>(lists.sumOf { it.size })
        for (k in 0 until maxLen) {
            for (l in lists) if (k < l.size) out += l[k]
        }
        return out
    }

    private fun discoverLibMinizincTests(root: File): List<Instance> {
        // libminizinc tests are organised under tests/spec/unit/<category>/ with .mzn files. The
        // suite is owned by MiniZinc's CI; many files exercise compiler edge cases
        // unrelated to solver correctness — keep this opt-in and assume the caller
        // filters further in their harness.
        val unitDir = File(root, "klause-bench/build/mzn/libminizinc/tests/spec/unit")
        if (!unitDir.isDirectory) return emptyList()
        return unitDir.walkTopDown()
            .filter { it.isFile && it.extension == "mzn" }
            .sortedBy { it.relativeTo(unitDir).path }
            .map { f ->
                val rel = f.relativeTo(unitDir).path.removeSuffix(".mzn")
                Instance(rel, f)
            }
            .toList()
    }

    private fun discoverHakank(root: File): List<Instance> {
        val hk = File(root, "klause-bench/build/mzn/hakank")
        if (!hk.isDirectory) return emptyList()
        return hk.walkTopDown()
            .filter { it.isFile && it.extension == "mzn" }
            .sortedBy { it.relativeTo(hk).path }
            .map { f ->
                val rel = f.relativeTo(hk).path.removeSuffix(".mzn")
                Instance(rel, f)
            }
            .toList()
    }

    /** Resolve the `klause.msc` config that the parity runner needs. Resolved relative to
     *  [workspaceRoot]. */
    fun klauseMsc(root: File = workspaceRoot()): File =
        File(root, "klause-mzn-lib/share/minizinc/solvers/klause.msc")

    /** Resolve the directory MiniZinc must search first to find klause's redefinitions. */
    fun klauseMznLibDir(root: File = workspaceRoot()): File =
        File(root, "klause-mzn-lib/share/minizinc/klause")
}
