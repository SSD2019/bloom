package com.rrajath.grove.attach

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SvgzAttachmentTest {

    @Test
    fun `svg and svgz are classified as inline images`() {
        val svg = AttachmentLink.classify("file:diagram.svg", null)
        assertNotNull(svg)
        assertTrue(svg!!.isImage)
        assertEquals(AttachmentCategory.IMAGE, svg.category)

        val svgz = AttachmentLink.classify("attachment:diagram.svgz", null)
        assertNotNull(svgz)
        assertTrue(svgz!!.isImage)
        assertEquals(AttachmentCategory.IMAGE, svgz.category)
        assertEquals(AttachmentBase.ATTACH_DIR, svgz.base)
    }
}

