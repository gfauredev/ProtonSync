package com.protosync.app.sync

import android.content.ContentProviderOperation
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.provider.ContactsContract
import com.protosync.app.util.SyncLog as Log
import dagger.hilt.android.qualifiers.ApplicationContext
import ezvcard.VCard
import com.protosync.app.util.SyncAccount
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ContactsSyncer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val syncAccount: SyncAccount
) {

    private val TAG = "ContactsSyncer"

    /**
     * Writes decrypted [contacts] into the Android contacts provider under the ProtonSync account.
     * Uses the Proton contact ID as SOURCE_ID to perform idempotent updates/inserts.
     * Existing contacts not present in the Proton list are deleted.
     */
    fun sync(contacts: List<Pair<String, VCard>>): Int {
        syncAccount.ensureExists(ACCOUNT_TYPE, ACCOUNT_NAME)
        val resolver = context.contentResolver
        
        val existingContacts = getExistingRawContacts(resolver)
        val processedProtonIds = mutableSetOf<String>()
        
        var written = 0
        val batch = ArrayList<ContentProviderOperation>()

        for ((protonId, vCard) in contacts) {
            processedProtonIds.add(protonId)
            val existingRawContactId = existingContacts[protonId]
            
            val rawContactOpIndex = batch.size

            if (existingRawContactId == null) {
                // INSERT: Create new RawContact
                batch.add(
                    ContentProviderOperation.newInsert(rawContactsUri())
                        .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, ACCOUNT_TYPE)
                        .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, ACCOUNT_NAME)
                        .withValue(ContactsContract.RawContacts.SOURCE_ID, protonId)
                        .build()
                )
            } else {
                // UPDATE: Delete existing data rows for this RawContact
                batch.add(
                    ContentProviderOperation.newDelete(ContactsContract.Data.CONTENT_URI)
                        .withSelection(
                            "${ContactsContract.Data.RAW_CONTACT_ID} = ?", 
                            arrayOf(existingRawContactId.toString())
                        )
                        .build()
                )
            }

            val displayName = vCard.formattedName?.value
                ?: fullNameFromStructuredName(vCard)
                ?: vCard.organizations.firstOrNull()?.values?.firstOrNull()
                ?: vCard.emails.firstOrNull()?.value
                ?: vCard.telephoneNumbers.firstOrNull()?.text
                ?: "Unknown"

            fun addDataInsert(mimeType: String, config: ContentProviderOperation.Builder.() -> Unit) {
                val builder = ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                if (existingRawContactId == null) {
                    builder.withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, rawContactOpIndex)
                } else {
                    builder.withValue(ContactsContract.Data.RAW_CONTACT_ID, existingRawContactId)
                }
                builder.withValue(ContactsContract.Data.MIMETYPE, mimeType)
                builder.config()
                batch.add(builder.build())
            }

            addDataInsert(ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE) {
                withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, displayName)
            }

            for (email in vCard.emails) {
                addDataInsert(ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE) {
                    withValue(ContactsContract.CommonDataKinds.Email.ADDRESS, email.value)
                    withValue(ContactsContract.CommonDataKinds.Email.TYPE, ContactsContract.CommonDataKinds.Email.TYPE_HOME)
                }
            }

            for (phone in vCard.telephoneNumbers) {
                addDataInsert(ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE) {
                    withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, phone.text)
                    withValue(ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE)
                }
            }

            for (org in vCard.organizations) {
                org.values.firstOrNull()?.let { company ->
                    addDataInsert(ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE) {
                        withValue(ContactsContract.CommonDataKinds.Organization.COMPANY, company)
                    }
                }
            }

            if (batch.size > 250) {
                try {
                    resolver.applyBatch(ContactsContract.AUTHORITY, batch)
                } catch (e: Exception) {
                    Log.e(TAG, "Batch failed", e)
                }
                batch.clear()
            }
            written++
        }

        // Handle deletions
        val idsToDelete = existingContacts.keys - processedProtonIds
        for (protonId in idsToDelete) {
            batch.add(
                ContentProviderOperation.newDelete(rawContactsUri())
                    .withSelection(
                        "${ContactsContract.RawContacts.SOURCE_ID} = ? AND ${ContactsContract.RawContacts.ACCOUNT_TYPE} = ?",
                        arrayOf(protonId, ACCOUNT_TYPE)
                    )
                    .build()
            )
        }

        if (batch.isNotEmpty()) {
            try {
                resolver.applyBatch(ContactsContract.AUTHORITY, batch)
            } catch (e: Exception) {
                Log.e(TAG, "Final batch failed", e)
            }
        }
        
        Log.i(TAG, "Contacts sync wrote $written/${contacts.size} contacts, deleted ${idsToDelete.size}")
        return written
    }

    private fun getExistingRawContacts(resolver: ContentResolver): Map<String, Long> {
        val map = mutableMapOf<String, Long>()
        val projection = arrayOf(
            ContactsContract.RawContacts._ID, 
            ContactsContract.RawContacts.SOURCE_ID
        )
        val selection = "${ContactsContract.RawContacts.ACCOUNT_TYPE} = ? AND ${ContactsContract.RawContacts.ACCOUNT_NAME} = ? AND ${ContactsContract.RawContacts.SOURCE_ID} IS NOT NULL"
        resolver.query(
            rawContactsUri(), projection, selection, arrayOf(ACCOUNT_TYPE, ACCOUNT_NAME), null
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(ContactsContract.RawContacts._ID)
            val sourceIdIndex = cursor.getColumnIndexOrThrow(ContactsContract.RawContacts.SOURCE_ID)
            while (cursor.moveToNext()) {
                map[cursor.getString(sourceIdIndex)] = cursor.getLong(idIndex)
            }
        }
        return map
    }

    private fun fullNameFromStructuredName(vCard: VCard): String? {
        val structuredName = vCard.structuredName ?: return null
        val parts = mutableListOf<String>()
        structuredName.prefixes.forEach { parts += it.trim() }
        structuredName.given?.let { parts += it.trim() }
        structuredName.additionalNames.forEach { parts += it.trim() }
        structuredName.family?.let { parts += it.trim() }
        structuredName.suffixes.forEach { parts += it.trim() }
        val fullName = parts.filter { it.isNotBlank() }.joinToString(" ")
        return fullName.takeIf { it.isNotEmpty() }
    }

    private fun rawContactsUri() = ContactsContract.RawContacts.CONTENT_URI.buildUpon()
        .appendQueryParameter(ContactsContract.RawContacts.ACCOUNT_TYPE, ACCOUNT_TYPE)
        .appendQueryParameter(ContactsContract.RawContacts.ACCOUNT_NAME, ACCOUNT_NAME)
        .build()

    companion object {
        const val ACCOUNT_TYPE = "com.protosync.app.account"
        const val ACCOUNT_NAME = "ProtonSync"
    }
}