package com.eignex.klause.backtrack

import com.eignex.kumulant.stat.decay.EwmaMeanStat

// The exponential-moving-average counterpart of GlucoseRestart's bounded LBD/trail windows (Biere and Fröhlich,
// "Evaluating CDCL Restart Schemes", POS 2015, https://doi.org/10.29007/89dw). Restart when the fast LBD
// average runs hotter than the slow one (`recentLbd · restartMargin > globalLbd`), unless the trail is well
// above its slow average (`trailSize > blockingFactor · globalTrail`). No restart within the first `warmup`
// conflicts. The averages are never cleared on restart: the EWMA decays a spent run out on its own.
internal class EmaRestart(
    fastAlpha: Double = 1.0 / 32,
    slowAlpha: Double = 1.0 / 16_384,
    private val restartMargin: Double = 0.8,
    private val blockingFactor: Double = 1.4,
    private val warmup: Int = 50,
) {
    init {
        require(restartMargin > 0.0) { "restartMargin must be positive, got $restartMargin" }
        require(blockingFactor > 0.0) { "blockingFactor must be positive, got $blockingFactor" }
        require(warmup >= 0) { "warmup must be non-negative, got $warmup" }
    }

    private val recentLbd = EwmaMeanStat(alpha = fastAlpha)
    private val globalLbd = EwmaMeanStat(alpha = slowAlpha)
    private val globalTrail = EwmaMeanStat(alpha = slowAlpha)
    private var conflicts = 0L

    fun recordConflict(lbd: Int, trailSize: Int): Boolean {
        conflicts++
        recentLbd.update(lbd.toDouble(), timestampNanos = 0L, weight = 1.0)
        globalLbd.update(lbd.toDouble(), timestampNanos = 0L, weight = 1.0)
        globalTrail.update(trailSize.toDouble(), timestampNanos = 0L, weight = 1.0)
        if (conflicts < warmup) return false

        // A trail far above its average means the solver is driving deep toward a model.
        if (trailSize > blockingFactor * globalTrail.read(0L).mean) return false

        return recentLbd.read(0L).mean * restartMargin > globalLbd.read(0L).mean
    }
}
