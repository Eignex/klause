package com.eignex.klause.bench.catalog

internal object ContinuousArithmeticSuite {
    val suite = suite("continuous-arithmetic", "Continuous product source and grid execution controls") {
        license = "internal"
        for ((name, optimum) in listOf("square" to 1L, "product" to 0L, "chain" to 0L, "power" to 4L)) {
            vendoredMzn(name, Category.OPTIMIZATION, Expected.Opt(optimum),
                model = "continuous-arithmetic/$name.mzn")
        }
    }
}
