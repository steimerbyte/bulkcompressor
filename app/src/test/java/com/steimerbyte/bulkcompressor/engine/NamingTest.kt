package com.steimerbyte.bulkcompressor.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Dateinamen: die App schreibt im selben Ordner neben das Original, und
 * ein doppelter Name wuerde dort entweder eine Datei ueberschreiben oder
 * den ganzen Lauf abbrechen.
 */
class NamingTest {

    @Test
    fun `endung wird klein und ohne punkt geliefert`() {
        assertEquals("jpg", Naming.extensionOf("IMG_0001.JPG"))
        assertEquals("", Naming.extensionOf("VID_0001"))
    }

    @Test
    fun `komprimierter name haengt _compressed an`() {
        assertEquals("IMG_0001_compressed.jpg", Naming.compressedName("IMG_0001.jpg"))
        assertEquals("clip_compressed", Naming.compressedName("clip"))
    }

    @Test
    fun `formatwechsel tauscht die endung`() {
        assertEquals("IMG_0001_compressed.webp", Naming.compressedName("IMG_0001.jpg", "webp"))
    }

    @Test
    fun `freier name bleibt unveraendert`() {
        assertEquals("a_compressed.jpg", Naming.uniqueName("a_compressed.jpg") { false })
    }

    @Test
    fun `belegter name bekommt eine nummer`() {
        val taken = setOf("a_compressed.jpg", "a_compressed_2.jpg")
        assertEquals("a_compressed_3.jpg", Naming.uniqueName("a_compressed.jpg") { it in taken })
    }

    @Test
    fun `punkt am anfang gilt nicht als endung`() {
        assertEquals(".nomedia_compressed.jpg", Naming.compressedName(".nomedia", "jpg"))
        assertEquals("", Naming.extensionOf(".nomedia"))
    }

    @Test
    fun `nur mp4 familie darf in place ersetzt werden`() {
        assertTrue(Naming.isMp4Compatible("mp4"))
        assertTrue(Naming.isMp4Compatible("mov"))
        assertFalse(Naming.isMp4Compatible("mkv"))
        assertFalse(Naming.isMp4Compatible("webm"))
    }

    @Test
    fun `endungen trennen video und bild`() {
        assertTrue(Naming.isVideo("mts"))
        assertTrue(Naming.isVideo("3gp"))
        assertFalse(Naming.isVideo("jpg"))
        assertTrue(Naming.isImage("heic"))
        assertFalse(Naming.isImage("mp4"))
    }
}
