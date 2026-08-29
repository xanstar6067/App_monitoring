package com.adam.app_monitoring.background

import java.util.Locale

internal enum class NetworkSpeedNotificationLayout {
    LEGACY_ONE_UI_6,
    COMPACT_DUAL_BADGE
}

internal object NetworkSpeedNotificationDesign {
    /**
     * One UI does not expose its version through a public Android API. Galaxy devices on
     * Android 14 are therefore used as the One UI 6.x compatibility group, while the exact
     * S23 Ultra model family keeps the legacy layout after an OS update as well.
     */
    fun chooseLayout(
        manufacturer: String,
        brand: String,
        model: String,
        sdkInt: Int
    ): NetworkSpeedNotificationLayout {
        val isSamsung = manufacturer.equals("samsung", ignoreCase = true) ||
            brand.equals("samsung", ignoreCase = true)
        val normalizedModel = model.trim().uppercase(Locale.US)
        val isGalaxyS23Ultra = isSamsung && normalizedModel.startsWith(S23_ULTRA_MODEL_PREFIX)
        val isSamsungAndroid14 = isSamsung && sdkInt == ANDROID_14_SDK

        return if (isGalaxyS23Ultra || isSamsungAndroid14) {
            NetworkSpeedNotificationLayout.LEGACY_ONE_UI_6
        } else {
            NetworkSpeedNotificationLayout.COMPACT_DUAL_BADGE
        }
    }

    private const val S23_ULTRA_MODEL_PREFIX = "SM-S918"
    private const val ANDROID_14_SDK = 34
}
