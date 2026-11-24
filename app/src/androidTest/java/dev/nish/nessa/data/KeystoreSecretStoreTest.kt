package dev.nish.nessa.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class KeystoreSecretStoreTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun saveThenLoadReturnsEqualSecrets_andDiskHasNoPlainText() = runBlocking {
        // Uses the app's real store, so keep whatever the owner has saved and put it back.
        val store = KeystoreSecretStore(context)
        val before = store.load()
        try {
            val secrets = Secrets("test-cat@example.com", "plain-pw-marker-91", "refresh-marker-77")
            store.save(secrets)
            assertEquals(secrets, KeystoreSecretStore(context).load())

            val files = File(context.filesDir, "datastore").listFiles().orEmpty()
            assertTrue(files.isNotEmpty())
            val onDisk = files.joinToString("") { it.readBytes().toString(Charsets.ISO_8859_1) }
            assertFalse(onDisk.contains("plain-pw-marker-91"))
            assertFalse(onDisk.contains("refresh-marker-77"))
            assertFalse(onDisk.contains("test-cat@example.com"))

            store.clear()
            assertNull(store.load())
        } finally {
            if (before != null) store.save(before) else store.clear()
        }
    }
}
