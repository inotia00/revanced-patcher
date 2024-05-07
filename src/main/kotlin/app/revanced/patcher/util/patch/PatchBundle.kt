@file:Suppress("unused")

package app.revanced.patcher.util.patch

import app.revanced.patcher.extensions.PatchExtensions.patchName
import app.revanced.patcher.patch.PatchClass
import dalvik.system.PathClassLoader
import org.jf.dexlib2.DexFileFactory
import java.io.File
import java.net.URLClassLoader
import java.util.jar.JarFile

/**
 * A patch bundle.
 */
sealed class PatchBundle : Iterable<PatchClass> {
    internal abstract fun classLoader(): ClassLoader
    internal abstract val patchClassNames: Iterable<String>

    private val patches = sequence {
        with(classLoader()) {
            yieldAll(patchClassNames.map { loadClass(it) })
        }
    }.filter {
        it.isAnnotationPresent(app.revanced.patcher.patch.annotations.Patch::class.java)
    }.map {
        @Suppress("UNCHECKED_CAST")
        it as PatchClass
    }.sortedBy { it.patchName }

    override fun iterator() = patches.iterator()

    /**
     * A patch bundle of type [Jar].
     *
     * @param patchBundlePath The path to a patch bundle.
     */
    class Jar(private val patchBundlePath: String) : PatchBundle() {
        override fun classLoader() = URLClassLoader(arrayOf(File(patchBundlePath).toURI().toURL()), javaClass.classLoader)
        override val patchClassNames =
            JarFile(patchBundlePath)
                .entries()
                .toList() // TODO: find a cleaner solution than that to filter non class files
                .filter { it.name.endsWith(".class") && !it.name.contains("$") }
                .map { it.realName.replace('/', '.').replace(".class", "") }
    }

    /**
     * A patch bundle of type [Dex] format.
     *
     * @param patchBundlePath The path to a patch bundle of dex format.
     */
    class Dex(private val patchBundlePath: String) : PatchBundle() {
        override fun classLoader() = PathClassLoader(patchBundlePath, null, javaClass.classLoader)
        override val patchClassNames = DexFileFactory.loadDexFile(patchBundlePath, null).classes.map {
            it.type
                .substring(1, it.length - 1)
                .replace('/', '.')
        }
    }
}