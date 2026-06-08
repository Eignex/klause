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
    data class Discovered(val name: String, val mznRelPath: String, val dznRelPath: String? = null) {
        /** Family key: the leading path component of [name]. */
        val family: String get() = name.substringBefore('/')
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

        /** Flat layout: walk [subDir] for self-contained `.mzn` files; family = first path
         *  component under [subDir]. Used for libminizinc tests and hakank. */
        data class FlatMzn(val subDir: String) : Layout {
            override fun discover(root: File): List<Discovered> {
                val base = File(root, subDir)
                if (!base.isDirectory) return emptyList()
                return base.walkTopDown()
                    .filter { it.isFile && it.extension == "mzn" }
                    .sortedBy { it.relativeTo(base).path }
                    .map { Discovered(it.relativeTo(base).path.removeSuffix(".mzn"), it.relativeTo(root).path) }
                    .toList()
            }
        }
    }

    /** Discover + select instances from a fetched [collection], returning catalog [ProblemRef]s. */
    fun select(
        collection: ExternalCollection,
        layout: Layout,
        selection: Selection,
        category: Category,
        /** Resolve an instance's oracle from its resolved `.mzn` [File] (e.g. parse directives). */
        expected: (File) -> Expected = { Expected.Unknown },
    ): List<ProblemRef> {
        val root = CorpusFetcher.ensure(collection)
        val chosen = applySelection(layout.discover(root), selection)
        return chosen.map { d ->
            ProblemRef(
                name = d.name,
                format = Format.MINIZINC,
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
