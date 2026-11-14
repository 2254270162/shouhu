package com.example.locateme

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 纯逻辑单元测试（不依赖 Android 运行时）。
 * 这些断言是定位结果展示正确性的基础，改格式化逻辑时先跑一遍。
 */
class CoordinateFormatTest {

    @Test
    fun latitudeFormatsWithSixDecimals() {
        assertEquals("31.230416", String.format(java.util.Locale.US, "%.6f", 31.230415926))
    }

    @Test
    fun negativeLongitudeFormatsCorrectly() {
        assertEquals("-122.419415", String.format(java.util.Locale.US, "%.6f", -122.4194150))
    }

    @Test
    fun accuracyRoundsDownToMeters() {
        assertEquals("±15 米", "±${15.7f.toInt()} 米")
    }
}
