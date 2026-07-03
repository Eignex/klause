package com.eignex.klause.localsearch.movesource

/**
 * The named sources axis of an LS recipe: a registry mapping a stable label to a default-configured
 * [MoveSource], and a parser turning a `sources=…` spec into the [ConfiguredSource] list a
 * source-driven strategy draws from. The single place a source label resolves to an instance with
 * its default numeric params, so the CLI selector and the cross-product arm generator share one
 * catalog rather than hard-coding constructors. Per-source numeric overrides are a caller concern
 * layered on top.
 */
object MoveSourceCatalog {

    private val factories: Map<String, () -> MoveSource> = linkedMapOf(
        "violated" to { ViolatedRepairs(DEFAULT_VIOLATED_SAMPLE) },
        "frontier" to { Frontier(DEFAULT_VIOLATED_SAMPLE, DEFAULT_FRONTIER_CAP) },
        "structured" to { SatisfiedStructured.sampled(DEFAULT_SATISFIED_SAMPLE) },
        "elected" to { SatisfiedStructured.elected(DEFAULT_ELECTED_CAP) },
        "objective" to { ObjectiveSeed() },
        "argmin" to { ArgminJump(DEFAULT_ARGMIN_VARS) },
        "stall-swaps" to { StallSwaps(DEFAULT_STALL_SWAP_CAP) },
        "ejection-chains" to { EjectionChains(DEFAULT_CHAIN_CAP, DEFAULT_CHAIN_DEPTH) },
        "stall-kick" to { StallKick(DEFAULT_KICK_VARS) },
        "pair-swap" to { PairSwap(DEFAULT_PAIR_SWAP_CAP) },
        "clique-swap" to { CliqueSwap(DEFAULT_CLIQUE_SWAP_CAP) },
        "flip-propagate" to { FlipAndPropagate(DEFAULT_FLIP_PROPAGATE_CAP, DEFAULT_FLIP_PROPAGATE_DEPTH) },
        "extended-structured" to { SatisfiedStructured.sampledExtended(DEFAULT_SATISFIED_SAMPLE) },
        "extended-elected" to { SatisfiedStructured.electedExtended(DEFAULT_ELECTED_CAP) },
        "extended-repairs" to { ViolatedRepairs.extended(DEFAULT_VIOLATED_SAMPLE) },
    )

    /** Every known source label, in catalog order. */
    val labels: List<String> get() = factories.keys.toList()

    /** A default-configured source for [label]; errors on an unknown label. */
    fun configured(label: String): ConfiguredSource = ConfiguredSource(
        (factories[label] ?: error("unknown move source '$label' (have ${labels.joinToString()})")).invoke(),
    )

    /** The stable [MoveSourceId] the source [label] resolves to — for matching a configured source
     *  back to its catalog token (e.g. removing `violated`, whose source id is `violated-repairs`).
     *  Tokens that share a generator share an id (`structured` and `elected` both map to
     *  `satisfied-structured`), so a removal by either drops that source. */
    fun idOf(label: String): MoveSourceId = configured(label).source.id

    /** Resolve a comma-separated `sources=` spec into configured sources, order preserved and blanks
     *  skipped (e.g. `"violated, frontier, argmin"`). An empty spec yields an empty list. */
    fun parse(spec: String): List<ConfiguredSource> =
        spec.split(',').map { it.trim() }.filter { it.isNotEmpty() }.map { configured(it) }

    private const val DEFAULT_VIOLATED_SAMPLE = 4
    private const val DEFAULT_FRONTIER_CAP = 32
    private const val DEFAULT_SATISFIED_SAMPLE = 4
    private const val DEFAULT_ELECTED_CAP = 8
    private const val DEFAULT_ARGMIN_VARS = 8
    private const val DEFAULT_STALL_SWAP_CAP = 16
    private const val DEFAULT_CHAIN_CAP = 8
    private const val DEFAULT_CHAIN_DEPTH = 16
    private const val DEFAULT_KICK_VARS = 8
    private const val DEFAULT_PAIR_SWAP_CAP = 8
    private const val DEFAULT_CLIQUE_SWAP_CAP = 8
    private const val DEFAULT_FLIP_PROPAGATE_CAP = 8
    private const val DEFAULT_FLIP_PROPAGATE_DEPTH = 16
}
