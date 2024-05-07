package app.revanced.patcher.apk

import app.revanced.patcher.Patcher
import app.revanced.patcher.PatcherOptions
import com.reandroid.apk.ApkModule
import com.reandroid.apk.ResourceIds
import com.reandroid.apk.xmlencoder.EncodeMaterials
import com.reandroid.arsc.util.FrameworkTable
import com.reandroid.arsc.value.ResConfig
import com.reandroid.common.TableEntryStore
import java.io.File

/**
 * An [Apk] file of type [Apk.Split].
 *
 * @param files A list of apk files to load.
 */
class ApkBundle(files: List<File>) {
    /**
     * The [Apk.Base] of this [ApkBundle].
     */
    val base: Apk.Base

    /**
     * A map containing all the [Apk.Split]s in this bundle associated by their configuration.
     */
    val splits: Map<String, Apk.Split>?

    private fun ApkModule.isFeatureModule() = androidManifestBlock.manifestElement.let {
        it.searchAttributeByName("isFeatureSplit")?.valueAsBoolean == true || it.searchAttributeByName("configForSplit") != null
    }

    init {
        var baseApk: Apk.Base? = null
        val splitList = mutableListOf<Apk.Split>()

        files.forEach {
            val module = ApkModule.loadApkFile(it)
            when {
                module.isBaseModule -> {
                    if (baseApk != null) {
                        throw IllegalArgumentException("Cannot have more than one base apk")
                    }
                    baseApk = Apk.Base(module)
                }

                !module.isFeatureModule() -> {
                    val config = module.split.removePrefix("config.")

                    splitList.add(
                        when {
                            config.length == 2 -> Apk.Split.Language(config, module)
                            Apk.Split.Library.architectures.contains(config) -> Apk.Split.Library(config, module)
                            ResConfig.Density.valueOf(config) != null -> Apk.Split.Asset(config, module)
                            else -> throw IllegalArgumentException("Unknown split: $config")
                        }
                    )
                }
            }
        }

        splits = splitList.takeIf { it.size > 0 }?.let { splitList.associateBy { it.config } }
        base = baseApk ?: throw IllegalArgumentException("Base apk not found")
    }

    /**
     * A [Sequence] yielding all [Apk]s in this [ApkBundle].
     */
    val all = sequence {
        yield(base)
        splits?.values?.let {
            yieldAll(it)
        }
    }

    /**
     * Write all the [Apk]s inside the bundle to a folder.
     *
     * @param options The [PatcherOptions] of the [Patcher].
     * @param folder The folder to write the [Apk]s to.
     * @return A sequence of the [Apk] files which are being refreshed.
     */
    internal fun write(options: PatcherOptions, folder: File) = all.map {
        val file = folder.resolve(it.toString())
        var exception: Apk.ApkException? = null
        try {
            it.write(options, file)
        } catch (e: Apk.ApkException) {
            exception = e
        }

        SplitApkResult(it, file, exception)
    }

    inner class GlobalResources {
        internal val entryStore = TableEntryStore()
        internal val resTable: ResourceIds.Table.Package
        internal val encodeMaterials = EncodeMaterials()

        /**
         * Get the [Apk.ResourceContainer] for the specified configuration.
         *
         * @param config The config to search for.
         */
        fun query(config: String) = splits?.get(config)?.resources ?: base.resources

        /**
         * Resolve a resource id for the specified resource.
         *
         * @param type The type of the resource.
         * @param name The name of the resource.
         * @return The id of the resource.
         */
        fun resolve(type: String, name: String) =
            resTable.getResourceId(type, name) ?: throw Apk.ApkException.InvalidReference(type, name)

        init {
            val resourceIds = ResourceIds()
            all.map { it.resources }.forEach {
                if (it.tableBlock != null) {
                    entryStore.add(it.tableBlock)
                    resourceIds.loadPackageBlock(it.packageBlock)
                }
                it.global = this
            }

            base.resources.also {
                encodeMaterials.currentPackage = it.packageBlock
                resTable =
                    resourceIds.table.listPackages().single { pkg -> pkg.id == it.packageBlock!!.id.toByte() }

                it.tableBlock!!.frameWorks.forEach { fw ->
                    if (fw is FrameworkTable) {
                        entryStore.add(fw)
                        encodeMaterials.addFramework(fw)
                    }
                }
            }
        }
    }

    /**
     * The global resource container.
     */
    val resources = GlobalResources()

    /**
     * The result of writing an [Apk] file.
     *
     * @param apk The corresponding [Apk] file.
     * @param file The location that the [Apk] was written to.
     * @param exception The optional [Apk.ApkException] when an exception occurred.
     */
    data class SplitApkResult(val apk: Apk, val file: File, val exception: Apk.ApkException? = null)
}