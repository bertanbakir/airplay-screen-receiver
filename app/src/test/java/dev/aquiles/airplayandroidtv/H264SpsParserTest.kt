package dev.aquiles.airplayandroidtv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class H264SpsParserTest {
    @Test
    fun parsesNonAlignedPortraitCropSeparatelyFromCodedSize() {
        val geometry = H264SpsParser.parse(
            buildBaselineSps(
                codedWidth = 512,
                codedHeight = 1088,
                cropRight = 14,
                cropBottom = 8
            )
        )
        assertNotNull(geometry)
        geometry!!

        assertEquals(512, geometry.codedWidth)
        assertEquals(1088, geometry.codedHeight)
        assertEquals(498, geometry.displayWidth)
        assertEquals(1080, geometry.displayHeight)
        assertEquals(14, geometry.cropRight)
        assertEquals(8, geometry.cropBottom)
        assertEquals(1, geometry.pixelAspectRatioWidth)
        assertEquals(1, geometry.pixelAspectRatioHeight)
    }

    @Test
    fun preservesAlignedLandscapeDisplayGeometry() {
        val geometry = H264SpsParser.parse(
            buildBaselineSps(
                codedWidth = 1920,
                codedHeight = 1088,
                cropRight = 0,
                cropBottom = 8
            )
        )
        assertNotNull(geometry)
        geometry!!

        assertEquals(1920, geometry.codedWidth)
        assertEquals(1088, geometry.codedHeight)
        assertEquals(1920, geometry.displayWidth)
        assertEquals(1080, geometry.displayHeight)
    }

    private fun buildBaselineSps(
        codedWidth: Int,
        codedHeight: Int,
        cropRight: Int,
        cropBottom: Int
    ): ByteArray {
        require(codedWidth % 16 == 0 && codedHeight % 16 == 0)
        require(cropRight % 2 == 0 && cropBottom % 2 == 0)

        val bits = BitWriter().apply {
            writeBits(66, 8) // profile_idc: Baseline
            writeBits(0, 8) // constraints
            writeBits(40, 8) // level_idc
            writeUe(0) // seq_parameter_set_id
            writeUe(0) // log2_max_frame_num_minus4
            writeUe(0) // pic_order_cnt_type
            writeUe(0) // log2_max_pic_order_cnt_lsb_minus4
            writeUe(1) // max_num_ref_frames
            writeBit(0) // gaps_in_frame_num_value_allowed_flag
            writeUe(codedWidth / 16 - 1)
            writeUe(codedHeight / 16 - 1)
            writeBit(1) // frame_mbs_only_flag
            writeBit(1) // direct_8x8_inference_flag
            writeBit(1) // frame_cropping_flag
            writeUe(0) // left; 4:2:0 crop units are 2x2
            writeUe(cropRight / 2)
            writeUe(0) // top
            writeUe(cropBottom / 2)
            writeBit(1) // vui_parameters_present_flag
            writeBit(1) // aspect_ratio_info_present_flag
            writeBits(1, 8) // 1:1 SAR
            writeBit(1) // rbsp_stop_one_bit
            byteAlignWithZeros()
        }

        val rbsp = bits.toByteArray()
        return byteArrayOf(0x67) + escapeRbsp(rbsp)
    }

    private fun escapeRbsp(rbsp: ByteArray): ByteArray {
        val output = ArrayList<Byte>(rbsp.size)
        var zeroCount = 0
        for (byte in rbsp) {
            val value = byte.toInt() and 0xff
            if (zeroCount >= 2 && value <= 0x03) {
                output += 0x03.toByte()
                zeroCount = 0
            }
            output += byte
            zeroCount = if (value == 0) zeroCount + 1 else 0
        }
        return output.toByteArray()
    }

    private class BitWriter {
        private val bits = ArrayList<Int>()

        fun writeBit(value: Int) {
            bits += value and 1
        }

        fun writeBits(value: Int, count: Int) {
            for (shift in count - 1 downTo 0) writeBit(value ushr shift)
        }

        fun writeUe(value: Int) {
            val codeNum = value + 1
            val bitCount = 32 - Integer.numberOfLeadingZeros(codeNum)
            repeat(bitCount - 1) { writeBit(0) }
            writeBits(codeNum, bitCount)
        }

        fun byteAlignWithZeros() {
            while (bits.size % 8 != 0) writeBit(0)
        }

        fun toByteArray(): ByteArray {
            val output = ByteArray(bits.size / 8)
            bits.forEachIndexed { index, bit ->
                output[index / 8] = (output[index / 8].toInt() or (bit shl (7 - index % 8))).toByte()
            }
            return output
        }
    }
}
