package app.revanced.patcher

import app.revanced.patcher.apk.Apk
import app.revanced.patcher.extensions.PatchExtensions.dependencies
import app.revanced.patcher.extensions.PatchExtensions.patchName
import app.revanced.patcher.fingerprint.method.impl.MethodFingerprint.Companion.resolve
import app.revanced.patcher.patch.*
import app.revanced.patcher.util.TypeUtil.traverseClassHierarchy
import app.revanced.patcher.util.VersionReader
import app.revanced.patcher.util.proxy.mutableTypes.MutableClass
import app.revanced.patcher.util.proxy.mutableTypes.MutableClass.Companion.toMutable
import app.revanced.patcher.util.proxy.mutableTypes.MutableField.Companion.toMutable
import app.revanced.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import lanchon.multidexlib2.BasicDexFileNamer
import lanchon.multidexlib2.MultiDexIO
import org.jf.dexlib2.AccessFlags
import org.jf.dexlib2.iface.ClassDef
import org.jf.dexlib2.util.MethodUtil
import java.io.File

/**
 * The ReVanced Patcher.
 * @param options The options for the patcher.
 */
class Patcher(private val options: PatcherOptions) {
    private val context = PatcherContext()
    private val logger = options.logger
    private var decodingMode = Apk.ResourceDecodingMode.MANIFEST_ONLY

    companion object {
        @Suppress("SpellCheckingInspection")
        internal val dexFileNamer = BasicDexFileNamer()

        /**
         * The version of the ReVanced Patcher.
         */
        @JvmStatic
        val version = VersionReader.read()
    }

    init {
        // Decode manifest file.
        logger.info("Decoding manifest file of the base apk file")

        options.apkBundle.base.decodeResources(options, Apk.ResourceDecodingMode.MANIFEST_ONLY)
    }

    /**
     * Add [Patch]es to the patcher.
     * @param patches [Patch]es The patches to add.
     */
    fun addPatches(patches: Iterable<PatchClass>) {
        /**
         * Fill the cache with the instances of the [Patch]es for later use.
         * Note: Dependencies of the [Patch] will be cached as well.
         */
        fun PatchClass.isResource() {
            this.also {
                if (!ResourcePatch::class.java.isAssignableFrom(it)) return@also
                // Set the mode to decode all resources before running the patches.
                decodingMode = Apk.ResourceDecodingMode.FULL
            }.dependencies?.forEach { it.java.isResource() }
        }

        context.patches.addAll(patches.onEach(PatchClass::isResource))
    }

    /**
     * Add additional dex file container to the patcher.
     * @param files The dex file containers to add to the patcher.
     */
    fun addFiles(files: List<File>) {
        context.bytecodeContext.classes.apply {
            for (file in files) {
                logger.info("Merging $file")

                for (classDef in MultiDexIO.readDexFile(true, file, dexFileNamer, null, null).classes) {
                    val type = classDef.type

                    val existingClassIndex = indexOfFirst { it.type == type }
                    if (existingClassIndex == -1) {
                        logger.trace("Merging type $type")
                        add(classDef)
                    } else {
                        logger.trace("Type $type exists. Adding missing methods and fields.")

                        /**
                         * Add missing fields and methods from [from].
                         *
                         * @param from The class to add methods and fields from.
                         */
                        fun ClassDef.addMissingFrom(from: ClassDef) {
                            var changed = false
                            fun <T> ClassDef.transformClass(transform: (MutableClass) -> T): T {
                                fun toMutableClass() =
                                    if (this@transformClass is MutableClass) this else this.toMutable()
                                return transform(toMutableClass())
                            }

                            /**
                             * Check if the [AccessFlags.PUBLIC] flag is set.
                             *
                             * @return True, if the flag is set.
                             */
                            fun Int.isPublic() = AccessFlags.PUBLIC.isSet(this)

                            /**
                             * Make a class and its super class public recursively.
                             */
                            fun MutableClass.publicize() {
                                context.bytecodeContext.traverseClassHierarchy(this) {
                                    if (accessFlags.isPublic()) return@traverseClassHierarchy

                                    accessFlags = accessFlags.or(AccessFlags.PUBLIC.value)
                                }
                            }

                            /**
                             * Add missing methods to the class, considering to publicise the [ClassDef] if necessary.
                             */
                            fun ClassDef.addMissingMethods(): ClassDef {
                                fun getMissingMethods() = from.methods.filterNot {
                                    this@addMissingMethods.methods.any { original ->
                                        MethodUtil.methodSignaturesMatch(original, it)
                                    }
                                }

                                return getMissingMethods()
                                    .apply {
                                        if (isEmpty()) return@addMissingMethods this@addMissingMethods else changed =
                                            true
                                    }
                                    .map { it.toMutable() }
                                    .let { missingMethods ->
                                        this@addMissingMethods.transformClass { classDef ->
                                            classDef.apply {
                                                // Make sure the class is public, if the class contains public methods.
                                                if (missingMethods.any { it.accessFlags.isPublic() })
                                                    classDef.publicize()

                                                methods.addAll(missingMethods)
                                            }
                                        }
                                    }
                            }

                            /**
                             * Add missing fields to the class, considering to publicise the [ClassDef] if necessary.
                             */
                            fun ClassDef.addMissingFields(): ClassDef {
                                fun getMissingFields() = from.fields.filterNot {
                                    this@addMissingFields.fields.any { original -> original.name == it.name }
                                }

                                return getMissingFields()
                                    .apply {
                                        if (isEmpty()) return@addMissingFields this@addMissingFields else changed = true
                                    }
                                    .map { it.toMutable() }
                                    .let { missingFields ->
                                        this@addMissingFields.transformClass { classDef ->
                                            // Make sure the class is public, if the class contains public fields.
                                            if (missingFields.any { it.accessFlags.isPublic() })
                                                classDef.publicize()

                                            classDef.apply { fields.addAll(missingFields) }
                                        }
                                    }
                            }

                            this@apply[existingClassIndex] = addMissingMethods().addMissingFields()
                                .apply { if (!changed) return }
                        }

                        this@apply[existingClassIndex].addMissingFrom(classDef)
                    }
                }
            }
        }

    }

    /**
     * Execute the patcher.
     *
     * @param stopOnError If true, the patches will stop on the first error.
     * @return A pair of the name of the [Patch] and its [PatchResult].
     */
    fun execute(stopOnError: Boolean = false) = sequence {
        /**
         * Execute a [Patch] and its dependencies recursively.
         *
         * @param patchClass The [Patch] to execute.
         * @param executedPatches A map of [Patch]es paired to a boolean indicating their success, to prevent infinite recursion.
         * @return The result of executing the [Patch].
         */
        fun executePatch(
            patchClass: PatchClass,
            executedPatches: HashMap<String, ExecutedPatch>
        ): PatchResult {
            val patchName = patchClass.patchName

            // If the patch has already executed silently skip it.
            if (executedPatches.contains(patchName)) {
                if (!executedPatches[patchName]!!.success)
                    return PatchResult.Error("'$patchName' did not succeed previously")

                logger.trace("Skipping '$patchName' because it has already been executed")

                return PatchResult.Success
            }

            // Recursively execute all dependency patches.
            patchClass.dependencies?.forEach { dependencyClass ->
                val dependency = dependencyClass.java

                executePatch(dependency, executedPatches).also {
                    if (it is PatchResult.Success) return@forEach
                }.let {
                    with(it as PatchResult.Error) {
                        val errorMessage = it.cause?.stackTraceToString() ?: it.message
                        return PatchResult.Error(
                            "'$patchName' depends on '${dependency.patchName}' " +
                                    "but the following exception was raised: $errorMessage",
                            it
                        )
                    }
                }
            }

            val isResourcePatch = ResourcePatch::class.java.isAssignableFrom(patchClass)
            val patchInstance = patchClass.getDeclaredConstructor().newInstance()

            // TODO: implement this in a more polymorphic way.
            val patchContext = if (isResourcePatch) {
                context.resourceContext
            } else {
                context.bytecodeContext.apply {
                    val bytecodePatch = patchInstance as BytecodePatch
                    bytecodePatch.fingerprints?.resolve(this, classes)
                }
            }

            logger.trace("Executing '$patchName' of type: ${if (isResourcePatch) "resource" else "bytecode"}")

            return try {
                patchInstance.execute(patchContext)
            } catch (patchException: PatchResult.Error) {
                patchException
            } catch (exception: Exception) {
                PatchResult.Error("Unhandled patch exception: ${exception.message}", exception)
            }.also {
                executedPatches[patchName] = ExecutedPatch(patchInstance, it is PatchResult.Success)
            }
        }

        // Prevent from decoding the manifest twice if it is not needed.
        if (decodingMode == Apk.ResourceDecodingMode.FULL) {
            options.apkBundle.decodeResources(options, Apk.ResourceDecodingMode.FULL).forEach {
                logger.info("Decoding resources for $it apk file")
            }

            // region Workaround because Androlib does not support split apk files

            options.apkBundle.also {
                logger.info("Merging split apk resources to base apk resources")
            }.mergeResources(options)

            // endregion
        }

        logger.trace("Executing all patches")

        HashMap<String, ExecutedPatch>().apply {
            try {
                context.patches.forEach { patch ->
                    val result = executePatch(patch, this)

                    yield(patch.patchName to result)
                    if (stopOnError && result is PatchResult.Error) return@sequence
                }
            } finally {
                values.reversed().forEach { (patch, _) ->
                    patch.close()
                }
            }
        }
    }

    /**
     * Save the patched dex file.
     *
     * @return The [PatcherResult] of the [Patcher].
     */
    fun save(): PatcherResult {
        val patchResults = buildList {
            if (decodingMode == Apk.ResourceDecodingMode.FULL) {
                logger.info("Writing patched resources")
                options.apkBundle.writeResources(options).forEach { writeResult ->
                    if (writeResult.exception is Apk.ApkException.Write) return@forEach

                    val patch = writeResult.apk.let {
                        when (it) {
                            is Apk.Base -> PatcherResult.Patch.Base(it)
                            is Apk.Split -> PatcherResult.Patch.Split(it)
                        }
                    }

                    add(patch)

                    logger.info("Patched resources written for ${writeResult.apk} apk file")
                }
            }
        }

        options.apkBundle.base.apply {
            logger.info("Writing patched dex files")
            dexFiles = bytecodeData.writeDexFiles()
        }

        return PatcherResult(patchResults)
    }

    private inner class PatcherContext {
        val patches = mutableListOf<PatchClass>()

        val bytecodeContext = BytecodeContext(options)
        val resourceContext = ResourceContext(options)
    }
}

/**
 * A result of executing a [Patch].
 *
 * @param patchInstance The instance of the [Patch] that was executed.
 * @param success The result of the [Patch].
 */
internal data class ExecutedPatch(val patchInstance: Patch<Context>, val success: Boolean)