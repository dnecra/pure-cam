package com.necra.purecam.core

/**
 * How a still is cropped when it reaches MediaStore.
 *
 * The camera always streams its largest native JPEG. [RAW] writes those bytes untouched, so the
 * saved file keeps the full sensor resolution. The other options apply a centre crop in software.
 */
enum class SaveCrop {
    RAW,
    R16_9,
    FULLSCREEN
}
