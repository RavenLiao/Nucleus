/*
 * Copyright 2020-2022 JetBrains s.r.o. and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package io.github.kdroidfilter.nucleus.desktop.application.internal

import org.gradle.api.logging.Logger
import java.awt.AlphaComposite
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * Height of the macOS DMG window title bar in pixels.
 *
 * The Finder window bounds include the title bar, so the visible content area
 * is `boundsHeight - titleBarHeight`. This constant is used by the native
 * AppleScript DMG path to ensure the window is large enough for the full
 * background image to be visible, and by the electron-builder path to pad the
 * background image so the clipped region is transparent padding, not content.
 */
internal const val MACOS_DMG_TITLE_BAR_HEIGHT = 28

/**
 * Pads a DMG background image at the bottom to compensate for the macOS title bar.
 *
 * electron-builder sets the DMG window size to match the background image dimensions.
 * Because the macOS title bar takes [MACOS_DMG_TITLE_BAR_HEIGHT] pixels, the actual
 * content area is smaller, clipping the bottom of the image.
 *
 * This function adds transparent padding at the bottom so the clipped region is padding,
 * not actual content. It also handles `@2x` Retina variants if present alongside the
 * source, padding them with 2x the pixel count to match the same point offset.
 *
 * The original filename is preserved so that electron-builder's `@2x` auto-detection
 * (`transformBackgroundFileIfNeed`) continues to work.
 *
 * @return the padded image file in [outputDir], or [source] unchanged if padding fails.
 * @see <a href="https://github.com/kdroidFilter/Nucleus/issues/26">Issue #26</a>
 */
internal fun padDmgBackgroundForTitleBar(
    source: File,
    outputDir: File,
    logger: Logger? = null,
): File {
    try {
        val extension = source.extension.ifEmpty { "png" }
        val formatName = toImageIOFormat(extension)
        outputDir.mkdirs()

        // Preserve the original filename so electron-builder can still discover @2x variants
        val output = File(outputDir, source.name)
        if (!padImageBottom(source, output, MACOS_DMG_TITLE_BAR_HEIGHT, formatName, logger)) {
            return source
        }

        // Also pad the @2x Retina variant if it sits alongside the source.
        // Retina images need 2× the pixel padding to match the same point offset.
        val retinaName = "${source.nameWithoutExtension}@2x.$extension"
        val retinaSource = File(source.parentFile, retinaName)
        if (retinaSource.isFile) {
            val retinaOutput = File(outputDir, retinaName)
            padImageBottom(retinaSource, retinaOutput, MACOS_DMG_TITLE_BAR_HEIGHT * 2, formatName, logger)
        }

        return output
    } catch (e: Exception) {
        logger?.warn("Failed to pad DMG background, using original: ${e.message}")
        return source
    }
}

/**
 * Adds [padding] pixels of fully transparent space at the bottom of [source]
 * and writes the result to [output].
 *
 * @return `true` if successful, `false` if the image could not be read.
 */
private fun padImageBottom(
    source: File,
    output: File,
    padding: Int,
    formatName: String,
    logger: Logger?,
): Boolean {
    val original =
        ImageIO.read(source) ?: run {
            logger?.warn("Could not read DMG background image: ${source.absolutePath}, skipping padding")
            return false
        }

    val paddedHeight = original.height + padding
    val padded = BufferedImage(original.width, paddedHeight, BufferedImage.TYPE_INT_ARGB)
    val g = padded.createGraphics()
    // Fill the entire canvas with transparent pixels first
    g.composite = AlphaComposite.Clear
    g.color = Color(0, 0, 0, 0)
    g.fillRect(0, 0, padded.width, padded.height)
    // Draw the original image at the top
    g.composite = AlphaComposite.SrcOver
    g.drawImage(original, 0, 0, null)
    g.dispose()

    ImageIO.write(padded, formatName, output)
    logger?.info(
        "Padded DMG background with ${padding}px: " +
            "${original.width}x${original.height} → ${padded.width}x${padded.height}",
    )
    return true
}

/** Maps a file extension to the ImageIO format name. */
private fun toImageIOFormat(extension: String): String =
    when (extension.lowercase()) {
        "tiff", "tif" -> "tiff"
        else -> "png"
    }

/**
 * Reads the dimensions of an image file.
 *
 * @return a [Pair] of (width, height) in pixels, or `null` if the image cannot be read.
 */
internal fun readImageDimensions(file: File): Pair<Int, Int>? {
    return try {
        val readers = ImageIO.getImageReadersBySuffix(file.extension)
        if (!readers.hasNext()) return null
        val reader = readers.next()
        ImageIO.createImageInputStream(file).use { stream ->
            reader.input = stream
            val width = reader.getWidth(0)
            val height = reader.getHeight(0)
            reader.dispose()
            width to height
        }
    } catch (_: Exception) {
        null
    }
}
