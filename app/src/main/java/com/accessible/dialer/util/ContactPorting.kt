package com.accessible.dialer.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import org.json.JSONArray
import org.json.JSONObject

/**
 * Contact import / export utilities.
 *
 * Export formats:
 *  - vCard (.vcf) — universal, native Android support. We assemble the multi-card stream
 *    via [ContactsContract.Contacts.CONTENT_MULTI_VCARD_URI] which is the recommended
 *    way to bulk-export. Every other dialer / contacts app on every platform can import
 *    this file.
 *  - CSV (.csv) — Google Contacts compatible. Three columns: Name, Phones, Emails. Multiple
 *    values within a column are joined by ";".
 *  - JSON (.json) — pretty-printed list of objects, mostly for power users who want to
 *    pipe the data into another tool.
 *  - HTML (.html) — printable address book.
 *
 * Import is delegated to the system Contacts app via [Intent.ACTION_VIEW] with a
 * vCard MIME type — that gives us the system import wizard with no need to write our
 * own vCard parser (which would re-implement a non-trivial spec).
 */
object ContactPorting {

    enum class Format(val displayName: String, val mime: String, val extension: String) {
        VCARD("vCard (.vcf)", "text/vcard", "vcf"),
        CSV("CSV (.csv)", "text/csv", "csv"),
        JSON("JSON (.json)", "application/json", "json"),
        HTML("HTML (.html)", "text/html", "html"),
    }

    /**
     * Writes all of the device's contacts to [outUri] in [format]. Returns the number of
     * contacts written, or -1 if the operation failed.
     */
    fun export(context: Context, outUri: Uri, format: Format): Int = runCatching {
        when (format) {
            Format.VCARD -> exportVCard(context, outUri)
            Format.CSV -> exportTabular(context, outUri, ",")
            Format.JSON -> exportJson(context, outUri)
            Format.HTML -> exportHtml(context, outUri)
        }
    }.getOrDefault(-1)

    /** Builds an [Intent] that hands the chosen .vcf file to the system Contacts app. */
    fun importVCardIntent(uri: Uri): Intent =
        Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "text/x-vcard")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)

    // ---- vCard ------------------------------------------------------------

    private fun exportVCard(context: Context, outUri: Uri): Int {
        val resolver = context.contentResolver
        val keys = mutableListOf<String>()
        resolver.query(
            ContactsContract.Contacts.CONTENT_URI,
            arrayOf(ContactsContract.Contacts.LOOKUP_KEY),
            null, null, null,
        )?.use { c ->
            while (c.moveToNext()) {
                val k = c.getString(0) ?: continue
                if (k.isNotBlank()) keys.add(k)
            }
        }
        if (keys.isEmpty()) {
            // Nothing to write — still touch the file so the user sees an empty .vcf.
            resolver.openOutputStream(outUri, "w")?.close()
            return 0
        }
        val joined = Uri.encode(keys.distinct().joinToString(":"))
        val multiUri = Uri.withAppendedPath(
            ContactsContract.Contacts.CONTENT_MULTI_VCARD_URI,
            joined,
        )
        resolver.openOutputStream(outUri, "w")?.use { out ->
            resolver.openInputStream(multiUri)?.use { input -> input.copyTo(out) }
                ?: return -1
        } ?: return -1
        return keys.size
    }

    // ---- Tabular (CSV / TSV) ---------------------------------------------

    private fun exportTabular(context: Context, outUri: Uri, sep: String): Int {
        val rows = collect(context)
        context.contentResolver.openOutputStream(outUri, "w")?.bufferedWriter()?.use { w ->
            w.append("Name").append(sep).append("Phones").append(sep).append("Emails")
            w.append("\n")
            for (r in rows) {
                w.append(csvCell(r.name, sep)).append(sep)
                w.append(csvCell(r.phones.joinToString(";"), sep)).append(sep)
                w.append(csvCell(r.emails.joinToString(";"), sep))
                w.append("\n")
            }
        } ?: return -1
        return rows.size
    }

    private fun csvCell(s: String, sep: String): String {
        return if (s.contains(sep) || s.contains('"') || s.contains('\n') || s.contains('\r')) {
            "\"" + s.replace("\"", "\"\"") + "\""
        } else s
    }

    // ---- JSON -------------------------------------------------------------

    private fun exportJson(context: Context, outUri: Uri): Int {
        val rows = collect(context)
        val arr = JSONArray()
        for (r in rows) {
            arr.put(
                JSONObject()
                    .put("name", r.name)
                    .put("phones", JSONArray(r.phones))
                    .put("emails", JSONArray(r.emails))
            )
        }
        context.contentResolver.openOutputStream(outUri, "w")?.bufferedWriter()?.use { w ->
            w.write(arr.toString(2))
        } ?: return -1
        return rows.size
    }

    // ---- HTML -------------------------------------------------------------

    private fun exportHtml(context: Context, outUri: Uri): Int {
        val rows = collect(context)
        context.contentResolver.openOutputStream(outUri, "w")?.bufferedWriter()?.use { w ->
            w.append("<!doctype html><meta charset=\"utf-8\"><title>Contacts</title>")
            w.append("<style>body{font-family:sans-serif;max-width:48rem;margin:2rem auto;padding:0 1rem}")
            w.append("table{width:100%;border-collapse:collapse}")
            w.append("th,td{border-bottom:1px solid #ccc;padding:.5rem;text-align:left;vertical-align:top}")
            w.append("th{background:#eee}</style>")
            w.append("<h1>Contacts (${rows.size})</h1>")
            w.append("<table><tr><th>Name</th><th>Phones</th><th>Emails</th></tr>")
            for (r in rows) {
                w.append("<tr><td>").append(htmlEscape(r.name)).append("</td>")
                w.append("<td>").append(r.phones.joinToString("<br>") { htmlEscape(it) }).append("</td>")
                w.append("<td>").append(r.emails.joinToString("<br>") { htmlEscape(it) }).append("</td></tr>")
            }
            w.append("</table>")
        } ?: return -1
        return rows.size
    }

    private fun htmlEscape(s: String) = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    // ---- Shared collection of contact rows -------------------------------

    private data class Row(val name: String, val phones: List<String>, val emails: List<String>)

    private fun collect(context: Context): List<Row> {
        val resolver = context.contentResolver
        val rows = LinkedHashMap<Long, Row>()
        resolver.query(
            ContactsContract.Contacts.CONTENT_URI,
            arrayOf(
                ContactsContract.Contacts._ID,
                ContactsContract.Contacts.DISPLAY_NAME,
            ),
            null, null,
            ContactsContract.Contacts.DISPLAY_NAME + " COLLATE NOCASE ASC",
        )?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val name = c.getString(1).orEmpty()
                rows[id] = Row(name, emptyList(), emptyList())
            }
        }
        // Phones
        val phoneMap = HashMap<Long, MutableList<String>>()
        resolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                ContactsContract.CommonDataKinds.Phone.NUMBER,
            ),
            null, null, null,
        )?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val num = c.getString(1).orEmpty().trim()
                if (num.isNotEmpty()) phoneMap.getOrPut(id) { mutableListOf() }.add(num)
            }
        }
        // Emails
        val emailMap = HashMap<Long, MutableList<String>>()
        resolver.query(
            ContactsContract.CommonDataKinds.Email.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Email.CONTACT_ID,
                ContactsContract.CommonDataKinds.Email.ADDRESS,
            ),
            null, null, null,
        )?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val em = c.getString(1).orEmpty().trim()
                if (em.isNotEmpty()) emailMap.getOrPut(id) { mutableListOf() }.add(em)
            }
        }
        return rows.map { (id, r) ->
            r.copy(
                phones = phoneMap[id].orEmpty(),
                emails = emailMap[id].orEmpty(),
            )
        }
    }
}
