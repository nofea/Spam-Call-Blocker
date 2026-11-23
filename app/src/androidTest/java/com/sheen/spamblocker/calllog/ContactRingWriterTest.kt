package com.sheen.spamblocker.calllog

import android.Manifest
import android.content.ContentProviderOperation
import android.content.ContentUris
import android.content.ContentValues
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.Contacts
import android.provider.ContactsContract.RawContacts
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sheen.spamblocker.rules.Action
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** SPEC §5: blocks on saved contacts are written to the contact, where Telecom reads them. */
@RunWith(AndroidJUnit4::class)
class ContactRingWriterTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val resolver = context.contentResolver
    private val writer = ContactRingWriter(context)
    private val number = "+919000012345"
    private var rawContactId = -1L

    @Before
    fun setUp() {
        listOf(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS).forEach {
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, it)
        }
        val ops = arrayListOf(
            ContentProviderOperation.newInsert(RawContacts.CONTENT_URI)
                .withValue(RawContacts.ACCOUNT_TYPE, null)
                .withValue(RawContacts.ACCOUNT_NAME, null)
                .build(),
            ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                .withValue(ContactsContract.Data.MIMETYPE, Phone.CONTENT_ITEM_TYPE)
                // E.164, so the lookup matches whatever country the emulator reports.
                .withValue(Phone.NUMBER, number)
                .build(),
        )
        rawContactId = ContentUris.parseId(resolver.applyBatch(ContactsContract.AUTHORITY, ops)[0].uri!!)
    }

    @After
    fun tearDown() {
        resolver.delete(
            ContentUris.withAppendedId(RawContacts.CONTENT_URI, rawContactId).buildUpon()
                .appendQueryParameter(ContactsContract.CALLER_IS_SYNCADAPTER, "true").build(),
            null, null,
        )
    }

    @Test
    fun silenceSetsRingtoneToNone() = runBlocking {
        assertTrue(writer.apply(number, Action.SILENCE))
        assertEquals("" to 0, state())
    }

    @Test
    fun rejectSendsToVoicemailAndUndoesSilence() = runBlocking {
        writer.apply(number, Action.SILENCE)
        assertTrue(writer.apply(number, Action.REJECT))
        assertEquals(null to 1, state())
    }

    @Test
    fun clearRemovesWhatApplyWrote() = runBlocking {
        writer.apply(number, Action.REJECT)
        writer.apply(number, Action.SILENCE)
        assertTrue(writer.clear(number))
        assertEquals(null to 0, state())
    }

    @Test
    fun clearKeepsARingtoneTheUserPicked() = runBlocking {
        val custom = "content://media/internal/audio/media/1"
        resolver.update(contactUri(), ContentValues().apply { put(Contacts.CUSTOM_RINGTONE, custom) }, null, null)
        writer.clear(number)
        assertEquals(custom, state().first)
    }

    @Test
    fun nonContactIsIgnored() = runBlocking {
        assertFalse(writer.apply("+919000099999", Action.SILENCE))
    }

    private fun contactUri() = resolver.query(
        RawContacts.CONTENT_URI, arrayOf(RawContacts.CONTACT_ID), "${RawContacts._ID} = ?", arrayOf(rawContactId.toString()), null,
    )!!.use { it.moveToFirst(); ContentUris.withAppendedId(Contacts.CONTENT_URI, it.getLong(0)) }

    /** The contact's (CUSTOM_RINGTONE, SEND_TO_VOICEMAIL). */
    private fun state(): Pair<String?, Int> =
        resolver.query(contactUri(), arrayOf(Contacts.CUSTOM_RINGTONE, Contacts.SEND_TO_VOICEMAIL), null, null, null)!!.use {
            it.moveToFirst()
            (if (it.isNull(0)) null else it.getString(0)) to it.getInt(1)
        }
}
