package app.revanced.patcher

import app.revanced.patcher.apk.Apk
import app.revanced.patcher.extensions.PatchExtensions.dependencies
import app.revanced.patcher.extensions.PatchExtensions.patchName
import app.revanced.patcher.extensions.PatchExtensions.requiresIntegrations
import app.revanced.patcher.fingerprint.method.impl.MethodFingerprint.Companion.resolve
import app.revanced.patcher.patch.*
import app.revanced.patcher.util.VersionReader
import lanchon.multidexlib2.BasicDexFileNamer
import java.io.File

/**
 * The ReVanced Patcher.
 * @param options The options for the patcher.
 */
class Patcher(private val options: PatcherOptions) {
    private val context = PatcherContext(options)
    private val logger = options.logger
    private var mergeIntegrations = false

    companion object {
        /**
         * The version of the ReVanced Patcher.
         */
        @JvmStatic
        val version = VersionReader.read()

        @Suppress("SpellCheckingInspection")
        internal val dexFileNamer = BasicDexFileNamer()
    }

    /**
     * Add integrations to be merged by the patcher.
     * The integrations will only be merged, if necessary.
     *
     * @param integrations The integrations, must be dex files or dex file container such as ZIP, APK or DEX files.
     */
    fun addIntegrations(integrations: List<File>) = context.integrations.add(integrations)

    /**
     * Add [Patch]es to the patcher.
     * @param patches [Patch]es The patches to add.
     */
    fun addPatches(patches: Iterable<PatchClass>) {
        /**
         * Returns true if at least one patches or its dependencies matches the given predicate.
         */
        fun PatchClass.anyRecursively(predicate: (PatchClass) -> Boolean): Boolean =
            predicate(this) || dependencies?.any { it.java.anyRecursively(predicate) } == true

        // Determine if merging integrations is required.
        for (patch in patches) {
            if (patch.anyRecursively { it.requiresIntegrations }) {
                mergeIntegrations = true
                break
            }
        }

        context.patches.addAll(patches)
    }

    /**
     * Execute the patcher.
     *
     * @param stopOnError If true, the patches will stop on the first error.
     * @return A pair of the name of the [Patch] and a [PatchException] if it failed.
     */
    fun execute(stopOnError: Boolean = false) = sequence {
        /**
         * Execute a [Patch] and its dependencies recursively.
         *
         * @param patchClass The [Patch] to execute.
         * @param executedPatches A map of [Patch]es paired to a boolean indicating their success, to prevent infinite recursion.
         */
        fun executePatch(
            patchClass: PatchClass,
            executedPatches: HashMap<String, ExecutedPatch>
        ) {
            val patchName = patchClass.patchName

            // If the patch has already executed silently skip it.
            if (executedPatches.contains(patchName)) {
                if (!executedPatches[patchName]!!.success)
                    throw PatchException("'$patchName' did not succeed previously")

                logger.trace("Skipping '$patchName' because it has already been executed")

                return
            }

            // Recursively execute all dependency patches.
            patchClass.dependencies?.forEach { dependencyClass ->
                val dependency = dependencyClass.java

                try {
                    executePatch(dependency, executedPatches)
                } catch (throwable: Throwable) {
                    throw PatchException(
                        "'$patchName' depends on '${dependency.patchName}' " +
                                "but the following exception was raised: ${throwable.cause?.stackTraceToString() ?: throwable.message}",
                        throwable
                    )
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

            var success = false
            try {
                patchInstance.execute(patchContext)

                success = true
            } catch (patchException: PatchException) {
                throw patchException
            } catch (throwable: Throwable) {
                throw PatchException("Unhandled patch exception: ${throwable.message}", throwable)
            } finally {
                executedPatches[patchName] = ExecutedPatch(patchInstance, success)
            }
        }
        if (mergeIntegrations) context.integrations.merge(logger)

        logger.trace("Executing all patches")

        HashMap<String, ExecutedPatch>().apply {
            try {
                context.patches.forEach { patch ->
                    var exception: PatchException? = null
                    try {
                        executePatch(patch, this)
                    } catch (patchException: PatchException) {
                        exception = patchException
                    }

                    yield(patch.patchName to exception)
                    if (stopOnError && exception != null) return@sequence
                }
            } finally {
                values.reversed().forEach { (patch, _) ->
                    patch.close()
                }
            }
        }
    }

    /**
     * Write patched [Apk]s to a folder.
     *
     * @param folder The output folder.
     * @return The [PatcherResult] of the [Patcher].
     */
    fun write(folder: File): PatcherResult {
        val patchResults = buildList {
            logger.info("Writing patched apks")
            options.apkBundle.write(options, folder).forEach { writeResult ->
                if (writeResult.exception != null) {
                    logger.error("Got exception while writing ${writeResult.apk}: ${writeResult.exception.stackTraceToString()}")
                    return@forEach
                }

                val patch = writeResult.let {
                    when (it.apk) {
                        is Apk.Base -> PatcherResult.Patch.Base(it.apk, it.file)
                        is Apk.Split -> PatcherResult.Patch.Split(it.apk, it.file)
                    }
                }

                add(patch)

                logger.info("Wrote ${writeResult.apk}")
            }
        }

        return PatcherResult(patchResults)
    }
}

/**
 * A result of executing a [Patch].
 *
 * @param patchInstance The instance of the [Patch] that was executed.
 * @param success The result of the [Patch].
 */
internal data class ExecutedPatch(val patchInstance: Patch<Context>, val success: Boolean)