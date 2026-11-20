package com.kmusic.app

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** The app's only database. Plain SQLite, no extra libraries. */
class Db(ctx: Context) : SQLiteOpenHelper(ctx.applicationContext, "kmusic.db", null, 3) {

    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE songs(
                id TEXT PRIMARY KEY,
                title TEXT NOT NULL,
                artist TEXT NOT NULL,
                duration_ms INTEGER NOT NULL DEFAULT 0,
                file TEXT NOT NULL,
                size INTEGER NOT NULL DEFAULT 0,
                mime TEXT NOT NULL DEFAULT 'audio/mpeg',
                source TEXT NOT NULL DEFAULT 'device',
                source_url TEXT NOT NULL DEFAULT '',
                added_at INTEGER NOT NULL,
                play_count INTEGER NOT NULL DEFAULT 0,
                last_played INTEGER NOT NULL DEFAULT 0,
                liked INTEGER NOT NULL DEFAULT 0
            )"""
        )
        db.execSQL(
            """CREATE TABLE playlists(
                id TEXT PRIMARY KEY,
                name TEXT NOT NULL,
                created_at INTEGER NOT NULL
            )"""
        )
        db.execSQL(
            """CREATE TABLE playlist_songs(
                playlist_id TEXT NOT NULL REFERENCES playlists(id) ON DELETE CASCADE,
                song_id TEXT NOT NULL REFERENCES songs(id) ON DELETE CASCADE,
                position INTEGER NOT NULL,
                PRIMARY KEY(playlist_id, song_id)
            )"""
        )
        // Download queue (used by the download manager)
        db.execSQL(
            """CREATE TABLE downloads(
                id TEXT PRIMARY KEY,
                url TEXT NOT NULL,
                title TEXT NOT NULL DEFAULT '',
                status TEXT NOT NULL DEFAULT 'queued',
                progress INTEGER NOT NULL DEFAULT 0,
                error TEXT NOT NULL DEFAULT '',
                created_at INTEGER NOT NULL,
                cover_url TEXT NOT NULL DEFAULT '',
                hint_title TEXT NOT NULL DEFAULT '',
                hint_artist TEXT NOT NULL DEFAULT '',
                kind TEXT NOT NULL DEFAULT '',
                quality TEXT NOT NULL DEFAULT '',
                result TEXT NOT NULL DEFAULT ''
            )"""
        )
        db.execSQL("CREATE INDEX idx_songs_added ON songs(added_at DESC)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE downloads ADD COLUMN cover_url TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE downloads ADD COLUMN hint_title TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE downloads ADD COLUMN hint_artist TEXT NOT NULL DEFAULT ''")
        }
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE downloads ADD COLUMN kind TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE downloads ADD COLUMN quality TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE downloads ADD COLUMN result TEXT NOT NULL DEFAULT ''")
        }
    }
}
