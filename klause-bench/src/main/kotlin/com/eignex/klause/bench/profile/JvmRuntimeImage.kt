package com.eignex.klause.bench.profile

import java.net.URI
import java.net.URLClassLoader
import java.nio.file.FileSystem
import java.nio.file.FileSystems
import java.nio.file.Path

internal fun <T> withJvmRuntimeImage(home: Path, block: (FileSystem) -> T): T {
    val image = FileSystems.newFileSystem(URI("jrt:/"), mapOf("java.home" to home.toString()))
    val loader = image.provider().javaClass.classLoader as? URLClassLoader
    return loader.use {
        image.use {
            requireNotNull(loader) { "Offline runtime image requires a separately loaded provider" }
            require(Class.forName("jdk.internal.jimage.BasicImageReader", false, loader).classLoader === loader) {
                "Offline image reader must use the separately loaded provider classes"
            }
            block(image)
        }
    }
}
