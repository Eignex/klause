package com.eignex.klause.bench.source

import com.eignex.klause.bench.catalog.Category
import com.eignex.klause.bench.catalog.Expected
import com.eignex.klause.bench.catalog.ExternalCollection
import com.eignex.klause.bench.catalog.Format
import com.eignex.klause.bench.catalog.ProblemRef
import com.eignex.klause.bench.catalog.ProblemSource
import java.io.File
import kotlin.random.Random

/**
 * First-class, reusable corpus-selection machinery. It discovers `(model, optional data)` instances
 * from a corpus root under a known [Layout], then applies family-aware selection so a capped
 * run yields a spread across problem families rather than all of the first family:
 *
 *  - **interleave** — round-robin merge across families, so `take(N)` spans families;
 *  - **per-family cap** — at most K instances per family (head, or deterministic seeded sample);
 *  - **overall cap** — total instance limit applied after interleave;
 *  - **[pickPrimaryMzn]** — choose the canonical `.mzn` when a family ships several;
 *  - **`.dzn` pairing** — pair each data file with its model across nested layouts.
 *
 * Selection is independent of *how* the corpus arrived (vendored or fetched), so any metric
 * or target can reuse it; for external collections the cache is fetched on demand first.
 */
internal object CorpusSelection {

    /** A discovered instance: paths are relative to the corpus root. */
    data class Discovered(
        val name: String,
        val mznRelPath: String,
        val dznRelPath: String? = null,
        /** Explicit family key; when null, falls back to the leading path component of [name]. Set
         *  for flat corpora (e.g. XCSP3) whose family lives in the name rather than a directory. */
        val familyKey: String? = null,
    ) {
        /** Family key: [familyKey] when set, else the leading path component of [name]. */
        val family: String get() = familyKey ?: name.substringBefore('/')
    }

    /** Knobs controlling how many instances a selection yields. */
    data class Selection(
        val perFamily: Int? = null,
        val maxInstances: Int? = null,
        /** When non-null, sample [perFamily] per family with this seed instead of taking the head. */
        val sampleSeed: Long? = null,
    ) {
        companion object {
            /** Read selection knobs from `-Dklause.bench.select.*` (perFamily / max / seed). */
            fun fromProps(defaultPerFamily: Int? = null): Selection = Selection(
                perFamily = System.getProperty("klause.bench.select.perFamily")?.toIntOrNull() ?: defaultPerFamily,
                maxInstances = System.getProperty("klause.bench.select.max")?.toIntOrNull(),
                sampleSeed = System.getProperty("klause.bench.select.seed")?.toLongOrNull(),
            )
        }
    }

    /** How instances are laid out under a corpus root. */
    sealed interface Layout {
        fun discover(root: File): List<Discovered>

        /** MiniZinc-Challenge layout: one dir per problem family under [subDir], each with one
         *  or more `.mzn` (pick the primary) plus `.dzn` data files in nested dirs. */
        data class MznChallenge(val subDir: String = "") : Layout {
            override fun discover(root: File): List<Discovered> {
                val base = if (subDir.isEmpty()) root else File(root, subDir)
                if (!base.isDirectory) return emptyList()
                val families = base.listFiles { f -> f.isDirectory && !f.name.startsWith(".") }
                    ?.sortedBy { it.name } ?: return emptyList()
                val perFamily = ArrayList<List<Discovered>>()
                for (pd in families) {
                    val candidates = pd.listFiles { f -> f.isFile && f.extension == "mzn" }?.toList()
                        ?: pd.walkTopDown().maxDepth(3).filter { it.isFile && it.extension == "mzn" }.toList()
                    if (candidates.isEmpty()) continue
                    val primary = pickPrimaryMzn(pd.name, candidates)
                    val mznRel = primary.relativeTo(root).path
                    val dzns = pd.walkTopDown().maxDepth(3)
                        .filter { it.isFile && it.extension == "dzn" }
                        .sortedBy { it.relativeTo(pd).path }.toList()
                    val instances = if (dzns.isEmpty()) {
                        listOf(Discovered(pd.name, mznRel))
                    } else {
                        dzns.map { dzn ->
                            Discovered(
                                "${pd.name}/${dzn.relativeTo(pd).path.removeSuffix(".dzn")}",
                                mznRel,
                                dzn.relativeTo(root).path,
                            )
                        }
                    }
                    if (instances.isNotEmpty()) perFamily += instances
                }
                return interleave(perFamily)
            }
        }

        /** Flat layout: walk [subDir] for `.mzn` files; family = first path component under
         *  [subDir]. A parameterized model is paired with a same-named `.dzn` sibling when one
         *  exists (a self-contained model has none). Used for libminizinc tests and hakank. */
        data class FlatMzn(val subDir: String) : Layout {
            override fun discover(root: File): List<Discovered> {
                val base = File(root, subDir)
                if (!base.isDirectory) return emptyList()
                return base.walkTopDown()
                    .filter { it.isFile && it.extension == "mzn" }
                    .filterNot { it.relativeTo(base).path.startsWith("lib/") }
                    .sortedBy { it.relativeTo(base).path }
                    .map {
                        val data = File(it.parentFile, "${it.nameWithoutExtension}.dzn")
                        Discovered(
                            it.relativeTo(base).path.removeSuffix(".mzn"),
                            it.relativeTo(root).path,
                            if (data.isFile) data.relativeTo(root).path else null,
                        )
                    }
                    .toList()
            }
        }

        /** Flat layout for single-file instances of one [ext] (no data-file pairing): walk
         *  [subDir] for `*.ext`. Family defaults to the first path component (SMT-LIB `.smt2`,
         *  DIMACS `.cnf`), but a flat corpus whose family lives in the filename passes [familyOf] —
         *  e.g. XCSP3, where `AircraftAssemblyLine-1-178-00-0_c23` groups under `AircraftAssemblyLine`
         *  via the series prefix, so `per-family` samples across series instead of near-identical
         *  parameterizations. */
        data class Flat(
            val subDir: String,
            val ext: String,
            val familyOf: (String) -> String = { it.substringBefore('/') },
        ) : Layout {
            override fun discover(root: File): List<Discovered> {
                val base = if (subDir.isEmpty()) root else File(root, subDir)
                if (!base.isDirectory) return emptyList()
                return base.walkTopDown()
                    .filter { it.isFile && it.extension == ext }
                    .sortedBy { it.relativeTo(base).path }
                    .map {
                        val name = it.relativeTo(base).path.removeSuffix(".$ext")
                        Discovered(name, it.relativeTo(root).path, familyKey = familyOf(name))
                    }
                    .toList()
            }
        }
    }

    /** Discover + select instances from a fetched [collection], returning catalog [ProblemRef]s.
     *  [format] is the instances' format (defaults to MiniZinc; SMT-LIB/DIMACS corpora set it). */
    fun select(
        collection: ExternalCollection,
        layout: Layout,
        selection: Selection,
        category: Category,
        format: Format = Format.MINIZINC,
        /** Resolve an instance's oracle from its resolved instance [File] (e.g. parse directives). */
        expected: (File) -> Expected = { Expected.Unknown },
    ): List<ProblemRef> {
        val root = CorpusFetcher.ensure(collection)
        val discovered = layout.discover(root)
        // A layout prefix that does not match what the archive actually unpacks finds nothing, and an
        // empty suite is indistinguishable downstream from a suite that ran and changed nothing - the
        // failure mode that makes a measurement read as a clean negative result. Fail where the cause is
        // still visible.
        require(discovered.isNotEmpty()) {
            "corpus '${collection.id}' yielded no instances under '$layout' in $root: " +
                "the layout prefix does not match the fetched tree"
        }
        val chosen = applySelection(discovered, selection)
        return chosen.map { d ->
            ProblemRef(
                name = d.name,
                format = format,
                source = ProblemSource.External(collection, d.mznRelPath),
                category = category,
                expected = expected(File(root, d.mznRelPath)),
                data = d.dznRelPath?.let { ProblemSource.External(collection, it) },
                license = collection.license,
            )
        }
    }

    /** Apply per-family cap (head or seeded sample), interleave, then overall cap. */
    fun applySelection(all: List<Discovered>, sel: Selection): List<Discovered> =
        applySelectionBy(all, sel) { it.family }

    /** Generic family-aware selection over any item type, keyed by [family]. Reused for
     *  catalog [com.eignex.klause.bench.catalog.ProblemRef] selection in the ad-hoc CLI. */
    fun <T> applySelectionBy(all: List<T>, sel: Selection, family: (T) -> String): List<T> {
        val byFamily = LinkedHashMap<String, MutableList<T>>()
        for (d in all) byFamily.getOrPut(family(d)) { mutableListOf() }.add(d)
        val capped = byFamily.values.map { fam ->
            val k = sel.perFamily ?: return@map fam.toList()
            if (sel.sampleSeed != null) fam.shuffled(Random(sel.sampleSeed)).take(k) else fam.take(k)
        }
        val interleaved = interleave(capped)
        return sel.maxInstances?.let { interleaved.take(it) } ?: interleaved
    }

    /** Format-balanced (or any-[group]-balanced) selection: partition [all] by [group], apply the
     *  family-aware cap/sample/interleave within each group, then spread [Selection.maxInstances]
     *  *evenly across the groups present* rather than across families globally — so a corpus with
     *  many families in one format can't crowd the other formats out of a capped run. The split
     *  water-fills: a group with fewer instances than its even share yields the surplus to groups
     *  that can still absorb it, keeping the total at the cap when the corpus can supply it. Groups
     *  are round-robin [interleave]d so a further downstream `take` still spans every group. With no
     *  [Selection.maxInstances] every group is kept in full. */
    fun <T> applyBalancedBy(all: List<T>, sel: Selection, family: (T) -> String, group: (T) -> Any): List<T> {
        val groups = LinkedHashMap<Any, MutableList<T>>()
        for (d in all) groups.getOrPut(group(d)) { mutableListOf() }.add(d)
        val perGroup = groups.values.map { applySelectionBy(it, sel.copy(maxInstances = null), family) }
        val total = sel.maxInstances ?: return interleave(perGroup)
        val quota = waterFill(perGroup.map { it.size }, total)
        return interleave(perGroup.mapIndexed { i, g -> g.take(quota[i]) })
    }

    /** Spread [total] across bins of the given [sizes] as evenly as possible, never exceeding a
     *  bin's size: hand every not-yet-full bin an equal share of what remains, repeating until the
     *  budget is spent or every bin is full, so surplus from small bins flows to bins with room. */
    private fun waterFill(sizes: List<Int>, total: Int): IntArray {
        val quota = IntArray(sizes.size)
        var remaining = total.coerceAtMost(sizes.sum())
        while (remaining > 0) {
            val hungry = sizes.indices.filter { quota[it] < sizes[it] }
            if (hungry.isEmpty()) break
            val share = (remaining / hungry.size).coerceAtLeast(1)
            for (i in hungry) {
                if (remaining == 0) break
                val give = minOf(share, sizes[i] - quota[i], remaining)
                quota[i] += give
                remaining -= give
            }
        }
        return quota
    }

    /** Pick the canonical `.mzn` for [familyName] from [candidates]: exact basename, then
     *  `<family>_model` / `model` / `main`, then a family-prefixed non-`mznc` name, else
     *  sorted-first. */
    fun pickPrimaryMzn(familyName: String, candidates: List<File>): File {
        val lower = familyName.lowercase()
        val priorities = listOf<(File) -> Boolean>(
            { it.nameWithoutExtension.equals(familyName, ignoreCase = true) },
            { it.nameWithoutExtension.equals("${familyName}_model", ignoreCase = true) },
            { it.nameWithoutExtension.equals("model", ignoreCase = true) },
            { it.nameWithoutExtension.equals("main", ignoreCase = true) },
            {
                it.nameWithoutExtension.lowercase().startsWith(
                    lower,
                ) && !it.nameWithoutExtension.lowercase().startsWith("mznc")
            },
        )
        for (pred in priorities) candidates.firstOrNull(pred)?.let { return it }
        return candidates.sortedBy { it.name }.first()
    }

    /** Round-robin merge: iteration k takes element k from each list that still has one.
     *  Stable; preserves within-list order. */
    fun <T> interleave(lists: List<List<T>>): List<T> {
        if (lists.isEmpty()) return emptyList()
        val maxLen = lists.maxOf { it.size }
        val out = ArrayList<T>(lists.sumOf { it.size })
        for (k in 0 until maxLen) for (l in lists) if (k < l.size) out += l[k]
        return out
    }
}
