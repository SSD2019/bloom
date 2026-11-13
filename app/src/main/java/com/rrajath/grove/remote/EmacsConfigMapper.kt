package com.rrajath.grove.remote

import com.rrajath.grove.capture.CaptureTemplate
import com.rrajath.grove.capture.DatetreeType
import com.rrajath.grove.capture.TargetLocation

/**
 * Pure-Kotlin mapper from [EmacsConfigDto] to Bloom's [CaptureTemplate] models.
 */
object EmacsConfigMapper {

    fun toCaptureTemplates(config: EmacsConfigDto): List<CaptureTemplate> =
        config.captureTemplates.mapNotNull { dto -> mapTemplate(dto) }

    fun mapTemplate(dto: EmacsCaptureTemplateDto): CaptureTemplate? {
        val target = dto.target
        val location: TargetLocation = when (target.type) {
            "file" -> if (dto.prepend) TargetLocation.TopOfFile else TargetLocation.BottomOfFile
            "file+headline", "file+olp" -> TargetLocation.UnderHeading(
                path = target.path,
                appendLast = !dto.prepend,
            )
            "file+datetree", "file+olp+datetree" -> TargetLocation.Datetree(
                path = target.path,
                treeType = when (target.treeType.lowercase()) {
                    "week" -> DatetreeType.WEEK
                    "month" -> DatetreeType.MONTH
                    else -> DatetreeType.DAY
                },
            )
            "id" -> TargetLocation.UnderHeading(
                id = target.id,
                appendLast = !dto.prepend,
            )
            else -> return null
        }

        val targetFile = target.file?.takeIf { it.isNotBlank() } ?: "inbox.org"
        return CaptureTemplate(
            id = "emacs:${dto.key}",
            name = dto.name,
            targetFile = targetFile,
            location = location,
            template = dto.template,
            shortcutKey = dto.key,
            fromEmacs = true,
        )
    }
}

