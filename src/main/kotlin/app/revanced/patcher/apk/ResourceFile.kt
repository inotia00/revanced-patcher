package app.revanced.patcher.apk

import com.reandroid.xml.XMLDocument
import com.reandroid.xml.XMLException
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream

/**
 * A resource file inside an [Apk].
 */
class ResourceFile private constructor(
    internal val handle: Handle,
    private val archive: Archive,
    private val resources: Apk.ResourceContainer,
    readResult: Archive.ReadResult?
) :
    Closeable {

    /**
     * @param virtualPath The resource file path (res/drawable-hdpi/icon.png)
     * @param archivePath The actual file path in the archive (res/4a.png)
     * @param close An action to perform when the file associated with this handle is closed
     */
    internal data class Handle(val virtualPath: String, val archivePath: String, val close: () -> Unit)

    private var changed = false
    private val xml = readResult?.xml ?: handle.virtualPath.endsWith(".xml")

    /**
     * @param handle The [FileHandle] associated with this file
     * @param archive The [Archive] that the file resides in
     * @param resources Resources used to resolve paths and encode XML
     */
    internal constructor(handle: Handle, archive: Archive, resources: Apk.ResourceContainer) : this(
        handle,
        archive,
        resources,
        archive.read(resources, handle)
    )

    var contents = readResult?.data ?: ByteArray(0)
        set(value) {
            changed = true
            field = value
        }

    val exists = readResult != null

    override fun toString() = handle.virtualPath

    init {
        archive.lock(this)
    }

    override fun close() {
        if (changed) {
            if (xml) archive.writeXml(
                resources,
                handle,
                try {
                    XMLDocument.load(String(contents))
                } catch (e: XMLException) {
                    throw Apk.ApkException.Encode("Failed to parse XML while writing ${handle.virtualPath}", e)
                }
            ) else archive.writeRaw(handle, contents)
        }
        handle.close()
        archive.unlock(this)
    }

    companion object {
        const val DEFAULT_BUFFER_SIZE = 4096
    }

    fun inputStream(): InputStream = ByteArrayInputStream(contents)
    fun outputStream(bufferSize: Int = DEFAULT_BUFFER_SIZE): OutputStream =
        object : ByteArrayOutputStream(bufferSize) {
            override fun close() {
                this@ResourceFile.contents = if (buf.size > count) buf.copyOf(count) else buf
                super.close()
            }
        }
}