package dev.aquiles.airplayandroidtv

internal data class H264Geometry(
    val codedWidth: Int,
    val codedHeight: Int,
    val displayWidth: Int,
    val displayHeight: Int,
    val cropLeft: Int,
    val cropRight: Int,
    val cropTop: Int,
    val cropBottom: Int,
    val pixelAspectRatioWidth: Int,
    val pixelAspectRatioHeight: Int
)

internal object H264SpsParser {
    fun parse(spsNal: ByteArray): H264Geometry? {
        if (spsNal.size < 4 || (spsNal[0].toInt() and 0x1f) != 7) return null

        return try {
            val reader = BitReader(unescapeRbsp(spsNal, startOffset = 1))
            val profileIdc = reader.readBits(8)
            reader.readBits(8) // constraint flags and reserved bits
            reader.readBits(8) // level_idc
            reader.readUe() // seq_parameter_set_id

            var chromaFormatIdc = 1
            var separateColorPlaneFlag = 0
            if (profileIdc in HIGH_PROFILES) {
                chromaFormatIdc = reader.readUe()
                if (chromaFormatIdc == 3) separateColorPlaneFlag = reader.readBit()
                reader.readUe() // bit_depth_luma_minus8
                reader.readUe() // bit_depth_chroma_minus8
                reader.readBit() // qpprime_y_zero_transform_bypass_flag
                if (reader.readBit() == 1) {
                    val count = if (chromaFormatIdc != 3) 8 else 12
                    repeat(count) { index ->
                        if (reader.readBit() == 1) {
                            reader.skipScalingList(if (index < 6) 16 else 64)
                        }
                    }
                }
            }

            reader.readUe() // log2_max_frame_num_minus4
            when (reader.readUe()) { // pic_order_cnt_type
                0 -> reader.readUe()
                1 -> {
                    reader.readBit()
                    reader.readSe()
                    reader.readSe()
                    repeat(reader.readUe()) { reader.readSe() }
                }
            }

            reader.readUe() // max_num_ref_frames
            reader.readBit() // gaps_in_frame_num_value_allowed_flag
            val picWidthInMbsMinus1 = reader.readUe()
            val picHeightInMapUnitsMinus1 = reader.readUe()
            val frameMbsOnlyFlag = reader.readBit()
            if (frameMbsOnlyFlag == 0) reader.readBit() // mb_adaptive_frame_field_flag
            reader.readBit() // direct_8x8_inference_flag

            var cropLeftOffset = 0
            var cropRightOffset = 0
            var cropTopOffset = 0
            var cropBottomOffset = 0
            if (reader.readBit() == 1) {
                cropLeftOffset = reader.readUe()
                cropRightOffset = reader.readUe()
                cropTopOffset = reader.readUe()
                cropBottomOffset = reader.readUe()
            }

            var sarWidth = 1
            var sarHeight = 1
            if (reader.hasBits(1) && reader.readBit() == 1 && reader.readBit() == 1) {
                val aspectRatioIdc = reader.readBits(8)
                val sar = if (aspectRatioIdc == EXTENDED_SAR) {
                    reader.readBits(16) to reader.readBits(16)
                } else {
                    ASPECT_RATIOS[aspectRatioIdc] ?: (1 to 1)
                }
                if (sar.first > 0 && sar.second > 0) {
                    sarWidth = sar.first
                    sarHeight = sar.second
                }
            }

            val codedWidth = (picWidthInMbsMinus1 + 1) * 16
            val codedHeight = (picHeightInMapUnitsMinus1 + 1) * 16 * (2 - frameMbsOnlyFlag)
            val chromaArrayType = if (separateColorPlaneFlag == 0) chromaFormatIdc else 0
            val subWidthC = when (chromaArrayType) {
                1, 2 -> 2
                else -> 1
            }
            val subHeightC = if (chromaArrayType == 1) 2 else 1
            val cropUnitX = if (chromaArrayType == 0) 1 else subWidthC
            val cropUnitY = if (chromaArrayType == 0) {
                2 - frameMbsOnlyFlag
            } else {
                subHeightC * (2 - frameMbsOnlyFlag)
            }

            val cropLeft = cropLeftOffset * cropUnitX
            val cropRight = cropRightOffset * cropUnitX
            val cropTop = cropTopOffset * cropUnitY
            val cropBottom = cropBottomOffset * cropUnitY
            val displayWidth = codedWidth - cropLeft - cropRight
            val displayHeight = codedHeight - cropTop - cropBottom

            if (codedWidth <= 0 || codedHeight <= 0 || displayWidth <= 0 || displayHeight <= 0) {
                null
            } else {
                H264Geometry(
                    codedWidth = codedWidth,
                    codedHeight = codedHeight,
                    displayWidth = displayWidth,
                    displayHeight = displayHeight,
                    cropLeft = cropLeft,
                    cropRight = cropRight,
                    cropTop = cropTop,
                    cropBottom = cropBottom,
                    pixelAspectRatioWidth = sarWidth,
                    pixelAspectRatioHeight = sarHeight
                )
            }
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun unescapeRbsp(data: ByteArray, startOffset: Int): ByteArray {
        val result = ByteArray(data.size - startOffset)
        var output = 0
        var zeroCount = 0
        for (index in startOffset until data.size) {
            val value = data[index].toInt() and 0xff
            if (zeroCount >= 2 && value == 0x03) {
                zeroCount = 0
                continue
            }
            result[output++] = data[index]
            zeroCount = if (value == 0) zeroCount + 1 else 0
        }
        return result.copyOf(output)
    }

    private class BitReader(private val data: ByteArray) {
        private var bitOffset = 0

        fun hasBits(count: Int): Boolean = bitOffset + count <= data.size * 8

        fun readBit(): Int {
            require(hasBits(1)) { "Unexpected end of SPS" }
            val byteIndex = bitOffset / 8
            val shift = 7 - (bitOffset % 8)
            bitOffset++
            return (data[byteIndex].toInt() ushr shift) and 1
        }

        fun readBits(count: Int): Int {
            require(count in 0..31) { "Invalid bit count: $count" }
            var result = 0
            repeat(count) { result = (result shl 1) or readBit() }
            return result
        }

        fun readUe(): Int {
            var leadingZeros = 0
            while (readBit() == 0) {
                leadingZeros++
                require(leadingZeros < 31) { "Invalid Exp-Golomb value" }
            }
            return if (leadingZeros == 0) 0 else {
                (1 shl leadingZeros) - 1 + readBits(leadingZeros)
            }
        }

        fun readSe(): Int {
            val codeNum = readUe()
            return if (codeNum == 0) 0 else if (codeNum and 1 == 1) {
                (codeNum + 1) / 2
            } else {
                -(codeNum / 2)
            }
        }

        fun skipScalingList(size: Int) {
            var lastScale = 8
            var nextScale = 8
            repeat(size) {
                if (nextScale != 0) {
                    nextScale = (lastScale + readSe() + 256) % 256
                }
                lastScale = if (nextScale == 0) lastScale else nextScale
            }
        }
    }

    private const val EXTENDED_SAR = 255
    private val HIGH_PROFILES = setOf(44, 83, 86, 100, 110, 118, 122, 128, 134, 135, 138, 139, 244)
    private val ASPECT_RATIOS = mapOf(
        1 to (1 to 1),
        2 to (12 to 11),
        3 to (10 to 11),
        4 to (16 to 11),
        5 to (40 to 33),
        6 to (24 to 11),
        7 to (20 to 11),
        8 to (32 to 11),
        9 to (80 to 33),
        10 to (18 to 11),
        11 to (15 to 11),
        12 to (64 to 33),
        13 to (160 to 99),
        14 to (4 to 3),
        15 to (3 to 2),
        16 to (2 to 1)
    )
}
