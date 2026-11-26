package app.plait.scan

import java.io.InputStream

/** Reads up to [limit] bytes without consuming the rest of the stream or closing it. */
internal fun InputStream.readUpTo(limit: Int): ByteArray {
    val bytes = ByteArray(limit)
    var offset = 0
    while (offset < limit) {
        val count = read(bytes, offset, limit - offset)
        if (count == -1) break
        offset += count
    }
    return if (offset == limit) bytes else bytes.copyOf(offset)
}
