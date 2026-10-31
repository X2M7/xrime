// SPDX-FileCopyrightText: 2015 - 2024 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [DatabaseBean::class, ClipboardImagePayload::class], version = 6)
@TypeConverters(DatabaseBean.Converters::class)
abstract class Database : RoomDatabase() {
    abstract fun databaseDao(): DatabaseDao

    abstract fun clipboardImagePayloadDao(): ClipboardImagePayloadDao

    companion object {
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE clipboard_image_payload ADD COLUMN ready INTEGER NOT NULL DEFAULT 1")
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS clipboard_image_payload (
                        id TEXT NOT NULL PRIMARY KEY,
                        sha256 TEXT NOT NULL,
                        mimeType TEXT NOT NULL,
                        byteCount INTEGER NOT NULL,
                        width INTEGER NOT NULL,
                        height INTEGER NOT NULL,
                        createdAt INTEGER NOT NULL,
                        pinned INTEGER NOT NULL
                    )""".trimIndent(),
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_clipboard_image_payload_sha256 ON clipboard_image_payload (sha256)")
            }
        }

        val MIGRATION_3_4 =
            object : Migration(3, 4) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    if (db.needUpgrade(4)) {
                        db.execSQL("ALTER TABLE ${DatabaseBean.TABLE_NAME} RENAME TO _t_data")
                        db.execSQL("ALTER TABLE _t_data ADD COLUMN pinned INTEGER NOT NULL DEFAULT 0")
                        db.execSQL(
                            """
                            CREATE TABLE IF NOT EXISTS ${DatabaseBean.TABLE_NAME} (
                                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                                text TEXT,
                                html TEXT,
                                type INTEGER NOT NULL,
                                time INTEGER NOT NULL,
                                pinned INTEGER NOT NULL
                            )
                            """.trimIndent(),
                        )
                        db.execSQL(
                            """
                            INSERT INTO ${DatabaseBean.TABLE_NAME} (id, text, html, type, time, pinned)
                            SELECT id, text, html, type, time, pinned FROM _t_data
                            """.trimIndent(),
                        )
                        db.execSQL("DROP TABLE _t_data")
                    }
                }
            }
    }
}
