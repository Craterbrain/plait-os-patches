package app.plait.scan

import java.io.ByteArrayInputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class BoundedReadTest {
    @Test
    fun respectsLimitAcrossShortReadsAndReturnsOnlyAvailableBytes() {
        val stream = object : ByteArrayInputStream(byteArrayOf(1, 2, 3, 4)) {
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int =
                super.read(bytes, offset, minOf(length, 1))
        }

        assertArrayEquals(byteArrayOf(), stream.readUpTo(0))
        assertArrayEquals(byteArrayOf(1, 2), stream.readUpTo(2))
        assertEquals(2, stream.available())
        assertArrayEquals(byteArrayOf(3, 4), stream.readUpTo(5))
        assertArrayEquals(byteArrayOf(), stream.readUpTo(5))
    }
}
