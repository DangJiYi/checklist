package local.checklist.data

import android.database.sqlite.SQLiteDatabase

/**
 * settings 表的存取。刻意做成纯函数式（方法都接 db 参数），
 * 不持有 executor，避免和 Repository 抢锁或形成两套写线程。
 */
class SettingsStore {

    companion object {
        const val ALLOW_EDIT_COMPLETED = "allow_edit_completed"
        const val DEVICE_NAME = "device_name"

        /** 允许通过 PUT /api/settings 修改的键。白名单，不是黑名单。 */
        val WRITABLE: Set<String> = setOf(ALLOW_EDIT_COMPLETED, DEVICE_NAME)

        val DEFAULTS: Map<String, String> = mapOf(
            ALLOW_EDIT_COMPLETED to "0",
            DEVICE_NAME to "android"
        )
    }

    fun all(db: SQLiteDatabase): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        out.putAll(DEFAULTS)
        db.rawQuery("SELECT key, value FROM settings", null).use { c ->
            while (c.moveToNext()) {
                out[c.getString(0)] = c.getString(1) ?: ""
            }
        }
        return out
    }

    fun get(db: SQLiteDatabase, key: String): String? {
        db.rawQuery("SELECT value FROM settings WHERE key = ?", arrayOf(key)).use { c ->
            return if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null
        }
    }

    fun bool(db: SQLiteDatabase, key: String, def: Boolean): Boolean {
        val v = get(db, key) ?: return def
        return v == "1" || v.equals("true", ignoreCase = true)
    }

    fun put(db: SQLiteDatabase, key: String, value: String) {
        db.execSQL(
            "INSERT OR REPLACE INTO settings(key, value) VALUES(?, ?)",
            arrayOf<Any?>(key, value)
        )
    }
}
