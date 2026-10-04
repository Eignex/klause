package com.eignex.klause.bench.source

/**
 * OR-Library (J.E. Beasley, people.brunel.ac.uk/~mastjjb/jeb/orlib) problem classes as MIPs: each class's own text
 * format read and written out as MPS, one model per instance. A converter returns (instance name, MPS) pairs; a file
 * that holds several instances names them `<file>-<k>`. The formats are those of the library's `<class>info.html`.
 */
internal object OrLibrary {
    /** Set covering (`scp*`): `m n`, the cost of each column, then for each row the number of columns covering it and
     *  those columns (1-based). Minimise cost so every row is covered at least once. */
    fun setCovering(file: String, text: String): List<Pair<String, String>> {
        val t = Tokens(text)
        val (m, n) = t.int() to t.int()
        val model = MipModel(file)
        val x = IntArray(n) { model.binary("x${it + 1}", t.double()) }
        for (i in 0 until m) {
            val covering = t.int()
            model.row("r${i + 1}", 'G', 1.0, List(covering) { x[t.int() - 1] to 1.0 })
        }
        return listOf(file to model.mps())
    }

    /** Set partitioning (`sppnw*`, airline crew scheduling): `m n`, then for each column its cost, the number of rows
     *  it covers and those rows (1-based). Minimise cost so every row is covered exactly once. */
    fun setPartitioning(file: String, text: String): List<Pair<String, String>> {
        val t = Tokens(text)
        val (m, n) = t.int() to t.int()
        val model = MipModel(file)
        val rows = Array(m) { ArrayList<Pair<Int, Double>>() }
        repeat(n) { j ->
            val column = model.binary("x${j + 1}", t.double())
            repeat(t.int()) { rows[t.int() - 1] += column to 1.0 }
        }
        rows.forEachIndexed { i, terms -> model.row("r${i + 1}", 'E', 1.0, terms) }
        return listOf(file to model.mps())
    }

    /** Capacitated warehouse location (`cap41`…`cap134`): `m n`, each warehouse's capacity and fixed cost, then each
     *  customer's demand and the cost of serving all of it from each warehouse. Open warehouses and split each customer
     *  among the open ones within their capacity; the `x ≤ y` rows give the strong formulation. */
    fun warehouseLocation(file: String, text: String): List<Pair<String, String>> {
        val t = Tokens(text)
        val (m, n) = t.int() to t.int()
        val model = MipModel(file)
        val capacity = DoubleArray(m)
        val open = IntArray(m) { i ->
            capacity[i] = t.double()
            model.binary("y${i + 1}", t.double())
        }
        val demand = DoubleArray(n)
        val serve = Array(n) { j ->
            demand[j] = t.double()
            IntArray(m) { i -> model.continuous("x${i + 1}_${j + 1}", t.double(), upper = 1.0) }
        }
        for (j in 0 until n) model.row("serve${j + 1}", 'E', 1.0, List(m) { i -> serve[j][i] to 1.0 })
        for (i in 0 until m) {
            model.row("cap${i + 1}", 'L', 0.0, List(n) { j -> serve[j][i] to demand[j] } + (open[i] to -capacity[i]))
            for (j in 0 until n) {
                model.row(
                    "open${i + 1}_${j + 1}",
                    'L',
                    0.0,
                    listOf(serve[j][i] to 1.0, open[i] to -1.0),
                )
            }
        }
        return listOf(file to model.mps())
    }

    /** Generalised assignment (`gap1`…`gap12`): the number of problems, then each one's `m n`, the m×n costs, the m×n
     *  resources and the m capacities. Maximised, as the library's optimal values are stated: each job to one agent,
     *  each agent within its capacity. */
    fun generalisedAssignment(file: String, text: String): List<Pair<String, String>> {
        val t = Tokens(text)
        return List(t.int()) { k ->
            val (m, n) = t.int() to t.int()
            val model = MipModel("$file-${k + 1}", maximize = true)
            val x = Array(m) { i -> IntArray(n) { j -> model.binary("x${i + 1}_${j + 1}", t.double()) } }
            val resource = Array(m) { DoubleArray(n) { t.double() } }
            for (j in 0 until n) model.row("job${j + 1}", 'E', 1.0, List(m) { i -> x[i][j] to 1.0 })
            for (i in 0 until m) model.row("agent${i + 1}", 'L', t.double(), List(n) { j -> x[i][j] to resource[i][j] })
            "$file-${k + 1}" to model.mps()
        }
    }

    /** Multidimensional knapsack (`mknapcb1`…`mknapcb9`): the number of problems, then each one's `n m` and optimal
     *  value (0 when unknown), the n profits, the m×n weights and the m capacities. Maximise profit within every
     *  capacity. */
    fun multidimensionalKnapsack(file: String, text: String): List<Pair<String, String>> {
        val t = Tokens(text)
        return List(t.int()) { k ->
            val (n, m) = t.int() to t.int()
            t.double()
            val model = MipModel("$file-${k + 1}", maximize = true)
            val x = IntArray(n) { model.binary("x${it + 1}", t.double()) }
            val weight = Array(m) { DoubleArray(n) { t.double() } }
            for (i in 0 until m) model.row("c${i + 1}", 'L', t.double(), List(n) { j -> x[j] to weight[i][j] })
            "$file-${k + 1}" to model.mps()
        }
    }

    /** One-dimensional bin packing (`binpack1`…`binpack8`): the number of problems, then each one's identifier, bin
     *  capacity, item count and best known bin count, and the item sizes. Bins are capped a tenth above the best known
     *  count: at exactly it, merely finding a packing is the hard part for a MIP solver, and the spare bins leave the
     *  optimum where it was. Bins are used in order, which removes the symmetry between empty bins. An
     *  instance whose model would exceed [MAX_BIN_VARIABLES] item-bin variables is left out. */
    fun binPacking(file: String, text: String): List<Pair<String, String>> {
        val t = Tokens(text)
        return List(t.int()) { _ ->
            val id = t.word()
            val capacity = t.double()
            val (n, best) = t.int() to t.int()
            val bins = best + maxOf(1, best / SPARE_BINS_PER)
            val size = DoubleArray(n) { t.double() }
            if (n.toLong() * bins > MAX_BIN_VARIABLES) return@List null
            val model = MipModel("$file-$id")
            val used = IntArray(bins) { b -> model.binary("y${b + 1}", 1.0) }
            val packed = Array(n) { i -> IntArray(bins) { b -> model.binary("x${i + 1}_${b + 1}") } }
            for (i in 0 until n) model.row("item${i + 1}", 'E', 1.0, List(bins) { b -> packed[i][b] to 1.0 })
            for (b in 0 until bins) {
                model.row("bin${b + 1}", 'L', 0.0, List(n) { i -> packed[i][b] to size[i] } + (used[b] to -capacity))
                if (b > 0) model.row("order${b + 1}", 'L', 0.0, listOf(used[b] to 1.0, used[b - 1] to -1.0))
            }
            id to model.mps()
        }.filterNotNull()
    }

    /** Aircraft landing (`airland1`…`airland13`), one runway, the static case: `p` and the freeze time, then for each
     *  plane its appearance, earliest, target and latest landing times, its early and late penalties per unit, and
     *  the separation it needs before each plane. Each pair of planes lands in one order, the separation enforced by a
     *  big-M on the order chosen. An instance of more than [MAX_PLANES] planes is left out. */
    fun aircraftLanding(file: String, text: String): List<Pair<String, String>> {
        val t = Tokens(text)
        val p = t.int()
        t.double()
        if (p > MAX_PLANES) return emptyList()
        val earliest = DoubleArray(p)
        val target = DoubleArray(p)
        val latest = DoubleArray(p)
        val early = DoubleArray(p)
        val late = DoubleArray(p)
        val separation = Array(p) { DoubleArray(p) }
        for (i in 0 until p) {
            t.double()
            earliest[i] = t.double()
            target[i] = t.double()
            latest[i] = t.double()
            early[i] = t.double()
            late[i] = t.double()
            for (j in 0 until p) separation[i][j] = t.double()
        }
        val model = MipModel(file)
        val land = IntArray(p) { model.continuous("t${it + 1}", lower = earliest[it], upper = latest[it]) }
        val ahead = IntArray(p) { model.continuous("a${it + 1}", early[it], upper = target[it] - earliest[it]) }
        val behind = IntArray(p) { model.continuous("b${it + 1}", late[it], upper = latest[it] - target[it]) }
        for (i in 0 until p) {
            model.row("dev${i + 1}", 'E', target[i], listOf(land[i] to 1.0, ahead[i] to 1.0, behind[i] to -1.0))
        }
        for (i in 0 until p) {
            for (j in i + 1 until p) {
                val first = model.binary("o${i + 1}_${j + 1}")
                // i first: t(j) ≥ t(i) + S(i,j), relaxed when j goes first; and the converse with S(j,i).
                val bigIj = latest[i] + separation[i][j] - earliest[j]
                val bigJi = latest[j] + separation[j][i] - earliest[i]
                model.row(
                    "s${i + 1}_${j + 1}",
                    'G',
                    separation[i][j] - bigIj,
                    listOf(land[j] to 1.0, land[i] to -1.0, first to -bigIj),
                )
                model.row(
                    "s${j + 1}_${i + 1}",
                    'G',
                    separation[j][i],
                    listOf(land[i] to 1.0, land[j] to -1.0, first to bigJi),
                )
            }
        }
        return listOf(file to model.mps())
    }

    /** Item-bin variables beyond which a bin packing instance makes too large a model for a seconds-scale solve. */
    private const val MAX_BIN_VARIABLES = 60_000L

    /** One spare bin per this many of the best known count. */
    private const val SPARE_BINS_PER = 10

    /** Planes beyond which the pairwise ordering makes too large a model for a seconds-scale solve. */
    private const val MAX_PLANES = 200

    /** The whitespace-separated words of a file, read in order. */
    private class Tokens(text: String) {
        private val words = text.split(Regex("\\s+")).filter { it.isNotEmpty() }
        private var next = 0

        fun word(): String = words.getOrNull(next++) ?: error("OR-Library file ended early")

        fun double(): Double = word().toDouble()

        fun int(): Int = word().toDouble().toInt()
    }
}
