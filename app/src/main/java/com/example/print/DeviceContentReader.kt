package com.example.print

import android.content.Context
import android.provider.CalendarContract
import android.provider.ContactsContract
import com.example.rendering.CalendarEventEntry
import com.example.rendering.ContactEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object DeviceContentReader {

    suspend fun loadContacts(context: Context): List<ContactEntry> = withContext(Dispatchers.IO) {
        val map = LinkedHashMap<String, ContactEntry>()
        val cr = context.contentResolver

        // Query phone numbers
        cr.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            ),
            null,
            null,
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC"
        )?.use { cursor ->
            val idIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
            val nameIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val numIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            while (cursor.moveToNext()) {
                val id = cursor.getString(idIdx) ?: continue
                val name = cursor.getString(nameIdx) ?: "(Tanpa Nama)"
                val number = cursor.getString(numIdx)?.trim().orEmpty()
                val existing = map[id]
                if (existing == null) {
                    map[id] = ContactEntry(
                        id = id,
                        name = name,
                        phones = if (number.isNotEmpty()) listOf(number) else emptyList(),
                        emails = emptyList()
                    )
                } else if (number.isNotEmpty() && number !in existing.phones) {
                    map[id] = existing.copy(phones = existing.phones + number)
                }
            }
        }

        // Query emails
        cr.query(
            ContactsContract.CommonDataKinds.Email.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Email.CONTACT_ID,
                ContactsContract.CommonDataKinds.Email.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Email.ADDRESS
            ),
            null,
            null,
            null
        )?.use { cursor ->
            val idIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Email.CONTACT_ID)
            val nameIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Email.DISPLAY_NAME)
            val emailIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Email.ADDRESS)
            while (cursor.moveToNext()) {
                val id = cursor.getString(idIdx) ?: continue
                val name = cursor.getString(nameIdx) ?: "(Tanpa Nama)"
                val email = cursor.getString(emailIdx)?.trim().orEmpty()
                val existing = map[id]
                if (existing == null) {
                    map[id] = ContactEntry(
                        id = id,
                        name = name,
                        phones = emptyList(),
                        emails = if (email.isNotEmpty()) listOf(email) else emptyList()
                    )
                } else if (email.isNotEmpty() && email !in existing.emails) {
                    map[id] = existing.copy(emails = existing.emails + email)
                }
            }
        }

        map.values.sortedBy { it.name.lowercase() }
    }

    suspend fun loadCalendarEvents(
        context: Context,
        startMillis: Long,
        endMillis: Long
    ): List<CalendarEventEntry> = withContext(Dispatchers.IO) {
        val events = mutableListOf<CalendarEventEntry>()
        val cr = context.contentResolver
        val selection = "${CalendarContract.Events.DTSTART} >= ? AND ${CalendarContract.Events.DTSTART} <= ?"
        val args = arrayOf(startMillis.toString(), endMillis.toString())

        cr.query(
            CalendarContract.Events.CONTENT_URI,
            arrayOf(
                CalendarContract.Events._ID,
                CalendarContract.Events.TITLE,
                CalendarContract.Events.DTSTART,
                CalendarContract.Events.DTEND,
                CalendarContract.Events.EVENT_LOCATION,
                CalendarContract.Events.DESCRIPTION
            ),
            selection,
            args,
            "${CalendarContract.Events.DTSTART} ASC"
        )?.use { cursor ->
            val idIdx = cursor.getColumnIndex(CalendarContract.Events._ID)
            val titleIdx = cursor.getColumnIndex(CalendarContract.Events.TITLE)
            val startIdx = cursor.getColumnIndex(CalendarContract.Events.DTSTART)
            val endIdx = cursor.getColumnIndex(CalendarContract.Events.DTEND)
            val locIdx = cursor.getColumnIndex(CalendarContract.Events.EVENT_LOCATION)
            val descIdx = cursor.getColumnIndex(CalendarContract.Events.DESCRIPTION)

            while (cursor.moveToNext()) {
                val id = cursor.getString(idIdx) ?: continue
                val start = cursor.getLong(startIdx)
                val end = cursor.getLong(endIdx).takeIf { it > start } ?: (start + 3_600_000L)
                events.add(
                    CalendarEventEntry(
                        id = id,
                        title = cursor.getString(titleIdx).orEmpty(),
                        startTimeMillis = start,
                        endTimeMillis = end,
                        location = cursor.getString(locIdx).orEmpty(),
                        description = cursor.getString(descIdx).orEmpty()
                    )
                )
            }
        }
        events
    }
}
