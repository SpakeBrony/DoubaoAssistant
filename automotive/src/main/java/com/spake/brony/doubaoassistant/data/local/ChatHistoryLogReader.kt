package com.spake.brony.doubaoassistant.data.local

import android.content.Context
import android.database.DatabaseUtils
import android.database.sqlite.SQLiteDatabase
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal object ChatHistoryLogReader {
    val tables = listOf("messages", "topics")
    private const val PAGE_SIZE = 50

    data class Item(val id: Long, val text: String)
    data class Page(val items: List<Item>, val total: Long, val hasMore: Boolean)

    suspend fun read(context: Context, table: String, afterId: Long): Page = withContext(Dispatchers.IO) {
        require(table in tables && afterId >= 0)
        val file = context.getDatabasePath(ChatHistoryDatabase.NAME)
        if (!file.isFile) return@withContext Page(emptyList(), 0, false)
        // 独立只读连接，不触发 Room 的建表或迁移，也不修改聊天数据库。
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { database ->
            val total = DatabaseUtils.queryNumEntries(database, table)
            val items = mutableListOf<Item>()
            val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
            database.rawQuery("SELECT * FROM $table WHERE _id > ? ORDER BY _id LIMIT $PAGE_SIZE",
                arrayOf(afterId.toString())).use { cursor ->
                val columns = cursor.columnNames
                val idIndex = cursor.getColumnIndexOrThrow("_id")
                while (cursor.moveToNext()) {
                    currentCoroutineContext().ensureActive()
                    val text = buildString {
                        columns.forEachIndexed { index, name ->
                            if (index > 0) append('\n')
                            append(name).append(": ")
                            append(if (cursor.isNull(index)) "NULL" else cursor.getString(index))
                            if (name == "time_millis" && !cursor.isNull(index)) {
                                append(" (").append(dateFormat.format(Date(cursor.getLong(index)))).append(')')
                            }
                        }
                    }
                    items += Item(cursor.getLong(idIndex), text)
                }
            }
            Page(items, total, items.size == PAGE_SIZE)
        }
    }
}
