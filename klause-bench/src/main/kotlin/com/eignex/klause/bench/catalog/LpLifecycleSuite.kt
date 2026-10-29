package com.eignex.klause.bench.catalog

internal object LpLifecycleSuite {
    private const val REVISION = "73cac48c5340d775a477087198611862559be250"
    private val names = listOf("afiro", "adlittle", "atwood0", "avgas")
    private val collection = ExternalCollection(
        id = "highs-lp-lifecycle-$REVISION",
        url = "https://raw.githubusercontent.com/ERGO-Code/HiGHS/$REVISION/check/instances",
        license = "MIT (HiGHS public MPS test inputs)",
        reason = "Original MPS inputs at the immutable revision used by the LP basis corpus",
        fetch = FetchMethod.Files(names.map { "$it.mps" }, "mps") { name, text ->
            listOf(name.removeSuffix(".mps") to text)
        },
    )

    val suite = suite("lp-lifecycle-v1", "Pinned original MPS exact-claim lifecycle controls") {
        format = Format.MPS
        category = Category.OPTIMIZATION
        for (name in names) external(name, collection, "$name.mps")
    }
}
