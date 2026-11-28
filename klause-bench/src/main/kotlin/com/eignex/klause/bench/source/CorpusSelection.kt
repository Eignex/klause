package com.eignex.klause.bench.source

import com.eignex.klause.bench.catalog.Category
import com.eignex.klause.bench.catalog.Expected
import com.eignex.klause.bench.catalog.ExternalCollection
import com.eignex.klause.bench.catalog.Format
import com.eignex.klause.bench.catalog.ProblemRef
import com.eignex.klause.bench.catalog.ProblemSource
import java.io.File
import kotlin.random.Random

// Family-aware selection: a capped run spreads across problem families instead of taking all of the first one.
internal object CorpusSelection {

    // Paths are relative to the corpus root.
    data class Discovered(
        val name: String,
        val mznRelPath: String,
        val dznRelPath: String? = null,
        // Set for flat corpora (e.g. XCSP3) whose family lives in the name rather than a directory.
        val familyKey: String? = null,
    ) {
        val family: String get() = familyKey ?: name.substringBefore('/')
    }

    data class Selection(
        val perFamily: Int? = null,
        val maxInstances: Int? = null,
        // When non-null, sample perFamily per family with this seed instead of taking the head.
        val sampleSeed: Long? = null,
    ) {
        companion object {
            fun fromProps(defaultPerFamily: Int? = null): Selection = Selection(
                perFamily = System.getProperty("klause.bench.select.perFamily")?.toIntOrNull() ?: defaultPerFamily,
                maxInstances = System.getProperty("klause.bench.select.max")?.toIntOrNull(),
                sampleSeed = System.getProperty("klause.bench.select.seed")?.toLongOrNull(),
            )
        }
    }

    sealed interface Layout {
        fun discover(root: File): List<Discovered>

        // One dir per family under subDir, each with one or more `.mzn` plus `.dzn` data in nested dirs.
        // Data-free instances keep the primary model's basename in their key.
        data class MznChallenge(val subDir: String = "") : Layout {
            override fun discover(root: File): List<Discovered> {
                val base = if (subDir.isEmpty()) root else File(root, subDir)
                if (!base.isDirectory) return emptyList()
                return discoverFamilyDirs(root, base)
            }
        }

        // Every year directory is a MznChallenge layout. Names and family keys are year-prefixed (`2013/cargo`)
        // because problem names recur across years and a bare name would merge different problems.
        data class MznChallengeAllYears(val legacyExclusions: Boolean = true) : Layout {
            override fun discover(root: File): List<Discovered> {
                val years = root.listFiles { f -> f.isDirectory && f.name.toIntOrNull() != null }
                    ?.sortedBy { it.name } ?: return emptyList()
                return interleave(
                    years.map { discoverFamilyDirs(root, it, prefix = it.name, legacyExclusions = legacyExclusions) },
                )
            }
        }

        // Each `.mzn` pairs with a same-named `.dzn` sibling when one exists.
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

        // Single-file instances without data pairing. A corpus whose family lives in the filename passes familyOf
        // (XCSP3 series prefixes), so per-family sampling spans series instead of near-identical parameterizations.
        // A `*.ext.zst` file is the same instance compressed; when both exist it is listed once, compressed.
        data class Flat(
            val subDir: String,
            val ext: String,
            val familyOf: (String) -> String = { it.substringBefore('/') },
        ) : Layout {
            fun discover(paths: List<String>): List<Discovered> {
                val prefix = if (subDir.isEmpty()) "" else "$subDir/"
                return paths.filter { it.startsWith(prefix) && CorpusFiles.plainPath(it).endsWith(".$ext") }
                    .sortedWith(CorpusFiles.storedFirst)
                    .map { path ->
                        val name = CorpusFiles.plainPath(path.removePrefix(prefix)).removeSuffix(".$ext")
                        Discovered(name, path, familyKey = familyOf(name))
                    }.distinctBy { it.name }
            }

            fun named(paths: List<String>, names: List<String>): List<Discovered> {
                val available = discover(paths).associateBy { it.name }
                return names.distinct().mapNotNull { name ->
                    require(name.isNotBlank() && name.length <= 2048)
                    require(name.split('/').none { it.isEmpty() || it == "." || it == ".." })
                    require(name.none { it == '\\' || it.code < 32 || it.code == 127 })
                    available[name]
                }
            }

            // A fixed set already knows its paths; do not enumerate unrelated corpus members.
            fun named(root: File, names: List<String>): List<Discovered> {
                val base = if (subDir.isEmpty()) root else File(root, subDir)
                return names.distinct().mapNotNull { name ->
                    require(name.isNotBlank() && name.length <= 2048)
                    require(name.split('/').none { it.isEmpty() || it == "." || it == ".." })
                    require(name.none { it == '\\' || it.code < 32 || it.code == 127 })
                    val plain = File(base, "$name.$ext")
                    val compressed = File(base, "$name.$ext${CorpusFiles.SUFFIX}")
                    val source = when {
                        compressed.isFile -> compressed
                        plain.isFile -> plain
                        else -> return@mapNotNull null
                    }
                    Discovered(name, source.relativeTo(root).path, familyKey = familyOf(name))
                }
            }

            override fun discover(root: File): List<Discovered> {
                val base = if (subDir.isEmpty()) root else File(root, subDir)
                if (!base.isDirectory) return emptyList()
                return base.walkTopDown()
                    .filter { it.isFile && CorpusFiles.formatExtension(it) == ext }
                    .sortedWith(compareBy(CorpusFiles.storedFirst) { it.relativeTo(base).invariantSeparatorsPath })
                    .map {
                        val name = CorpusFiles.plainPath(it.relativeTo(base).path).removeSuffix(".$ext")
                        Discovered(name, it.relativeTo(root).path, familyKey = familyOf(name))
                    }
                    .distinctBy { it.name }
                    .toList()
            }
        }
    }

    fun select(
        collection: ExternalCollection,
        layout: Layout,
        selection: Selection,
        category: Category,
        format: Format = Format.MINIZINC,
        // Reading content needs the collection's bytes; without it discovery may run on the index listing alone.
        expected: ((File) -> Expected)? = null,
    ): List<ProblemRef> {
        val paths = if (expected == null && layout is Layout.Flat) {
            CorpusFetcher.declaredPaths(collection) ?: CorpusFetcher.selectivePaths(collection)
        } else {
            null
        }
        val root = if (paths == null) {
            if (expected == null) CorpusFetcher.listing(collection) else CorpusFetcher.ensure(collection)
        } else {
            File(CorpusFetcher.cacheRoot, collection.id)
        }
        val discovered = if (paths == null) layout.discover(root) else (layout as Layout.Flat).discover(paths)
        // Reject empty discovery so a mismatched archive layout cannot look like a completed measurement.
        require(discovered.isNotEmpty()) {
            "corpus '${collection.id}' yielded no instances under '$layout' in $root: " +
                "the layout prefix does not match the fetched tree"
        }
        val chosen = applySelection(discovered, selection)
        return references(collection, root, chosen, category, format, expected)
    }

    // Missing members stay absent so the caller's missing-set-member check reports them.
    fun named(
        collection: ExternalCollection,
        layout: Layout.Flat,
        names: List<String>,
        category: Category,
        format: Format,
    ): List<ProblemRef> {
        val paths = CorpusFetcher.declaredPaths(collection) ?: CorpusFetcher.selectivePaths(collection)
        val root = if (paths == null) {
            CorpusFetcher.listing(collection)
        } else {
            File(CorpusFetcher.cacheRoot, collection.id)
        }
        val discovered = if (paths == null) layout.named(root, names) else layout.named(paths, names)
        return references(collection, root, discovered, category, format, null)
    }

    private fun references(
        collection: ExternalCollection,
        root: File,
        discovered: List<Discovered>,
        category: Category,
        format: Format,
        expected: ((File) -> Expected)?,
    ): List<ProblemRef> =
        discovered.map { d ->
            ProblemRef(
                name = d.name,
                format = format,
                source = ProblemSource.External(collection, d.mznRelPath),
                category = category,
                expected = expected?.invoke(File(root, d.mznRelPath)) ?: Expected.Unknown,
                data = d.dznRelPath?.let { ProblemSource.External(collection, it) },
                license = collection.license,
                family = d.family,
            )
        }

    // Per-family cap (head or seeded sample), interleave, then overall cap.
    fun applySelection(all: List<Discovered>, sel: Selection): List<Discovered> =
        applySelectionBy(all, sel) { it.family }

    fun <T> applySelectionBy(all: List<T>, sel: Selection, family: (T) -> String): List<T> =
        applyAdmittedSelectionBy(all, sel, family) { true }

    fun <T> applyAdmittedSelectionBy(
        all: List<T>,
        sel: Selection,
        family: (T) -> String,
        admit: (T) -> Boolean,
    ): List<T> {
        val byFamily = LinkedHashMap<String, MutableList<T>>()
        for (item in all) byFamily.getOrPut(family(item)) { mutableListOf() }.add(item)
        val capped = byFamily.values.map { members ->
            val ordered = if (sel.perFamily != null && sel.sampleSeed != null) {
                members.shuffled(Random(sel.sampleSeed))
            } else {
                members
            }
            val eligible = ordered.asSequence().filter(admit)
            sel.perFamily?.let { eligible.take(it).toList() } ?: eligible.toList()
        }
        val interleaved = interleave(capped)
        return sel.maxInstances?.let { interleaved.take(it) } ?: interleaved
    }

    // Spreads maxInstances evenly across groups rather than families, so a format with many families cannot crowd
    // the others out of a capped run. Groups are interleaved so a downstream `take` still spans every group.
    fun <T> applyBalancedBy(all: List<T>, sel: Selection, family: (T) -> String, group: (T) -> Any): List<T> {
        val groups = LinkedHashMap<Any, MutableList<T>>()
        for (d in all) groups.getOrPut(group(d)) { mutableListOf() }.add(d)
        val perGroup = groups.values.map { applySelectionBy(it, sel.copy(maxInstances = null), family) }
        val total = sel.maxInstances ?: return interleave(perGroup)
        val quota = waterFill(perGroup.map { it.size }, total)
        return interleave(perGroup.mapIndexed { i, g -> g.take(quota[i]) })
    }

    // Surplus from bins smaller than their even share flows to bins with room.
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

    // One family per immediate subdirectory of base. prefix (a challenge year) keeps same-named problems from
    // different years apart.
    private fun discoverFamilyDirs(
        root: File,
        base: File,
        prefix: String? = null,
        legacyExclusions: Boolean = true,
    ): List<Discovered> {
        val families = base.listFiles { f -> f.isDirectory && !f.name.startsWith(".") }
            ?.sortedBy { it.name } ?: return emptyList()
        val out = ArrayList<Discovered>()
        for (pd in families) {
            // The model uses an is_output annotation undefined in MiniZinc.
            if (legacyExclusions && pd.relativeTo(root).path == "2010/depot_placement") continue
            val candidates = pd.listFiles { f -> f.isFile && f.extension == "mzn" }
                ?.toList()?.takeIf { it.isNotEmpty() }
                ?: pd.walkTopDown().maxDepth(3).filter { it.isFile && it.extension == "mzn" }.toList()
            if (candidates.isEmpty()) continue
            val primary = pickPrimaryMzn(pd.name, candidates)
            val mznRel = primary.relativeTo(root).path
            val familyName = if (prefix != null) "$prefix/${pd.name}" else pd.name
            // Challenge years from 2021 ship some instances' data as `.json`, which minizinc reads as it reads `.dzn`.
            val dzns = pd.walkTopDown().maxDepth(3)
                .filter { it.isFile && it.extension in DATA_EXTENSIONS }
                .sortedBy { it.relativeTo(pd).path }.toList()
            // The 2026 archive publishes parameterized models without their instance data.
            if (dzns.isEmpty() && mznRel.startsWith("2026/")) continue
            if (dzns.isEmpty()) {
                // A year-prefixed name already contains '/', so the default family fallback would collapse a year.
                val modelName = primary.relativeTo(pd).path.removeSuffix(".mzn")
                out += Discovered("$familyName/$modelName", mznRel, familyKey = familyName)
            } else {
                dzns.mapTo(out) { dzn ->
                    Discovered(
                        "$familyName/${dzn.relativeTo(pd).path.substringBeforeLast('.')}",
                        mznRel,
                        dzn.relativeTo(root).path,
                        familyKey = familyName,
                    )
                }
            }
        }
        return out
    }

    private val DATA_EXTENSIONS = setOf("dzn", "json")

    // Path order breaks ties independently of filesystem enumeration.
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
        val ordered = candidates.sortedBy { it.path }
        for (pred in priorities) ordered.firstOrNull(pred)?.let { return it }
        return ordered.first()
    }

    fun <T> interleave(lists: List<List<T>>): List<T> {
        if (lists.isEmpty()) return emptyList()
        val maxLen = lists.maxOf { it.size }
        val out = ArrayList<T>(lists.sumOf { it.size })
        for (k in 0 until maxLen) for (l in lists) if (k < l.size) out += l[k]
        return out
    }
}
