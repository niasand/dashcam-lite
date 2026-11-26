package com.niasand.dashcamlite.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class FileViewModePrefTest {
    @Test
    fun unknownValueOpensPreview() {
        assertEquals(FileViewMode.PREVIEW, FileViewModePref.decode(null))
        assertEquals(FileViewMode.PREVIEW, FileViewModePref.decode("grid"))
    }

    @Test
    fun roundTripKeepsTheChosenMode() {
        assertEquals("list", FileViewModePref.encode(FileViewMode.LIST))
        assertEquals(FileViewMode.LIST, FileViewModePref.decode(FileViewModePref.encode(FileViewMode.LIST)))
        assertEquals(FileViewMode.PREVIEW, FileViewModePref.decode(FileViewModePref.encode(FileViewMode.PREVIEW)))
    }
}
