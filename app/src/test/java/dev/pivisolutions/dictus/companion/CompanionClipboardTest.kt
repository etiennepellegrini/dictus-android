package dev.pivisolutions.dictus.companion

import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CompanionClipboardTest {
    @Test fun completedTextIsAvailableAsPlainText() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val text = "Deuxième dictée\navec accents."
        context.copyCompanionText(text)
        val clip = requireNotNull(context.getSystemService(ClipboardManager::class.java).primaryClip)
        assertTrue(clip.description.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN))
        assertEquals(1, clip.itemCount)
        assertEquals(text, clip.getItemAt(0).text.toString())
    }
}
