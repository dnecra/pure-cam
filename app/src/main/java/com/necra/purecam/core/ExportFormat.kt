package com.necra.purecam.core

/**
 * Container written for still captures. The camera always delivers the largest native JPEG;
 * HEIC is produced by re-encoding that frame, JPEG is written untouched when no crop is needed.
 */
enum class ExportFormat(
    val label: String,
    val extension: String,
    val mimeType: String
) {
    JPEG("JPG", "jpg", "image/jpeg"),
    HEIC("HEIC", "heic", "image/heic");

    companion object {
        fun fromName(value: String?): ExportFormat =
            entries.firstOrNull { it.name == value } ?: JPEG
    }
}
