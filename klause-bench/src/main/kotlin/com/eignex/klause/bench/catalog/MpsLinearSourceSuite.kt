package com.eignex.klause.bench.catalog

internal object MpsLinearSourceSuite {
    val suite = suite("mps-linear-source-v1", "Exact decimal MPS source and execution controls") {
        format = Format.MPS
        license = "Apache-2.0 (self-authored Eignex Klause fixtures)"
        vendored("bounded-mixed-decimal", expected = Expected.Sat,
            relPath = "mps-linear-source-v1/bounded-mixed-decimal.mps", tags = setOf("linear-source"),
        )
        vendored("indicator-shared-contradiction", expected = Expected.Unsat,
            relPath = "mps-linear-source-v1/indicator-shared-contradiction.mps", tags = setOf("linear-source"),
        )
        vendored("indicator-disabled", expected = Expected.Sat,
            relPath = "mps-linear-source-v1/indicator-disabled.mps", tags = setOf("linear-source"),
        )
        vendored("bounded-decimal-constant", expected = Expected.Sat,
            relPath = "mps-linear-source-v1/bounded-decimal-constant.mps", tags = setOf("linear-source"),
        )
        vendored("continuous-decimal", expected = Expected.Sat,
            relPath = "mps-linear-source-v1/continuous-decimal.mps", tags = setOf("linear-source"),
        )
        vendored("fixed-indicator-bound-closure", expected = Expected.Sat,
            relPath = "mps-linear-source-v1/fixed-indicator-bound-closure.mps", tags = setOf("linear-source"),
        )
        vendored("wide-bounded-mixed", expected = Expected.Sat,
            relPath = "mps-linear-source-v1/wide-bounded-mixed.mps", tags = setOf("linear-source"),
        )
        vendored("original-decimal-cancellation", expected = Expected.Sat,
            relPath = "mps-linear-source-v1/original-decimal-cancellation.mps", tags = setOf("linear-source"),
        )
        vendored("zero-row-disabled", expected = Expected.Sat,
            relPath = "mps-linear-source-v1/zero-row-disabled.mps", tags = setOf("linear-source"),
        )
    }
}
