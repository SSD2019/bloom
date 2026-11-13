package com.rrajath.grove.remote

import com.rrajath.grove.capture.DatetreeType
import com.rrajath.grove.capture.TargetLocation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EmacsConfigMapperTest {

    @Test
    fun `maps emacs capture templates with shortcut keys and target variants`() {
        val config = EmacsConfigDto(
            todoKeywords = "TODO NEXT | DONE CANCELLED",
            captureTemplates = listOf(
                EmacsCaptureTemplateDto(
                    key = "t",
                    name = "Todo",
                    target = EmacsCaptureTargetDto(
                        type = "file+headline",
                        file = "tasks.org",
                        path = listOf("Inbox"),
                    ),
                    template = "* TODO %?\n%U",
                    prepend = false,
                ),
                EmacsCaptureTemplateDto(
                    key = "j",
                    name = "Journal",
                    target = EmacsCaptureTargetDto(
                        type = "file+datetree",
                        file = "journal.org",
                        treeType = "week",
                    ),
                    template = "* %?",
                ),
                EmacsCaptureTemplateDto(
                    key = "n",
                    name = "Quick Note",
                    target = EmacsCaptureTargetDto(
                        type = "file",
                        file = "notes.org",
                    ),
                    template = "* %?",
                    prepend = true,
                ),
                EmacsCaptureTemplateDto(
                    key = "i",
                    name = "By ID",
                    target = EmacsCaptureTargetDto(
                        type = "id",
                        id = "abc-123",
                    ),
                    template = "* %?",
                ),
            ),
        )

        val mapped = EmacsConfigMapper.toCaptureTemplates(config)
        assertEquals(4, mapped.size)

        assertEquals("t", mapped[0].shortcutKey)
        assertTrue(mapped[0].fromEmacs)
        assertEquals("tasks.org", mapped[0].targetFile)
        assertEquals(
            TargetLocation.UnderHeading(path = listOf("Inbox"), appendLast = true),
            mapped[0].location,
        )

        assertEquals("j", mapped[1].shortcutKey)
        assertEquals(
            TargetLocation.Datetree(treeType = DatetreeType.WEEK),
            mapped[1].location,
        )

        assertEquals("n", mapped[2].shortcutKey)
        assertEquals(TargetLocation.TopOfFile, mapped[2].location)

        assertEquals("i", mapped[3].shortcutKey)
        assertEquals(TargetLocation.UnderHeading(id = "abc-123", appendLast = true), mapped[3].location)
    }
}

