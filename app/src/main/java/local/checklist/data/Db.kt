package local.checklist.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * 表结构与迁移。
 *
 * 迁移用 PRAGMA user_version 递增，不用 CREATE TABLE IF NOT EXISTS 冒充迁移——
 * 后者在加列时不会生效，而失败是静默的。
 */
class Db(context: Context) : SQLiteOpenHelper(context.applicationContext, NAME, null, VERSION) {

    companion object {
        const val NAME = "checklist.db"
        const val VERSION = 1
    }

    init {
        // WAL：读不阻塞写，跨设备同时刷新才不会互相卡住
        setWriteAheadLoggingEnabled(true)
    }

    override fun onConfigure(db: SQLiteDatabase) {
        // 外键必须每连接单独开，放在 onCreate 里是错的
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        for (sql in SCHEMA) db.execSQL(sql)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // v2 起在这里按版本递增补 ALTER TABLE。首个版本无操作。
    }

    private val SCHEMA: List<String> = listOf(
        """
        CREATE TABLE templates(
            id          INTEGER PRIMARY KEY AUTOINCREMENT,
            name        TEXT    NOT NULL,
            created_at  INTEGER NOT NULL,
            archived    INTEGER NOT NULL DEFAULT 0
        )
        """,
        """
        CREATE TABLE template_items(
            id          INTEGER PRIMARY KEY AUTOINCREMENT,
            template_id INTEGER NOT NULL REFERENCES templates(id) ON DELETE CASCADE,
            name        TEXT    NOT NULL,
            sort_order  INTEGER NOT NULL
        )
        """,
        """
        CREATE TABLE instances(
            id              INTEGER PRIMARY KEY AUTOINCREMENT,
            template_id     INTEGER REFERENCES templates(id) ON DELETE SET NULL,
            name            TEXT    NOT NULL,
            local_date      TEXT    NOT NULL,
            created_at      INTEGER NOT NULL,
            completed_at    INTEGER,
            status          TEXT    NOT NULL,
            completed_count INTEGER NOT NULL DEFAULT 0,
            total_count     INTEGER NOT NULL,
            locked          INTEGER NOT NULL DEFAULT 0,
            edited_at       INTEGER,
            client_token    TEXT
        )
        """,
        """
        CREATE TABLE instance_items(
            id          INTEGER PRIMARY KEY AUTOINCREMENT,
            instance_id INTEGER NOT NULL REFERENCES instances(id) ON DELETE CASCADE,
            name        TEXT    NOT NULL,
            checked     INTEGER NOT NULL DEFAULT 0,
            sort_order  INTEGER NOT NULL,
            checked_at  INTEGER
        )
        """,
        """
        CREATE TABLE settings(
            key   TEXT PRIMARY KEY,
            value TEXT NOT NULL
        )
        """,
        "CREATE UNIQUE INDEX idx_inst_token ON instances(client_token)",
        "CREATE INDEX idx_inst_date ON instances(local_date)",
        "CREATE INDEX idx_inst_status ON instances(status)",
        "CREATE INDEX idx_items_inst ON instance_items(instance_id, sort_order)"
    )
}
