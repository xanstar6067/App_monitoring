package com.adam.app_monitoring

import com.adam.app_monitoring.background.NetworkSpeedNotificationDesign
import com.adam.app_monitoring.background.NetworkSpeedNotificationLayout
import org.junit.Assert.assertEquals
import org.junit.Test

class NetworkSpeedNotificationDesignTest {
    @Test
    fun galaxyS23UltraKeepsLegacyLayout() {
        assertEquals(
            NetworkSpeedNotificationLayout.LEGACY_ONE_UI_6,
            NetworkSpeedNotificationDesign.chooseLayout(
                manufacturer = "Samsung",
                brand = "samsung",
                model = "SM-S918B",
                sdkInt = 36
            )
        )
    }

    @Test
    fun samsungAndroid14KeepsOneUi6Layout() {
        assertEquals(
            NetworkSpeedNotificationLayout.LEGACY_ONE_UI_6,
            NetworkSpeedNotificationDesign.chooseLayout(
                manufacturer = "samsung",
                brand = "generic",
                model = "SM-S921B",
                sdkInt = 34
            )
        )
    }

    @Test
    fun galaxyS25UltraOnAndroid16UsesCompactDualBadgeLayout() {
        assertEquals(
            NetworkSpeedNotificationLayout.COMPACT_DUAL_BADGE,
            NetworkSpeedNotificationDesign.chooseLayout(
                manufacturer = "samsung",
                brand = "samsung",
                model = "SM-S938B",
                sdkInt = 36
            )
        )
    }

    @Test
    fun nonSamsungAndroid14UsesDefaultCompactLayout() {
        assertEquals(
            NetworkSpeedNotificationLayout.COMPACT_DUAL_BADGE,
            NetworkSpeedNotificationDesign.chooseLayout(
                manufacturer = "Google",
                brand = "google",
                model = "Pixel 8 Pro",
                sdkInt = 34
            )
        )
    }
}
