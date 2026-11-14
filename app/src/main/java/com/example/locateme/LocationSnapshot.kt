package com.example.locateme

import android.location.Location

/** 一次成功定位的完整结果，供界面展示与分享。 */
data class LocationSnapshot(
    val latitude: Double,
    val longitude: Double,
    /** 水平精度，单位米；越大越模糊。 */
    val accuracyMeters: Float,
    val altitudeMeters: Double?,
    val speedMetersPerSecond: Float?,
    val bearingDegrees: Float?,
    /** 定位结果的来源，如 gps / network / passive。 */
    val provider: String,
    val timestamp: Long,
    /** 只有大致位置权限时为 true，坐标已被系统模糊化。 */
    val isApproximate: Boolean,
    val address: AddressInfo?,
) {
    companion object {
        fun from(location: Location, isApproximate: Boolean, address: AddressInfo? = null) = LocationSnapshot(
            latitude = location.latitude,
            longitude = location.longitude,
            accuracyMeters = location.accuracy,
            altitudeMeters = if (location.hasAltitude()) location.altitude else null,
            speedMetersPerSecond = if (location.hasSpeed()) location.speed else null,
            bearingDegrees = if (location.hasBearing()) location.bearing else null,
            provider = location.provider ?: "unknown",
            timestamp = location.time,
            isApproximate = isApproximate,
            address = address,
        )
    }
}
