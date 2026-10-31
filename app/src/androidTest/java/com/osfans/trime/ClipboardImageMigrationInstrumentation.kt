/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime

import android.app.Activity
import android.app.Instrumentation
import android.database.sqlite.SQLiteDatabase
import android.os.Bundle
import androidx.room.Room
import com.osfans.trime.data.db.ClipboardImagePayload
import com.osfans.trime.data.db.Database
import com.osfans.trime.data.db.DatabaseBean
import kotlinx.coroutines.runBlocking
import java.util.UUID

/** Creates and deletes only uniquely named test databases, never a user's clipboard history. */
class ClipboardImageMigrationInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val result = Bundle()
        var code = Activity.RESULT_CANCELED
        try {
            val context = targetContext
            for (oldVersion in listOf(3, 4, 5)) {
                val name = "image-migration-test-${UUID.randomUUID()}.db"
                val file = context.getDatabasePath(name)
                check(file.parentFile?.isDirectory == true || file.parentFile?.mkdirs() == true)
                try {
                    SQLiteDatabase.openOrCreateDatabase(file, null).use { sqlite ->
                        val pinnedColumn = if (oldVersion >= 4) ", pinned INTEGER NOT NULL" else ""
                        sqlite.execSQL("CREATE TABLE t_data (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, text TEXT, html TEXT, type INTEGER NOT NULL, time INTEGER NOT NULL$pinnedColumn)")
                        val pinnedValue = if (oldVersion >= 4) ", 1" else ""
                        sqlite.execSQL("INSERT INTO t_data VALUES (7, '旧文本', '<b>rich</b>', 1, 123456$pinnedValue)")
                        if (oldVersion == 5) {
                            sqlite.execSQL("CREATE TABLE clipboard_image_payload (id TEXT NOT NULL PRIMARY KEY, sha256 TEXT NOT NULL, mimeType TEXT NOT NULL, byteCount INTEGER NOT NULL, width INTEGER NOT NULL, height INTEGER NOT NULL, createdAt INTEGER NOT NULL, pinned INTEGER NOT NULL)")
                            sqlite.execSQL("CREATE UNIQUE INDEX index_clipboard_image_payload_sha256 ON clipboard_image_payload (sha256)")
                            sqlite.execSQL("INSERT INTO clipboard_image_payload VALUES ('legacy-id', 'legacy-hash', 'image/png', 10, 2, 3, 42, 1)")
                        }
                        sqlite.version = oldVersion
                    }
                    fun open() = Room.databaseBuilder(context, Database::class.java, name)
                        .addMigrations(Database.MIGRATION_3_4, Database.MIGRATION_4_5, Database.MIGRATION_5_6)
                        .build()
                    val payload = ClipboardImagePayload("opaque-id", "hash-1", "image/png", 10, 2, 3, 42, true)
                    val first = open()
                    try {
                        val db = first
                        db.openHelper.writableDatabase // Room validates the complete migrated schema.
                        runBlocking {
                            val row = checkNotNull(db.databaseDao().get(7))
                            check(row.text == "旧文本" && row.html == "<b>rich</b>")
                            check(row.type == DatabaseBean.BeanType.HTML && row.time == 123456L)
                            check(row.pinned == (oldVersion >= 4))
                            if (oldVersion == 5) {
                                check(db.clipboardImagePayloadDao().get("legacy-id")?.ready == true)
                            }
                            db.clipboardImagePayloadDao().insert(payload)
                            check(db.clipboardImagePayloadDao().findByHash("hash-1") == payload)
                            check(runCatching { db.clipboardImagePayloadDao().insert(payload.copy(id = "other")) }.isFailure)
                        }
                    } finally {
                        first.close()
                    }
                    val reopened = open()
                    try {
                        val db = reopened
                        runBlocking {
                            check(db.databaseDao().get(7)?.text == "旧文本")
                            check(db.clipboardImagePayloadDao().get("opaque-id") == payload)
                        }
                    } finally {
                        reopened.close()
                    }
                    sendStatus(0, Bundle().apply { putString("stream", "Room migration $oldVersion -> 6 passed\n") })
                } finally {
                    check(context.deleteDatabase(name)) { "Failed to remove the isolated test database" }
                }
            }
            code = Activity.RESULT_OK
            result.putString("stream", "Image metadata migration passed\n")
        } catch (failure: Throwable) {
            result.putString("stream", android.util.Log.getStackTraceString(failure))
        }
        finish(code, result)
    }
}
