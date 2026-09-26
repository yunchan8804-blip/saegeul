/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.graph

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class EntityInfo(
    val id: String,
    val label: String,
    val category: String,
    val weight: Float = 1.0f,
    val lastSeenEpoch: Long = System.currentTimeMillis()
)

data class EdgeInfo(
    val src: String,
    val dst: String,
    val relation: String,
    val weight: Float = 1.0f,
    val frequency: Int = 1,
    val lastUpdatedEpoch: Long = System.currentTimeMillis()
)

class OnDeviceEgoGraphDatabase(
    context: Context,
    dbName: String = DATABASE_NAME
) : SQLiteOpenHelper(context, dbName, null, DATABASE_VERSION) {

    init {
        setWriteAheadLoggingEnabled(true)
    }

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        setWriteAheadLoggingEnabled(true)
        db.enableWriteAheadLogging()
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS entities (
                id TEXT PRIMARY KEY,
                label TEXT NOT NULL,
                category TEXT NOT NULL,
                weight REAL NOT NULL DEFAULT 1.0,
                last_seen_epoch INTEGER NOT NULL
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS edges (
                src TEXT NOT NULL,
                dst TEXT NOT NULL,
                relation TEXT NOT NULL,
                weight REAL NOT NULL DEFAULT 1.0,
                frequency INTEGER NOT NULL DEFAULT 1,
                last_updated_epoch INTEGER NOT NULL,
                PRIMARY KEY (src, dst, relation)
            )
            """.trimIndent()
        )

        db.execSQL("CREATE INDEX IF NOT EXISTS idx_edges_src ON edges(src);")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_edges_dst ON edges(dst);")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS edges")
        db.execSQL("DROP TABLE IF EXISTS entities")
        onCreate(db)
    }

    @Synchronized
    fun upsertEntity(id: String, label: String, category: String, weight: Float = 1.0f) {
        val now = System.currentTimeMillis()
        val values = ContentValues().apply {
            put("id", id)
            put("label", label)
            put("category", category)
            put("weight", weight)
            put("last_seen_epoch", now)
        }
        writableDatabase.insertWithOnConflict("entities", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    @Synchronized
    fun upsertEdge(src: String, dst: String, relation: String, weight: Float = 1.0f) {
        val now = System.currentTimeMillis()
        val db = writableDatabase
        var existingFrequency = 0
        val cursor = db.rawQuery(
            "SELECT frequency FROM edges WHERE src = ? AND dst = ? AND relation = ?",
            arrayOf(src, dst, relation)
        )
        cursor.use {
            if (it.moveToFirst()) {
                existingFrequency = it.getInt(0)
            }
        }

        val values = ContentValues().apply {
            put("src", src)
            put("dst", dst)
            put("relation", relation)
            put("weight", weight)
            put("frequency", existingFrequency + 1)
            put("last_updated_epoch", now)
        }
        db.insertWithOnConflict("edges", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    @Synchronized
    fun get1HopNeighbors(entityId: String): List<EdgeInfo> {
        val db = readableDatabase
        val results = mutableListOf<EdgeInfo>()
        val cursor = db.rawQuery(
            "SELECT src, dst, relation, weight, frequency, last_updated_epoch FROM edges WHERE src = ? OR dst = ?",
            arrayOf(entityId, entityId)
        )
        cursor.use {
            while (it.moveToNext()) {
                results.add(
                    EdgeInfo(
                        src = it.getString(0),
                        dst = it.getString(1),
                        relation = it.getString(2),
                        weight = it.getFloat(3),
                        frequency = it.getInt(4),
                        lastUpdatedEpoch = it.getLong(5)
                    )
                )
            }
        }
        return results
    }

    @Synchronized
    fun getAllEntities(): List<EntityInfo> {
        val db = readableDatabase
        val results = mutableListOf<EntityInfo>()
        val cursor = db.rawQuery(
            "SELECT id, label, category, weight, last_seen_epoch FROM entities",
            null
        )
        cursor.use {
            while (it.moveToNext()) {
                results.add(
                    EntityInfo(
                        id = it.getString(0),
                        label = it.getString(1),
                        category = it.getString(2),
                        weight = it.getFloat(3),
                        lastSeenEpoch = it.getLong(4)
                    )
                )
            }
        }
        return results
    }

    @Synchronized
    fun getAllEdges(): List<EdgeInfo> {
        val db = readableDatabase
        val results = mutableListOf<EdgeInfo>()
        val cursor = db.rawQuery(
            "SELECT src, dst, relation, weight, frequency, last_updated_epoch FROM edges",
            null
        )
        cursor.use {
            while (it.moveToNext()) {
                results.add(
                    EdgeInfo(
                        src = it.getString(0),
                        dst = it.getString(1),
                        relation = it.getString(2),
                        weight = it.getFloat(3),
                        frequency = it.getInt(4),
                        lastUpdatedEpoch = it.getLong(5)
                    )
                )
            }
        }
        return results
    }

    @Synchronized
    fun clear() {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.execSQL("DELETE FROM edges")
            db.execSQL("DELETE FROM entities")
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    companion object {
        const val DATABASE_NAME = "saegeul_ego_graph.db"
        const val DATABASE_VERSION = 1
    }
}
