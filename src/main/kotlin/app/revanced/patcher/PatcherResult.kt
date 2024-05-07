package app.revanced.patcher

import app.revanced.patcher.apk.Apk
import java.io.File

/**
 * The result of a patcher.
 * @param apkFiles The patched [Apk] files.
 */
data class PatcherResult(val apkFiles: List<Patch>) {

    /**
     * The result of a patch.
     *
     * @param apk The patched [Apk] file.
     * @param file The location of the patched [Apk] file.
     */
    sealed class Patch(val apk: Apk, val file: File) {

        /**
         * The result of a patch of an [Apk.Split] file.
         *
         * @param apk The patched [Apk.Split] file.
         * @param file The location of the patched [Apk.Split] file.
         */
        class Split(apk: Apk.Split, file: File) : Patch(apk, file)

        /**
         * The result of a patch of an [Apk.Split] file.
         *
         * @param apk The patched [Apk.Base] file.
         * @param file The location of the patched [Apk.Base] file.
         */
        class Base(apk: Apk.Base, file: File) : Patch(apk, file)
    }
}