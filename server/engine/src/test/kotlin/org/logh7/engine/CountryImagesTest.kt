package org.logh7.engine

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import org.junit.jupiter.api.Test
import kotlin.test.*

class CountryImagesTest {
    @Test fun pngOriginalAndAlphaSurviveWhileAspectRatioIsPreservedInTheGameThumbnail() {
        val image=BufferedImage(64,32,BufferedImage.TYPE_INT_ARGB)
        for(y in 0..31)for(x in 0..63)image.setRGB(x,y,0x80ffffff.toInt())
        val png=ByteArrayOutputStream().also {ImageIO.write(image,"png",it)}.toByteArray()
        val decoded=CountryImageUpload.decode(png)
        assertContentEquals(png,decoded.png);assertEquals(64,decoded.width);assertEquals(32,decoded.height)
        assertEquals(4096,decoded.rgba.size);assertEquals(0,decoded.rgba[3].toInt())
        assertEquals(128,decoded.rgba[(16*32+16)*4+3].toInt() and 255)
        assertFails {CountryImageUpload.decode(byteArrayOf(1,2,3))}
        val tooWide=ByteArrayOutputStream().also {ImageIO.write(BufferedImage(2049,1,BufferedImage.TYPE_INT_ARGB),"png",it)}.toByteArray()
        assertTrue(assertFailsWith<IllegalArgumentException>{CountryImageUpload.decode(tooWide)}.message!!.contains("dimensions"))
        assertFalse(CountryImageStore.validText("Name","\u0000"));assertFalse(CountryImageStore.validKey("../image"))
    }
    @Test fun anOlderPollingSnapshotCannotReplaceANewerPublishedImage() {
        val published=PublishedCountryImages();val next=CountryImageSnapshot(2,mapOf(42 to CountryImageSelection(2)))
        published.publish(next);published.publish(CountryImageSnapshot(1,emptyMap()));assertSame(next,published.current())
    }
}
