package app.revanced.patcher

import app.revanced.patcher.apk.Apk
import app.revanced.patcher.apk.ApkBundle
import app.revanced.patcher.apk.ResourceFile
import app.revanced.patcher.util.method.MethodWalker
import org.jf.dexlib2.iface.Method
import org.w3c.dom.Document
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

/**
 * A common class to constrain [Context] to [BytecodeContext] and [ResourceContext].
 * @param apkBundle the [ApkBundle] for this context.
 */
sealed class Context(val apkBundle: ApkBundle)

/**
 * A context for the bytecode of an [Apk.Base] file.
 *
 * @param apkBundle The [ApkBundle] for this context.
 */
class BytecodeContext internal constructor(apkBundle: ApkBundle) : Context(apkBundle) {
    /**
     * The list of classes.
     */
    val classes = apkBundle.base.bytecodeData.classes

    /**
     * Create a [MethodWalker] instance for the current [BytecodeContext].
     *
     * @param startMethod The method to start at.
     * @return A [MethodWalker] instance.
     */
    fun traceMethodCalls(startMethod: Method) = MethodWalker(this, startMethod)
}

/**
 * A context for [Apk] file resources.
 *
 * @param apkBundle the [ApkBundle] for this context.
 */
class ResourceContext internal constructor(apkBundle: ApkBundle) : Context(apkBundle) {

    /**
     * Open an [DomFileEditor] for a given DOM file.
     *
     * @param inputStream The input stream to read the DOM file from.
     * @return A [DomFileEditor] instance.
     */
    fun openXmlFile(inputStream: InputStream) = DomFileEditor(inputStream)
}

/**
 * Wrapper for a file that can be edited as a dom document.
 *
 * This constructor does not check for locks to the file when writing.
 * Use the secondary constructor.
 *
 * @param inputStream the input stream to read the xml file from.
 * @param outputStream the output stream to write the xml file to. If null, the file will be read only.
 *
 */
class DomFileEditor internal constructor(
    private val inputStream: InputStream,
    private val outputStream: Lazy<OutputStream>? = null,
    private val onClose: () -> Unit = { },
) : Closeable {
    private var closed: Boolean = false

    /**
     * The document of the xml file.
     */
    val file: Document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(inputStream)
        .also(Document::normalize)

    // Lazily open an output stream.
    // This is required because when constructing a DomFileEditor the output stream is created along with the input stream, which is not allowed.
    // The workaround is to lazily create the output stream. This way it would be used after the input stream is closed, which happens in the constructor.
    internal constructor(file: ResourceFile) : this(
        file.inputStream(),
        lazy { file.outputStream() },
        { file.close() })

    /**
     * Closes the editor. Write backs and decreases the lock count.
     *
     * Will not write back to the file if the file is still locked.
     */
    override fun close() {
        if (closed) return

        inputStream.close()

        // If the output stream is not null, do not close it.
        outputStream?.let {
            // Write back to the file.
            it.value.use { stream ->
                val result = StreamResult(stream)
                TransformerFactory.newInstance().newTransformer().transform(DOMSource(file), result)
            }

            it.value.close()
        }

        onClose()
        closed = true
    }
}