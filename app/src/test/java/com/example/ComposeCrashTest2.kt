package com.example

import android.net.Uri
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import com.example.tools.viewer.PdfViewerScreen
import com.example.ui.theme.AppTheme

@RunWith(RobolectricTestRunner::class)
class ComposeCrashTest2 {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun testPdfViewerScreenRenders() {
        composeTestRule.setContent {
            AppTheme {
                PdfViewerScreen(uri = Uri.parse("content://dummy"), onBack = {})
            }
        }
        println("PdfViewerScreen rendered successfully!")
    }
}
