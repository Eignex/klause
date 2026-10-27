package com.eignex.klause.bench.source

import java.io.IOException

internal class CorpusUnavailable(cause: IOException) : IOException("corpus transport temporarily unavailable", cause)

internal inline fun <T> corpusTransport(block: () -> T): T = try {
    block()
} catch (failure: IOException) {
    throw CorpusUnavailable(failure)
}
