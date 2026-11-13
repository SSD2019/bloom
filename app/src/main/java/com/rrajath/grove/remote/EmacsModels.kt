package com.rrajath.grove.remote

import kotlinx.serialization.Serializable
import java.io.IOException

/** Thrown when the Emacs HTTP server cannot be reached (e.g., WireGuard down, Emacs closed, timeout). */
class EmacsUnreachableException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** Thrown when the Emacs HTTP server returned a non-2xx HTTP status. */
class EmacsServerException(val statusCode: Int, message: String) : IOException("HTTP $statusCode: $message")

@Serializable
data class EmacsPingResponse(
    val status: String,
    val version: String,
    val orgDirectory: String,
)

@Serializable
data class EmacsFileEntryDto(
    val name: String,
    val lastModified: Long,
    val size: Long,
)

@Serializable
data class EmacsFilesResponse(
    val files: List<EmacsFileEntryDto>,
)

@Serializable
data class EmacsReadFileResponse(
    val name: String,
    val lastModified: Long,
    val size: Long,
    val content: String,
)

@Serializable
data class EmacsRawListResponse(
    val files: List<EmacsFileEntryDto> = emptyList(),
    val subdirs: List<String> = emptyList(),
)

@Serializable
data class EmacsDirExistsResponse(
    val exists: Boolean,
)

@Serializable
data class EmacsArchiveLocationDto(
    val file: String? = null,
    val headingPath: String = "",
)

@Serializable
data class EmacsCaptureTargetDto(
    val type: String,
    val file: String? = null,
    val path: List<String> = emptyList(),
    val id: String? = null,
    val treeType: String = "day",
)

@Serializable
data class EmacsCaptureTemplateDto(
    val key: String,
    val name: String,
    val entryType: String = "entry",
    val target: EmacsCaptureTargetDto,
    val template: String = "",
    val prepend: Boolean = false,
)

@Serializable
data class EmacsConfigDto(
    val version: String = "1.0.0",
    val orgDirectory: String = "",
    val todoKeywords: String = "TODO | DONE",
    val defaultPriority: String? = null,
    val deadlineWarningDays: Int = 14,
    val archiveLocation: EmacsArchiveLocationDto? = null,
    val attachIdDir: String = "data",
    val roamDirectory: String? = null,
    val dailiesDirectory: String? = null,
    val savedTags: List<String> = emptyList(),
    val captureTemplates: List<EmacsCaptureTemplateDto> = emptyList(),
)

@Serializable
internal data class RenameRequestDto(
    val oldPath: String,
    val newPath: String,
)

@Serializable
internal data class RawRenameRequestDto(
    val path: String,
    val newLeaf: String,
)

@Serializable
internal data class RawMoveDirRequestDto(
    val src: String,
    val dstParent: String,
)

