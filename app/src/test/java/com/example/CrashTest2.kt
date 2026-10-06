package com.example

import android.content.Intent
import android.net.Uri
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CrashTest2 {
    @Test
    fun testMainActivityViewIntent() {
        try {
            val intent = Intent(Intent.ACTION_VIEW)
            intent.data = Uri.parse("pdfstudio://tools/merge")
            val controller = Robolectric.buildActivity(MainActivity::class.java, intent)
            controller.create().start().resume()
            println("MainActivity with deep link started successfully!")
        } catch (e: Throwable) {
            e.printStackTrace()
            throw e
        }
    }
}
