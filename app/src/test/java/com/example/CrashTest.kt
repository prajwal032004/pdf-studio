package com.example

import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CrashTest {
    @Test
    fun testMainActivityStarts() {
        try {
            val controller = Robolectric.buildActivity(MainActivity::class.java)
            controller.create().start().resume()
            println("MainActivity started successfully!")
        } catch (e: Throwable) {
            e.printStackTrace()
            throw e
        }
    }
}
