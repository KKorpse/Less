package com.kkorpse.less.data

import org.junit.Assert.assertEquals
import org.junit.Test

class LivePhotoDetectorTest {

    @Test
    fun oppoExtension_usesVideoLength() {
        val head = """
            <rdf:Description GCamera:MotionPhoto="1" OpCamera:MotionPhotoOwner="oplus"
                OpCamera:VideoLength="123456" />
        """.trimIndent()
        assertEquals(123456L, LivePhotoDetector.parseMotionPhotoLength(head))
    }

    @Test
    fun googleLegacy_usesMicroVideoOffset() {
        val head = """
            <rdf:Description GCamera:MotionPhoto="1" GCamera:MicroVideoOffset="98765" />
        """.trimIndent()
        assertEquals(98765L, LivePhotoDetector.parseMotionPhotoLength(head))
    }

    @Test
    fun googleStandard_usesLargestItemLength() {
        val head = """
            <rdf:Description GCamera:MotionPhoto="1">
              <Container:Directory>
                <rdf:Seq>
                  <rdf:li><Container:Item Item:Mime="image/jpeg" Item:Semantic="Primary" Item:Length="0" /></rdf:li>
                  <rdf:li><Container:Item Item:Mime="video/mp4" Item:Semantic="MotionPhoto" Item:Length="456789" /></rdf:li>
                </rdf:Seq>
              </Container:Directory>
            </rdf:Description>
        """.trimIndent()
        assertEquals(456789L, LivePhotoDetector.parseMotionPhotoLength(head))
    }

    @Test
    fun normalJpeg_returnsZero() {
        val head = "<rdf:Description tiff:Make=\"OPPO\" />"
        assertEquals(0L, LivePhotoDetector.parseMotionPhotoLength(head))
    }

    @Test
    fun motionPhotoFlagZero_returnsZero() {
        val head = """
            <rdf:Description Camera:MotionPhoto="0" GCamera:MicroVideoOffset="12345" />
        """.trimIndent()
        assertEquals(0L, LivePhotoDetector.parseMotionPhotoLength(head))
    }
}
