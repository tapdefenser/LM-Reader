package com.lmreader.ui.reader

import kotlin.test.Test
import kotlin.test.assertEquals

class ReaderImageSamplingTest {
    @Test
    fun `sampling stays within the longest edge limit`() {
        assertEquals(1, sampleSizeForEdge(3000, 3000))
        assertEquals(2, sampleSizeForEdge(3001, 3000))
        assertEquals(2, sampleSizeForEdge(5999, 3000))
        assertEquals(2, sampleSizeForEdge(6000, 3000))
        assertEquals(4, sampleSizeForEdge(6001, 3000))
    }
}
