package com.adam.app_monitoring

import com.adam.app_monitoring.background.XiaomiBackgroundSupport
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class XiaomiBackgroundSupportTest {
    @Test
    fun recognizesXiaomiFamilyFromManufacturerOrBrand() {
        assertTrue(XiaomiBackgroundSupport.isXiaomiDevice("Xiaomi", "generic"))
        assertTrue(XiaomiBackgroundSupport.isXiaomiDevice("generic", "Redmi"))
        assertTrue(XiaomiBackgroundSupport.isXiaomiDevice("generic", "POCO"))
    }

    @Test
    fun doesNotClassifyOtherManufacturersAsXiaomi() {
        assertFalse(XiaomiBackgroundSupport.isXiaomiDevice("Google", "Pixel"))
        assertFalse(XiaomiBackgroundSupport.isXiaomiDevice("Samsung", "Galaxy"))
    }
}
