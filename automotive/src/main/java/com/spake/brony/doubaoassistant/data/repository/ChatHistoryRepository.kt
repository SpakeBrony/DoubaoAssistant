package com.spake.brony.doubaoassistant.data.repository

import android.content.Context
import com.spake.brony.doubaoassistant.data.local.ChatHistoryDatabase
import com.spake.brony.doubaoassistant.data.model.ConversationMessage
import com.spake.brony.doubaoassistant.data.model.ChatTopic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CompletableDeferred

internal class ChatHistoryRepository(context: Context, private val onError: () -> Unit) : AutoCloseable {
    private val database = ChatHistoryDatabase.create(context)
    private val dao = database.historyDao()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writes = Channel<() -> Unit>(64)
    private val databaseLock = Any()
    private var databaseClosed = false
    private val writer = scope.launch {
        try {
            for (write in writes) {
                try { synchronized(databaseLock) { write() } } catch (_: Exception) { onError() }
            }
        } finally {
            try { synchronized(databaseLock) { databaseClosed = true; database.close() } }
            finally { scope.cancel() }
        }
    }

    suspend fun currentTopic(): ChatTopic = withContext(Dispatchers.IO) {
        synchronized(databaseLock) { check(!databaseClosed); dao.currentTopic() }
    }

    suspend fun latest(topicId: String): List<ConversationMessage> = withContext(Dispatchers.IO) {
        synchronized(databaseLock) { check(!databaseClosed); dao.latest(topicId) }
    }

    fun createTopic(topic: ChatTopic) = enqueue { dao.createTopic(topic) }

    fun save(message: ConversationMessage) = enqueue { dao.save(message) }

    suspend fun lastResponseId(topicId: String): String? {
        val result = CompletableDeferred<String?>()
        // 与写入排同一个队列，读取必须等上一轮 completed ID 真正保存完成。
        writes.send {
            try { result.complete(dao.lastResponseId(topicId)) }
            catch (e: Exception) { result.completeExceptionally(e) }
        }
        return result.await()
    }

    // 与旧回复的最后一次保存串行执行，清空后只允许排在后面的新话题写入。
    fun clearAll(topic: ChatTopic) = enqueue { dao.clearAll(topic) }

    private fun enqueue(write: () -> Unit) {
        // 有界队列；磁盘故障不能静默丢记录，也不能无限积压车机内存。
        if (writes.trySend(write).isFailure) onError()
    }

    override fun close() { writes.close() } // 排空已提交写入后关闭，不随服务 scope 一起丢掉最后一条。

    internal suspend fun awaitClosed() { writer.join() }
}
