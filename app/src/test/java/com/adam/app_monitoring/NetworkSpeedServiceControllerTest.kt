package com.adam.app_monitoring

import com.adam.app_monitoring.background.NetworkSpeedServiceController
import com.adam.app_monitoring.background.RestartAlarmMode
import org.junit.Assert.assertEquals
import org.junit.Test

class NetworkSpeedServiceControllerTest {
    @Test
    fun preAndroid12UsesExactAlarmWithoutSpecialAccess() {
        assertEquals(
            RestartAlarmMode.EXACT,
            NetworkSpeedServiceController.chooseAlarmMode(
                sdkInt = 30,
                canScheduleExact = false
            )
        )
    }

    @Test
    fun android12UsesExactAlarmWhenGranted() {
        assertEquals(
            RestartAlarmMode.EXACT,
            NetworkSpeedServiceController.chooseAlarmMode(
                sdkInt = 31,
                canScheduleExact = true
            )
        )
    }

    @Test
    fun android12FallsBackToInexactAlarmWhenDenied() {
        assertEquals(
            RestartAlarmMode.INEXACT,
            NetworkSpeedServiceController.chooseAlarmMode(
                sdkInt = 31,
                canScheduleExact = false
            )
        )
    }
}
