package com.gia.aicomposer.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OnboardingFlagTest {

    @Test
    fun onboardingIsUnseenByDefaultThenPersists() {
        val context = RuntimeEnvironment.getApplication()
        val settings = AppSettings(context)
        assertFalse(settings.onboardingSeen)
        settings.onboardingSeen = true
        assertTrue(AppSettings(context).onboardingSeen)
    }
}
