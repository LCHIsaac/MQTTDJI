package com.dji.dronecontrol.adapter.dji

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SnapshotFrameBytesTest {

    @Test
    fun fullBuffer_noCopy() {
        val data = byteArrayOf(1, 2, 3)
        assertArrayEquals(data, extractFrameBytes(data, 0, 3))
    }

    @Test
    fun slice_extractsRange() {
        val data = byteArrayOf(0, 1, 2, 3, 4)
        assertArrayEquals(byteArrayOf(2, 3), extractFrameBytes(data, 2, 2))
    }

    @Test
    fun invalidLength_returnsNull() {
        assertNull(extractFrameBytes(byteArrayOf(1), 0, 0))
    }
}
