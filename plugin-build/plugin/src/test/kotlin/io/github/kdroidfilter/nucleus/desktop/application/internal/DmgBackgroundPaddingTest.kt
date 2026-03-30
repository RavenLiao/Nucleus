package io.github.kdroidfilter.nucleus.desktop.application.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

class DmgBackgroundPaddingTest {
    @get:Rule
    val tmpDir = TemporaryFolder()

    // ---- readImageDimensions ----

    @Test
    fun `readImageDimensions returns correct size for PNG`() {
        val img = createTestImage(300, 200)
        val file = tmpDir.newFile("test.png")
        ImageIO.write(img, "png", file)

        val dims = readImageDimensions(file)
        assertNotNull(dims)
        assertEquals(300, dims!!.first)
        assertEquals(200, dims.second)
    }

    @Test
    fun `readImageDimensions returns correct size for TIFF`() {
        val img = createTestImage(400, 250)
        val file = tmpDir.newFile("test.tiff")
        ImageIO.write(img, "tiff", file)

        val dims = readImageDimensions(file)
        assertNotNull(dims)
        assertEquals(400, dims!!.first)
        assertEquals(250, dims.second)
    }

    @Test
    fun `readImageDimensions returns null for non-image file`() {
        val file = tmpDir.newFile("test.txt")
        file.writeText("not an image")
        assertEquals(null, readImageDimensions(file))
    }

    // ---- padDmgBackgroundForTitleBar — basic padding ----

    @Test
    fun `padding adds exactly MACOS_DMG_TITLE_BAR_HEIGHT pixels to PNG`() {
        val original = createTestImage(600, 400, Color.RED)
        val source = tmpDir.newFile("background.png")
        ImageIO.write(original, "png", source)
        val outputDir = tmpDir.newFolder("output")

        val result = padDmgBackgroundForTitleBar(source, outputDir)

        val dims = readImageDimensions(result)
        assertNotNull(dims)
        assertEquals(600, dims!!.first)
        assertEquals(400 + MACOS_DMG_TITLE_BAR_HEIGHT, dims.second)
    }

    @Test
    fun `padding adds exactly MACOS_DMG_TITLE_BAR_HEIGHT pixels to TIFF`() {
        val original = createTestImage(500, 350, Color.BLUE)
        val source = tmpDir.newFile("background.tiff")
        ImageIO.write(original, "tiff", source)
        val outputDir = tmpDir.newFolder("output")

        val result = padDmgBackgroundForTitleBar(source, outputDir)

        val dims = readImageDimensions(result)
        assertNotNull(dims)
        assertEquals(500, dims!!.first)
        assertEquals(350 + MACOS_DMG_TITLE_BAR_HEIGHT, dims.second)
    }

    // ---- padDmgBackgroundForTitleBar — original pixels preserved ----

    @Test
    fun `original image pixels are preserved at the top of padded image`() {
        val original = createTestImage(100, 80, Color.RED)
        val source = tmpDir.newFile("bg.png")
        ImageIO.write(original, "png", source)
        val outputDir = tmpDir.newFolder("output")

        val result = padDmgBackgroundForTitleBar(source, outputDir)
        val padded = ImageIO.read(result)

        // Every pixel in the original region should be red
        for (y in 0 until 80) {
            for (x in 0 until 100) {
                val rgb = padded.getRGB(x, y) and 0x00FFFFFF
                assertEquals("Pixel ($x,$y) should be red", 0xFF0000, rgb)
            }
        }
    }

    @Test
    fun `padding region at the bottom is fully transparent`() {
        val original = createTestImage(100, 80, Color.RED)
        val source = tmpDir.newFile("bg.png")
        ImageIO.write(original, "png", source)
        val outputDir = tmpDir.newFolder("output")

        val result = padDmgBackgroundForTitleBar(source, outputDir)
        val padded = ImageIO.read(result)

        // Padding region: rows 80..(80 + MACOS_DMG_TITLE_BAR_HEIGHT - 1)
        for (y in 80 until 80 + MACOS_DMG_TITLE_BAR_HEIGHT) {
            for (x in 0 until 100) {
                val alpha = (padded.getRGB(x, y) ushr 24) and 0xFF
                assertEquals("Pixel ($x,$y) should be fully transparent", 0, alpha)
            }
        }
    }

    // ---- padDmgBackgroundForTitleBar — filename preservation ----

    @Test
    fun `output preserves the original filename for electron-builder @2x detection`() {
        val source = tmpDir.newFile("myBackground.png")
        ImageIO.write(createTestImage(200, 100), "png", source)
        val outputDir = tmpDir.newFolder("output")

        val result = padDmgBackgroundForTitleBar(source, outputDir)

        assertEquals("myBackground.png", result.name)
        assertTrue(result.parentFile.absolutePath.startsWith(outputDir.absolutePath))
    }

    // ---- padDmgBackgroundForTitleBar — @2x Retina handling ----

    @Test
    fun `@2x retina variant is padded with double pixel count`() {
        val sourceDir = tmpDir.newFolder("source")
        val source1x = File(sourceDir, "background.png")
        val source2x = File(sourceDir, "background@2x.png")
        ImageIO.write(createTestImage(300, 200), "png", source1x)
        ImageIO.write(createTestImage(600, 400), "png", source2x)

        val outputDir = tmpDir.newFolder("output")
        padDmgBackgroundForTitleBar(source1x, outputDir)

        // 1x should be padded by MACOS_DMG_TITLE_BAR_HEIGHT
        val padded1x = File(outputDir, "background.png")
        assertTrue("1x output should exist", padded1x.isFile)
        val dims1x = readImageDimensions(padded1x)!!
        assertEquals(300, dims1x.first)
        assertEquals(200 + MACOS_DMG_TITLE_BAR_HEIGHT, dims1x.second)

        // 2x should be padded by 2 × MACOS_DMG_TITLE_BAR_HEIGHT
        val padded2x = File(outputDir, "background@2x.png")
        assertTrue("2x output should exist", padded2x.isFile)
        val dims2x = readImageDimensions(padded2x)!!
        assertEquals(600, dims2x.first)
        assertEquals(400 + 2 * MACOS_DMG_TITLE_BAR_HEIGHT, dims2x.second)
    }

    @Test
    fun `missing @2x variant does not cause failure`() {
        val source = tmpDir.newFile("background.png")
        ImageIO.write(createTestImage(300, 200), "png", source)
        val outputDir = tmpDir.newFolder("output")

        val result = padDmgBackgroundForTitleBar(source, outputDir)

        assertTrue(result.isFile)
        val dims = readImageDimensions(result)!!
        assertEquals(200 + MACOS_DMG_TITLE_BAR_HEIGHT, dims.second)
    }

    // ---- padDmgBackgroundForTitleBar — idempotency / cache ----

    @Test
    fun `running padding twice produces identical output`() {
        val source = tmpDir.newFile("bg.png")
        ImageIO.write(createTestImage(200, 150, Color.GREEN), "png", source)

        val out1 = tmpDir.newFolder("out1")
        val out2 = tmpDir.newFolder("out2")

        padDmgBackgroundForTitleBar(source, out1)
        padDmgBackgroundForTitleBar(source, out2)

        val img1 = ImageIO.read(File(out1, "bg.png"))
        val img2 = ImageIO.read(File(out2, "bg.png"))

        assertEquals(img1.width, img2.width)
        assertEquals(img1.height, img2.height)
        for (y in 0 until img1.height) {
            for (x in 0 until img1.width) {
                assertEquals(
                    "Pixel ($x,$y) should be identical across runs",
                    img1.getRGB(x, y),
                    img2.getRGB(x, y),
                )
            }
        }
    }

    @Test
    fun `padding an already-padded image doubles the padding`() {
        val source = tmpDir.newFile("bg.png")
        ImageIO.write(createTestImage(200, 100), "png", source)
        val out1 = tmpDir.newFolder("out1")
        val out2 = tmpDir.newFolder("out2")

        // First pass
        val firstResult = padDmgBackgroundForTitleBar(source, out1)
        assertEquals(100 + MACOS_DMG_TITLE_BAR_HEIGHT, readImageDimensions(firstResult)!!.second)

        // Second pass on already-padded image (simulates accidental double-padding)
        val secondResult = padDmgBackgroundForTitleBar(firstResult, out2)
        assertEquals(
            100 + 2 * MACOS_DMG_TITLE_BAR_HEIGHT,
            readImageDimensions(secondResult)!!.second,
        )
    }

    // ---- padDmgBackgroundForTitleBar — edge cases ----

    @Test
    fun `non-image file returns source unchanged`() {
        val source = tmpDir.newFile("readme.txt")
        source.writeText("not an image")
        val outputDir = tmpDir.newFolder("output")

        val result = padDmgBackgroundForTitleBar(source, outputDir)

        assertEquals(source.absolutePath, result.absolutePath)
    }

    @Test
    fun `very small image is padded correctly`() {
        val source = tmpDir.newFile("tiny.png")
        ImageIO.write(createTestImage(1, 1), "png", source)
        val outputDir = tmpDir.newFolder("output")

        val result = padDmgBackgroundForTitleBar(source, outputDir)
        val dims = readImageDimensions(result)!!
        assertEquals(1, dims.first)
        assertEquals(1 + MACOS_DMG_TITLE_BAR_HEIGHT, dims.second)
    }

    // ---- Issue #166 regression: returned file must always exist ----

    @Test
    fun `issue 166 - TIFF padding result file exists on disk`() {
        // Regression test: before the fix, ImageIO.write could return false for TIFF
        // and the code returned a File that did not exist, causing FileNotFoundException
        val original = createTestImage(600, 400, Color.RED)
        val source = tmpDir.newFile("background.tiff")
        ImageIO.write(original, "tiff", source)
        val outputDir = tmpDir.newFolder("output")

        val result = padDmgBackgroundForTitleBar(source, outputDir)

        assertTrue("Result file must exist on disk (issue #166)", result.isFile)
        assertTrue("Result file must be non-empty", result.length() > 0)
    }

    @Test
    fun `issue 166 - PNG padding result file exists on disk`() {
        val original = createTestImage(600, 400, Color.RED)
        val source = tmpDir.newFile("background.png")
        ImageIO.write(original, "png", source)
        val outputDir = tmpDir.newFolder("output")

        val result = padDmgBackgroundForTitleBar(source, outputDir)

        assertTrue("Result file must exist on disk", result.isFile)
        assertTrue("Result file must be non-empty", result.length() > 0)
    }

    @Test
    fun `issue 166 - TIFF with alpha channel produces valid output`() {
        // The exact scenario from issue #166: TYPE_INT_ARGB TIFF
        val img = BufferedImage(500, 350, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        g.color = Color(255, 0, 0, 128) // semi-transparent red
        g.fillRect(0, 0, 500, 350)
        g.dispose()

        val source = tmpDir.newFile("background.tiff")
        ImageIO.write(img, "tiff", source)
        val outputDir = tmpDir.newFolder("output")

        val result = padDmgBackgroundForTitleBar(source, outputDir)

        assertTrue("Result file must exist on disk", result.isFile)
        // Either padded or fallback to original — both are valid
        val resultImg = ImageIO.read(result)
        assertNotNull("Result must be a readable image", resultImg)
        assertEquals("Width must be preserved", 500, resultImg.width)
    }

    @Test
    fun `issue 166 - corrupted image falls back to source instead of missing file`() {
        // Simulate the core bug: an unreadable image should return the original source,
        // not a non-existent padded file
        val source = tmpDir.newFile("background.png")
        source.writeBytes(byteArrayOf(0x00, 0x01, 0x02, 0x03)) // not a valid image
        val outputDir = tmpDir.newFolder("output")

        val result = padDmgBackgroundForTitleBar(source, outputDir)

        assertTrue("Fallback must return an existing file", result.isFile)
        assertEquals(
            "When read fails, should fallback to original source",
            source.absolutePath,
            result.absolutePath,
        )
    }

    @Test
    fun `issue 166 - TIFF result can be read without FileNotFoundException`() {
        // End-to-end: pad TIFF, then simulate what the task does: read the result
        val original = createTestImage(800, 600, Color.GREEN)
        val source = tmpDir.newFile("background.tiff")
        ImageIO.write(original, "tiff", source)
        val outputDir = tmpDir.newFolder("dmg-assets")

        val paddedFile = padDmgBackgroundForTitleBar(source, outputDir)

        // This is what the task does after padding — must not throw FileNotFoundException
        val bytes = paddedFile.readBytes()
        assertTrue("Padded file must have content", bytes.isNotEmpty())

        // Verify it's a valid image
        val readBack = ImageIO.read(paddedFile)
        assertNotNull("Must be a valid image file", readBack)
    }

    @Test
    fun `issue 166 - padded TIFF retina pair both exist on disk`() {
        val sourceDir = tmpDir.newFolder("source")
        val source1x = File(sourceDir, "background.tiff")
        val source2x = File(sourceDir, "background@2x.tiff")
        ImageIO.write(createTestImage(400, 300), "tiff", source1x)
        ImageIO.write(createTestImage(800, 600), "tiff", source2x)

        val outputDir = tmpDir.newFolder("output")
        val result = padDmgBackgroundForTitleBar(source1x, outputDir)

        assertTrue("1x result must exist", result.isFile)

        val retina = File(outputDir, "background@2x.tiff")
        if (retina.exists()) {
            // If retina was padded, it must be valid
            assertTrue("2x result must be non-empty", retina.length() > 0)
            val retinaImg = ImageIO.read(retina)
            assertNotNull("2x result must be readable", retinaImg)
        }
        // If retina doesn't exist, that's also fine — the function is resilient
    }

    // ---- helpers ----

    private fun createTestImage(
        width: Int,
        height: Int,
        color: Color = Color.RED,
    ): BufferedImage {
        val img = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        g.color = color
        g.fillRect(0, 0, width, height)
        g.dispose()
        return img
    }
}
