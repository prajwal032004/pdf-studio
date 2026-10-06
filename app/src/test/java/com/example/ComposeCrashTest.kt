package com.example

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import com.example.navigation.AppNavigation
import com.example.ui.theme.AppTheme

@RunWith(RobolectricTestRunner::class)
class ComposeCrashTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun testAppNavigationRenders() {
        composeTestRule.setContent {
            AppTheme {
                AppNavigation(initialUri = null)
            }
        }
        composeTestRule.onNodeWithText("PDF Studio", substring = true, ignoreCase = true)
        println("Compose UI rendered successfully!")
    }
}
