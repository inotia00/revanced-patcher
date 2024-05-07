package app.revanced.patcher.apk

import app.revanced.patcher.util.xml.LazyXMLInputSource
import com.reandroid.apk.ApkModule
import com.reandroid.archive.ByteInputSource
import com.reandroid.archive.InputSource
import com.reandroid.arsc.chunk.xml.ResXmlDocument
import com.reandroid.xml.XMLDocument
import com.reandroid.xml.XMLException
import java.io.IOException

private fun isResXml(inputSource: InputSource) = inputSource.openStream().use { ResXmlDocument.isResXmlBlock(it) }

/**
 * A class for reading/writing files in an [ApkModule].
 *
 * @param module The [ApkModule] to operate on.
 */
internal class Archive(private val module: ApkModule) {
    /**
     * The result of a [read] operation.
     *
     * @param xml Whether the contents were decoded from a [ResXmlDocument].
     * @param data The contents of the file.
     */
    class ReadResult(val xml: Boolean, val data: ByteArray)

    private val archive = module.apkArchive

    private val lockedFiles = mutableMapOf<String, ResourceFile>()

    /**
     * Lock the [ResourceFile], preventing it from being opened again until it is unlocked.
     */
    fun lock(file: ResourceFile) {
        val path = file.handle.archivePath
        if (lockedFiles.contains(path)) {
            throw Apk.ApkException.Decode("${file.handle.virtualPath} is locked. If you are a patch developer, make sure you always close files.")
        }
        lockedFiles[path] = file
    }

    /**
     * Unlock the [ResourceFile], allowing patches to open it again.
     */
    fun unlock(file: ResourceFile) {
        lockedFiles.remove(file.handle.archivePath)
    }

    /**
     * Get all open files.
     *
     * @return A list of all currently open files
     */
    fun openFiles() = lockedFiles.values.toList()

    /**
     * Read an entry from the archive.
     *
     * @param resources The [Apk.ResourceContainer] to use when decoding XML.
     * @param handle The [ResourceFile.Handle] to read from.
     * @return A [ReadResult] containing the contents of the entry.
     */
    fun read(resources: Apk.ResourceContainer, handle: ResourceFile.Handle) =
        archive.getInputSource(handle.archivePath)?.let { inputSource ->
            try {
                val xml = when {
                    inputSource is LazyXMLInputSource -> inputSource.document
                    isResXml(inputSource) -> module.loadResXmlDocument(
                        inputSource
                    ).decodeToXml(resources.global.entryStore, resources.packageBlock?.id ?: 0)

                    else -> null
                }

                ReadResult(
                    xml != null,
                    xml?.toText()?.toByteArray() ?: inputSource.openStream().use { it.readAllBytes() })
            } catch (e: XMLException) {
                throw Apk.ApkException.Decode("Failed to decode XML while reading ${handle.virtualPath}", e)
            } catch (e: IOException) {
                throw Apk.ApkException.Decode("Could not read ${handle.virtualPath}", e)
            }
        }

    /**
     * Write the byte array to the archive entry associated with the [ResourceFile.Handle].
     *
     * @param handle The file whose contents will be replaced.
     * @param content The content of the file.
     */
    fun writeRaw(handle: ResourceFile.Handle, content: ByteArray) = archive.add(ByteInputSource(content, handle.archivePath))

    /**
     * Write the XML to the entry associated with the [ResourceFile.Handle].
     *
     * @param resources The [Apk.ResourceContainer] used to encode the file.
     * @param handle The file whose contents will be replaced.
     * @param document The XML document to encode.
     */
    fun writeXml(resources: Apk.ResourceContainer, handle: ResourceFile.Handle, document: XMLDocument) = archive.add(
        LazyXMLInputSource(
            handle.archivePath,
            document,
            resources.global.encodeMaterials
        )
    )
}