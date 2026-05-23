package com.relavoi.sdk.offline

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.relavoi.sdk.Relavoi
import com.relavoi.sdk.internal.Logger

/**
 * SQLite-backed bounded queue for actions that couldn't be sent (network drop, server
 * 5xx, etc). Host apps drain the queue manually on a reconnect.
 *
 * NOT wired into [com.relavoi.sdk.internal.ApiClient] yet — apps explicitly enqueue
 * after catching [com.relavoi.sdk.RelavoiException.Network].
 *
 * TODO(future): wire as ApiClient fallback on Network exceptions.
 */
class OfflineQueue(context: Context) {

    private val helper = OpenHelper(context.applicationContext)

    /** Enqueue an action. If the queue exceeds the configured cap, oldest is dropped. */
    fun enqueue(action: String, payload: String) {
        val db = helper.writableDatabase
        val values = ContentValues().apply {
            put(COL_ACTION, action)
            put(COL_PAYLOAD, payload)
            put(COL_QUEUED_AT, System.currentTimeMillis())
        }
        db.insert(TABLE, null, values)
        trimIfNeeded(db)
    }

    /** Pull everything out in queued_at order. Caller is responsible for calling [delete] on success. */
    fun dequeueAll(): List<QueuedAction> {
        val db = helper.readableDatabase
        val out = mutableListOf<QueuedAction>()
        db.query(TABLE, null, null, null, null, null, "$COL_QUEUED_AT ASC").use { c ->
            val idIdx = c.getColumnIndexOrThrow(COL_ID)
            val actIdx = c.getColumnIndexOrThrow(COL_ACTION)
            val payIdx = c.getColumnIndexOrThrow(COL_PAYLOAD)
            val tsIdx = c.getColumnIndexOrThrow(COL_QUEUED_AT)
            while (c.moveToNext()) {
                out += QueuedAction(
                    id = c.getLong(idIdx),
                    action = c.getString(actIdx),
                    payload = c.getString(payIdx),
                    queuedAt = c.getLong(tsIdx),
                )
            }
        }
        return out
    }

    /** Remove a single entry by row id (returned in [QueuedAction.id]). */
    fun delete(id: Long) {
        helper.writableDatabase.delete(TABLE, "$COL_ID = ?", arrayOf(id.toString()))
    }

    /** Current row count. */
    fun size(): Int {
        helper.readableDatabase.rawQuery("SELECT COUNT(*) FROM $TABLE", null).use { c ->
            return if (c.moveToFirst()) c.getInt(0) else 0
        }
    }

    private fun trimIfNeeded(db: SQLiteDatabase) {
        val cap = runCatching { Relavoi.config.offlineQueueMaxSize }.getOrDefault(100)
        val excess = size() - cap
        if (excess <= 0) return
        Logger.w("OfflineQueue exceeded cap ($cap) — dropping $excess oldest entries")
        // Delete the oldest `excess` rows.
        val ids = mutableListOf<Long>()
        db.query(TABLE, arrayOf(COL_ID), null, null, null, null, "$COL_QUEUED_AT ASC", excess.toString()).use { c ->
            while (c.moveToNext()) ids += c.getLong(0)
        }
        ids.forEach { delete(it) }
    }

    private class OpenHelper(context: Context) :
        SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE $TABLE (
                    $COL_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                    $COL_ACTION TEXT NOT NULL,
                    $COL_PAYLOAD TEXT NOT NULL,
                    $COL_QUEUED_AT INTEGER NOT NULL
                )
                """.trimIndent()
            )
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            db.execSQL("DROP TABLE IF EXISTS $TABLE")
            onCreate(db)
        }
    }

    /** A single queued entry returned by [dequeueAll]. */
    data class QueuedAction(
        val id: Long,
        val action: String,
        val payload: String,
        val queuedAt: Long,
    )

    companion object {
        private const val DB_NAME = "relavoi_offline.db"
        private const val DB_VERSION = 1
        private const val TABLE = "queued_actions"
        private const val COL_ID = "id"
        private const val COL_ACTION = "action"
        private const val COL_PAYLOAD = "payload"
        private const val COL_QUEUED_AT = "queued_at"
    }
}
