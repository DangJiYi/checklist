package local.checklist.data

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import local.checklist.AppLog
import java.time.LocalDate
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors

// ---------------------------------------------------------------- 模型

data class TemplateItem(val id: Long, val name: String, val sortOrder: Int)

data class Template(
    val id: Long,
    val name: String,
    val createdAt: Long,
    val items: List<TemplateItem>
)

data class Item(val id: Long, val name: String, val checked: Boolean, val sortOrder: Int)

data class Instance(
    val id: Long,
    val templateId: Long?,
    val name: String,
    val localDate: String,
    val createdAt: Long,
    val completedAt: Long?,
    val status: Status,
    val completedCount: Int,
    val totalCount: Int,
    val locked: Boolean,
    val editedAt: Long?,
    val items: List<Item>
)

// ---------------------------------------------------------------- 导入模型

/** 导入用的模板。id 为 null 或已被占用时由数据库自增。 */
data class ImportTemplate(
    val id: Long?,
    val name: String,
    val createdAt: Long,
    val items: List<String>
)

data class ImportItem(val name: String, val checked: Boolean)

data class ImportInstance(
    val id: Long?,
    val templateId: Long?,
    val name: String,
    val localDate: String,
    val createdAt: Long,
    val completedAt: Long?,
    val status: Status,
    val items: List<ImportItem>,
    val editedAt: Long?
)

/** 导入结果，回给界面显示“恢复了多少”。 */
data class ImportResult(
    val templates: Int,
    val instances: Int,
    val items: Int,
    val skipped: Int
)

sealed class RepoResult<out T> {
    data class Ok<T>(val value: T) : RepoResult<T>()
    data class Err(val code: String, val message: String) : RepoResult<Nothing>()
}

/** 事务内的业务拒绝：抛出后事务回滚，再由 guarded 转成 RepoResult.Err。 */
// message 不写成 val：Throwable 已经有 message 成员，再用 val 声明会报
// "'message' hides member of supertype 'Throwable'"。构造参数直接传给父类即可。
private class Reject(val code: String, message: String) : RuntimeException(message)

// ---------------------------------------------------------------- 仓库

/**
 * 唯一的数据写入口。
 *
 * 所有写操作都在单线程 executor 上串行执行并包在事务里：
 * NanoHTTPD 每个请求一个线程，不做串行化就会撞上 SQLITE_BUSY 和丢更新。
 * 读也走同一个 executor——这个量级下最简单，也避免了读到一个半提交的状态。
 *
 * status / completed_count / locked 三列只由 persisted() 写，
 * 其他任何地方都不许直接 UPDATE 它们。
 */
class ChecklistRepository(private val helper: Db) {

    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "checklist-db") }
    private val settings = SettingsStore()

    companion object {
        private const val COLS =
            "id, template_id, name, local_date, created_at, completed_at, status, " +
                "completed_count, total_count, locked, edited_at"
        private val DATE_RE = Regex("^\\d{4}-\\d{2}-\\d{2}$")
    }

    // ------------------------------------------------------------ 线程 / 事务

    /**
     * 把工作丢到单线程 executor 上执行，并把 ExecutionException 的内层异常原样抛出。
     *
     * 这一步不能省：submit(...).get() 会把业务异常包进 ExecutionException，
     * 而 Reject("LOCKED") 一旦被包住，guarded() 只会看到 INTERNAL，
     * 接口就会把"已锁定"错报成 500——正是这类错误最难从界面上看出来。
     */
    private fun <T> onIo(block: () -> T): T = try {
        // 显式写上 Callable<T>，避开 submit 的 Runnable/Callable 重载推断歧义
        val call: Callable<T> = Callable { block() }
        io.submit(call).get()
    } catch (e: ExecutionException) {
        throw (e.cause ?: e)
    }

    private fun <T> read(block: (SQLiteDatabase) -> T): T =
        onIo { block(helper.readableDatabase) }

    private fun <T> write(block: (SQLiteDatabase) -> T): T = onIo {
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            val result = block(db)
            db.setTransactionSuccessful()
            result
        } finally {
            db.endTransaction()
        }
    }

    private fun <T> guarded(block: () -> T): RepoResult<T> = try {
        RepoResult.Ok(block())
    } catch (e: Reject) {
        RepoResult.Err(e.code, e.message ?: e.code)
    } catch (e: Exception) {
        AppLog.w("repo", AppLog.brief(e))
        RepoResult.Err("INTERNAL", e.message ?: e.javaClass.simpleName)
    }

    // ------------------------------------------------------------ 小工具

    private fun queryLong(db: SQLiteDatabase, sql: String, args: Array<String>?): Long? {
        db.rawQuery(sql, args).use { c ->
            return if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
        }
    }

    private fun queryString(db: SQLiteDatabase, sql: String, args: Array<String>?): String? {
        db.rawQuery(sql, args).use { c ->
            return if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null
        }
    }

    private fun countChecked(db: SQLiteDatabase, instanceId: Long): Int =
        queryLong(
            db,
            "SELECT COUNT(*) FROM instance_items WHERE instance_id = ? AND checked = 1",
            arrayOf(instanceId.toString())
        )?.toInt() ?: 0

    private fun countItems(db: SQLiteDatabase, instanceId: Long): Int =
        queryLong(
            db,
            "SELECT COUNT(*) FROM instance_items WHERE instance_id = ?",
            arrayOf(instanceId.toString())
        )?.toInt() ?: 0

    private fun allowEditCompleted(db: SQLiteDatabase): Boolean =
        settings.bool(db, SettingsStore.ALLOW_EDIT_COMPLETED, false)

    // ------------------------------------------------------------ 映射

    private fun readInstance(c: Cursor): Instance = Instance(
        id = c.getLong(0),
        templateId = if (c.isNull(1)) null else c.getLong(1),
        name = c.getString(2) ?: "",
        localDate = c.getString(3) ?: "",
        createdAt = c.getLong(4),
        completedAt = if (c.isNull(5)) null else c.getLong(5),
        status = Status.of(c.getString(6)),
        completedCount = c.getInt(7),
        totalCount = c.getInt(8),
        locked = c.getInt(9) != 0,
        editedAt = if (c.isNull(10)) null else c.getLong(10),
        items = emptyList()
    )

    private fun itemsOf(db: SQLiteDatabase, instanceId: Long): List<Item> {
        val out = ArrayList<Item>()
        db.rawQuery(
            "SELECT id, name, checked, sort_order FROM instance_items " +
                "WHERE instance_id = ? ORDER BY sort_order, id",
            arrayOf(instanceId.toString())
        ).use { c ->
            while (c.moveToNext()) {
                out.add(Item(c.getLong(0), c.getString(1) ?: "", c.getInt(2) != 0, c.getInt(3)))
            }
        }
        return out
    }

    private fun templateItemsOf(db: SQLiteDatabase, templateId: Long): List<TemplateItem> {
        val out = ArrayList<TemplateItem>()
        db.rawQuery(
            "SELECT id, name, sort_order FROM template_items WHERE template_id = ? ORDER BY sort_order, id",
            arrayOf(templateId.toString())
        ).use { c ->
            while (c.moveToNext()) {
                out.add(TemplateItem(c.getLong(0), c.getString(1) ?: "", c.getInt(2)))
            }
        }
        return out
    }

    /**
     * locked 在读取时重新推导，而不是信数据库里那一列。
     * 列里的值只是给导出/排障看的缓存，开关一变就会过时。
     */
    private fun loadInstance(db: SQLiteDatabase, id: Long, withItems: Boolean = true): Instance? {
        val head = db.rawQuery(
            "SELECT $COLS FROM instances WHERE id = ?",
            arrayOf(id.toString())
        ).use { c -> if (c.moveToFirst()) readInstance(c) else null } ?: return null

        val locked = StateMachine.isLocked(head.status, allowEditCompleted(db))
        return if (withItems) head.copy(locked = locked, items = itemsOf(db, id))
        else head.copy(locked = locked, items = emptyList())
    }

    private fun selectInstances(
        db: SQLiteDatabase,
        where: String,
        args: Array<String>?,
        order: String,
        withItems: Boolean
    ): List<Instance> {
        val heads = ArrayList<Instance>()
        db.rawQuery("SELECT $COLS FROM instances WHERE $where ORDER BY $order", args).use { c ->
            while (c.moveToNext()) heads.add(readInstance(c))
        }
        val allow = allowEditCompleted(db)
        return heads.map { head ->
            head.copy(
                locked = StateMachine.isLocked(head.status, allow),
                items = if (withItems) itemsOf(db, head.id) else emptyList()
            )
        }
    }

    /** 三列的唯一写入口。checkedCount 必须是变更之后重新数出来的值。 */
    private fun persisted(db: SQLiteDatabase, instanceId: Long, checkedCount: Int, o: Outcome) {
        db.execSQL(
            "UPDATE instances SET status = ?, completed_count = ?, completed_at = ?, " +
                "locked = ?, edited_at = ? WHERE id = ?",
            arrayOf<Any?>(
                o.status.id,
                checkedCount,
                o.completedAt,
                if (o.locked) 1 else 0,
                o.editedAt,
                instanceId
            )
        )
    }

    // ------------------------------------------------------------ 模板

    fun listTemplates(): List<Template> = read { db ->
        val heads = ArrayList<Template>()
        db.rawQuery(
            "SELECT id, name, created_at FROM templates WHERE archived = 0 ORDER BY id",
            null
        ).use { c ->
            while (c.moveToNext()) {
                heads.add(Template(c.getLong(0), c.getString(1) ?: "", c.getLong(2), emptyList()))
            }
        }
        heads.map { t -> t.copy(items = templateItemsOf(db, t.id)) }
    }

    fun createTemplate(name: String, items: List<String>): RepoResult<Template> = guarded {
        val clean = normalizeItems(items)
        val n = name.trim()
        if (n.isEmpty()) throw Reject("BAD_REQUEST", "模板名称不能为空")
        if (clean.isEmpty()) throw Reject("EMPTY", "模板至少需要一个检查项")

        write { db ->
            val now = System.currentTimeMillis()
            val id = db.insertOrThrow(
                "templates",
                null,
                ContentValues().apply {
                    put("name", n)
                    put("created_at", now)
                    put("archived", 0)
                }
            )
            insertTemplateItems(db, id, clean)
            Template(id, n, now, templateItemsOf(db, id))
        }
    }

    fun updateTemplate(id: Long, name: String, items: List<String>): RepoResult<Template> = guarded {
        val clean = normalizeItems(items)
        val n = name.trim()
        if (n.isEmpty()) throw Reject("BAD_REQUEST", "模板名称不能为空")
        if (clean.isEmpty()) throw Reject("EMPTY", "模板至少需要一个检查项")

        write { db ->
            val createdAt = queryLong(
                db,
                "SELECT created_at FROM templates WHERE id = ? AND archived = 0",
                arrayOf(id.toString())
            ) ?: throw Reject("NOT_FOUND", "模板不存在")

            db.update(
                "templates",
                ContentValues().apply { put("name", n) },
                "id = ?",
                arrayOf(id.toString())
            )
            db.delete("template_items", "template_id = ?", arrayOf(id.toString()))
            insertTemplateItems(db, id, clean)
            // 已存在的实例是快照，改模板不影响它们——这正是 item 表被复制而非引用的原因。
            Template(id, n, createdAt, templateItemsOf(db, id))
        }
    }

    fun deleteTemplate(id: Long): RepoResult<Boolean> = guarded {
        write { db ->
            db.update(
                "templates",
                ContentValues().apply { put("archived", 1) },
                "id = ?",
                arrayOf(id.toString())
            ) > 0
        }
    }

    private fun normalizeItems(items: List<String>): List<String> =
        items.map { it.trim() }.filter { it.isNotEmpty() }

    private fun insertTemplateItems(db: SQLiteDatabase, templateId: Long, names: List<String>) {
        names.forEachIndexed { index, itemName ->
            db.insertOrThrow(
                "template_items",
                null,
                ContentValues().apply {
                    put("template_id", templateId)
                    put("name", itemName)
                    put("sort_order", index)
                }
            )
        }
    }

    // ------------------------------------------------------------ 实例

    fun createInstance(
        templateId: Long?,
        name: String?,
        localDate: String?,
        clientToken: String?
    ): RepoResult<Instance> = guarded {
        write { db ->
            // 幂等：同一 clientToken 重复提交，返回既有那张表，不新建。
            if (clientToken != null) {
                val existing = queryLong(
                    db,
                    "SELECT id FROM instances WHERE client_token = ?",
                    arrayOf(clientToken)
                )
                if (existing != null) {
                    return@write loadInstance(db, existing)
                        ?: throw Reject("INTERNAL", "幂等命中但读不到实例")
                }
            }

            val date = localDate?.takeIf { DATE_RE.matches(it) } ?: LocalDate.now().toString()
            val displayName: String
            val itemNames: List<String>

            if (templateId == null) {
                displayName = name?.trim()?.takeIf { it.isNotEmpty() } ?: "空白检查表"
                itemNames = emptyList()
            } else {
                val tplName = queryString(
                    db,
                    "SELECT name FROM templates WHERE id = ?",
                    arrayOf(templateId.toString())
                ) ?: throw Reject("NOT_FOUND", "模板不存在")
                displayName = name?.trim()?.takeIf { it.isNotEmpty() } ?: tplName
                // 快照复制：之后改模板不影响这张表
                itemNames = templateItemsOf(db, templateId).map { it.name }
            }

            val now = System.currentTimeMillis()
            val id = db.insertOrThrow(
                "instances",
                null,
                ContentValues().apply {
                    put("template_id", templateId)
                    put("name", displayName)
                    put("local_date", date)
                    put("created_at", now)
                    put("status", Status.ACTIVE.id)
                    put("completed_count", 0)
                    put("total_count", itemNames.size)
                    put("locked", 0)
                    if (clientToken != null) put("client_token", clientToken)
                }
            )
            itemNames.forEachIndexed { index, itemName ->
                db.insertOrThrow(
                    "instance_items",
                    null,
                    ContentValues().apply {
                        put("instance_id", id)
                        put("name", itemName)
                        put("checked", 0)
                        put("sort_order", index)
                    }
                )
            }
            loadInstance(db, id) ?: throw Reject("INTERNAL", "创建后读取失败")
        }
    }

    fun getInstance(id: Long): Instance? = read { db -> loadInstance(db, id) }

    fun listActive(): List<Instance> = read { db ->
        selectInstances(db, "status = 'active'", null, "created_at DESC", withItems = true)
    }

    fun listByDate(date: String): List<Instance> = read { db ->
        selectInstances(db, "local_date = ?", arrayOf(date), "created_at", withItems = false)
    }

    fun listByMonth(month: String): List<Instance> = read { db ->
        selectInstances(
            db,
            "substr(local_date, 1, 7) = ?",
            arrayOf(month),
            "local_date, created_at",
            withItems = false
        )
    }

    fun listAll(): List<Instance> = read { db ->
        selectInstances(db, "1 = 1", null, "created_at", withItems = true)
    }

    fun toggleItem(itemId: Long): RepoResult<Instance> = guarded {
        write { db ->
            val instanceId = queryLong(
                db,
                "SELECT instance_id FROM instance_items WHERE id = ?",
                arrayOf(itemId.toString())
            ) ?: throw Reject("NOT_FOUND", "检查项不存在")

            val head = loadInstance(db, instanceId, withItems = false)
                ?: throw Reject("NOT_FOUND", "检查表不存在")
            val allow = allowEditCompleted(db)
            if (head.status.isCompleted && !allow) {
                throw Reject("LOCKED", "该检查表已完成并锁定；如需修改，请先在设置里打开“允许修改已完成表”")
            }

            val now = System.currentTimeMillis()
            val wasChecked = queryLong(
                db,
                "SELECT checked FROM instance_items WHERE id = ?",
                arrayOf(itemId.toString())
            )?.toInt() ?: 0
            val cv = ContentValues().apply {
                put("checked", if (wasChecked != 0) 0 else 1)
            }
            if (wasChecked != 0) cv.putNull("checked_at") else cv.put("checked_at", now)
            db.update("instance_items", cv, "id = ?", arrayOf(itemId.toString()))

            val checked = countChecked(db, instanceId)
            val outcome = when (
                val r = StateMachine.apply(
                    Action.TOGGLE,
                    head.status,
                    checked,
                    head.totalCount,
                    head.completedAt,
                    now,
                    allow
                )
            ) {
                is StateResult.Ok -> r.outcome
                is StateResult.Rejected -> throw Reject(r.code, r.message)
            }
            persisted(db, instanceId, checked, outcome)
            loadInstance(db, instanceId) ?: throw Reject("INTERNAL", "更新后读取失败")
        }
    }

    fun manualComplete(instanceId: Long): RepoResult<Instance> = guarded {
        write { db ->
            val head = loadInstance(db, instanceId, withItems = false)
                ?: throw Reject("NOT_FOUND", "检查表不存在")
            val allow = allowEditCompleted(db)
            val checked = countChecked(db, instanceId)
            val now = System.currentTimeMillis()

            val outcome = when (
                val r = StateMachine.apply(
                    Action.MANUAL_COMPLETE,
                    head.status,
                    checked,
                    head.totalCount,
                    head.completedAt,
                    now,
                    allow
                )
            ) {
                is StateResult.Ok -> r.outcome
                is StateResult.Rejected -> throw Reject(r.code, r.message)
            }
            persisted(db, instanceId, checked, outcome)
            loadInstance(db, instanceId) ?: throw Reject("INTERNAL", "完成后读取失败")
        }
    }

    fun addItem(instanceId: Long, name: String): RepoResult<Instance> = guarded {
        val n = name.trim()
        if (n.isEmpty()) throw Reject("BAD_REQUEST", "检查项名称不能为空")

        write { db ->
            val head = loadInstance(db, instanceId, withItems = false)
                ?: throw Reject("NOT_FOUND", "检查表不存在")
            if (head.status.isCompleted) {
                throw Reject("LOCKED", "已完成的检查表不能添加检查项")
            }
            val order = queryLong(
                db,
                "SELECT COALESCE(MAX(sort_order), -1) + 1 FROM instance_items WHERE instance_id = ?",
                arrayOf(instanceId.toString())
            )?.toInt() ?: 0

            db.insertOrThrow(
                "instance_items",
                null,
                ContentValues().apply {
                    put("instance_id", instanceId)
                    put("name", n)
                    put("checked", 0)
                    put("sort_order", order)
                }
            )
            val total = countItems(db, instanceId)
            db.execSQL(
                "UPDATE instances SET total_count = ? WHERE id = ?",
                arrayOf<Any?>(total, instanceId)
            )
            loadInstance(db, instanceId) ?: throw Reject("INTERNAL", "添加后读取失败")
        }
    }

    fun deleteItem(itemId: Long): RepoResult<Instance> = guarded {
        write { db ->
            val instanceId = queryLong(
                db,
                "SELECT instance_id FROM instance_items WHERE id = ?",
                arrayOf(itemId.toString())
            ) ?: throw Reject("NOT_FOUND", "检查项不存在")

            val head = loadInstance(db, instanceId, withItems = false)
                ?: throw Reject("NOT_FOUND", "检查表不存在")
            if (head.status.isCompleted) {
                throw Reject("LOCKED", "已完成的检查表不能删除检查项")
            }

            db.delete("instance_items", "id = ?", arrayOf(itemId.toString()))
            val total = countItems(db, instanceId)
            val checked = countChecked(db, instanceId)
            db.execSQL(
                "UPDATE instances SET total_count = ?, completed_count = ? WHERE id = ?",
                arrayOf<Any?>(total, checked, instanceId)
            )
            loadInstance(db, instanceId) ?: throw Reject("INTERNAL", "删除后读取失败")
        }
    }

    fun deleteInstance(id: Long): RepoResult<Boolean> = guarded {
        write { db ->
            // 只允许整表删除；instance_items 由外键级联清理
            db.delete("instances", "id = ?", arrayOf(id.toString())) > 0
        }
    }

    // ------------------------------------------------------------ 设置

    fun readSettings(): Map<String, String> = read { db -> settings.all(db) }

    fun updateSettings(patch: Map<String, String>): RepoResult<Map<String, String>> = guarded {
        val unknown = patch.keys.filter { it !in SettingsStore.WRITABLE }
        if (unknown.isNotEmpty()) {
            throw Reject("BAD_REQUEST", "未知设置项：${unknown.joinToString(",")}")
        }
        write { db ->
            patch.forEach { (key, value) ->
                val v = if (key == SettingsStore.ALLOW_EDIT_COMPLETED) {
                    if (value == "1" || value.equals("true", ignoreCase = true)) "1" else "0"
                } else {
                    value.trim()
                }
                settings.put(db, key, v)
            }
            if (patch.containsKey(SettingsStore.ALLOW_EDIT_COMPLETED)) refreshLocks(db)
            settings.all(db)
        }
    }

    /** 开关一变，缓存列必须整体重算，否则导出的 locked 会和实际行为不一致。 */
    private fun refreshLocks(db: SQLiteDatabase) {
        if (allowEditCompleted(db)) {
            db.execSQL("UPDATE instances SET locked = 0")
        } else {
            db.execSQL("UPDATE instances SET locked = CASE WHEN status <> 'active' THEN 1 ELSE 0 END")
        }
    }

    // ------------------------------------------------------------ 导入

    /**
     * 整包导入（恢复备份）。
     *
     * 语义是**替换**，不是合并。合并看起来更诱人，但会造成两个真问题：
     * 同一条实例在两边都被改过时无法判定谁对；以及必须重写全部 id
     * 并重新维护 template_id 引用关系。恢复备份的正确含义本来就是
     * “把数据变回备份里的样子”，所以这里做全量替换，界面上先告知清楚。
     *
     * 全部动作在同一个事务里：中途任何一步失败，旧数据原样保留。
     * （半导入的数据比什么都没导入更难收拾——用户分不清哪些是新的哪些是旧的。）
     *
     * 时间戳、完成状态、勾选状态全部按备份原样恢复，不重新计算，
     * 否则历史上的“部分完成 3/5”会被改成今天的 0/5。
     * 只有 locked 不采用备份值，而是按当前开关重算。
     */
    fun importAll(
        templates: List<ImportTemplate>,
        instances: List<ImportInstance>,
        settingValues: Map<String, String>
    ): RepoResult<ImportResult> = guarded {
        write { db ->
            // 外键开着：子表必须先删，否则级联不会触发
            db.delete("instance_items", null, null)
            db.delete("instances", null, null)
            db.delete("template_items", null, null)
            db.delete("templates", null, null)

            // 先写设置再插数据。
            // 原因是 locked 要按 allowEditCompleted 算：如果备份里这个开关
            // 和当前值不同，先插后写就会拿旧值算出一批错的 locked。
            settingValues.forEach { (k, v) ->
                if (k in SettingsStore.WRITABLE) settings.put(db, k, v)
            }

            var skipped = 0
            var itemCount = 0
            var templateCount = 0
            var instanceCount = 0
            val keepTemplateIds = claimable(templates.mapNotNull { it.id })
            val keepInstanceIds = claimable(instances.mapNotNull { it.id })
            val allowEdit = allowEditCompleted(db)

            for (t in templates) {
                val n = t.name.trim()
                val names = t.items.map { it.trim() }.filter { it.isNotEmpty() }
                if (n.isEmpty() || names.isEmpty()) {
                    skipped++
                    continue
                }
                val wantId = t.id
                val newId = if (wantId != null && wantId > 0 && keepTemplateIds.remove(wantId)) {
                    db.insertOrThrow(
                        "templates", null,
                        ContentValues().apply {
                            put("id", wantId)
                            put("name", n)
                            put("created_at", t.createdAt)
                            put("archived", 0)
                        }
                    )
                    wantId
                } else {
                    db.insertOrThrow(
                        "templates", null,
                        ContentValues().apply {
                            put("name", n)
                            put("created_at", t.createdAt)
                            put("archived", 0)
                        }
                    )
                }
                insertTemplateItems(db, newId, names)
                templateCount++
            }

            for (inst in instances) {
                val n = inst.name.trim()
                if (n.isEmpty()) {
                    skipped++
                    continue
                }
                val items = inst.items.filter { it.name.isNotBlank() }
                val total = items.size
                val checked = items.count { it.checked }
                // 备份里的 status 可能与 counts 不一致（手工改过导出文件），按 counts 归一。
                // 但“已完成”这件事本身要保留：曾手动完成过的表不应因导入而变回进行中。
                val status = when {
                    !inst.status.isCompleted -> Status.ACTIVE
                    total > 0 && checked >= total -> Status.COMPLETED_FULL
                    else -> Status.COMPLETED_PARTIAL
                }

                val wantId = inst.id
                val newId = if (wantId != null && wantId > 0 && keepInstanceIds.remove(wantId)) {
                    db.insertOrThrow(
                        "instances", null,
                        ContentValues().apply {
                            put("id", wantId)
                            applyInstanceRow(this, inst, n, total, checked, status, allowEdit)
                        }
                    )
                    wantId
                } else {
                    db.insertOrThrow(
                        "instances", null,
                        ContentValues().apply {
                            applyInstanceRow(this, inst, n, total, checked, status, allowEdit)
                        }
                    )
                }
                instanceCount++

                items.forEachIndexed { index, item ->
                    db.insertOrThrow(
                        "instance_items", null,
                        ContentValues().apply {
                            put("instance_id", newId)
                            put("name", item.name.trim())
                            put("checked", if (item.checked) 1 else 0)
                            put("sort_order", index)
                            // 勾选时间未知就留空，不要拿“现在”冒充历史
                            if (item.checked && inst.completedAt != null) put("checked_at", inst.completedAt)
                        }
                    )
                    itemCount++
                }
            }

            ImportResult(
                templates = templateCount,
                instances = instanceCount,
                items = itemCount,
                skipped = skipped
            )
        }
    }

    /**
     * 备份里哪些 id 可以原样复用。
     *
     * 返回的是**可变集合**，调用方每用掉一个就 remove 一个：
     * 手改过的备份很容易写出重复 id，只用不可变集合判断“在不在”会让第二行也认为可用，
     * 结果直撞主键。去掉之后第二行自然回落到自增。
     *
     * 模板与实例是不同表，id 空间独立，所以要分别处理（各自调一次）。
     */
    private fun claimable(ids: List<Long>): MutableSet<Long> {
        val set = HashSet<Long>()
        for (id in ids) if (id > 0) set.add(id)
        return set
    }

    private fun applyInstanceRow(
        cv: ContentValues,
        inst: ImportInstance,
        name: String,
        total: Int,
        checked: Int,
        status: Status,
        allowEditCompleted: Boolean
    ) {
        cv.put("template_id", inst.templateId)
        cv.put("name", name)
        cv.put("local_date", if (DATE_RE.matches(inst.localDate)) inst.localDate else LocalDate.now().toString())
        cv.put("created_at", inst.createdAt)
        cv.put("completed_at", inst.completedAt)
        cv.put("status", status.id)
        cv.put("completed_count", checked)
        cv.put("total_count", total)
        cv.put("locked", if (StateMachine.isLocked(status, allowEditCompleted)) 1 else 0)
        cv.put("edited_at", inst.editedAt)
    }

    // ------------------------------------------------------------ 诊断

    fun schemaVersion(): Int = read { db -> db.version }

    fun dbSizeBytes(): Long = read { db ->
        db.rawQuery("PRAGMA page_count", null).use { c ->
            val pages = if (c.moveToFirst()) c.getLong(0) else 0L
            db.rawQuery("PRAGMA page_size", null).use { p ->
                val size = if (p.moveToFirst()) p.getLong(0) else 0L
                pages * size
            }
        }
    }
}
