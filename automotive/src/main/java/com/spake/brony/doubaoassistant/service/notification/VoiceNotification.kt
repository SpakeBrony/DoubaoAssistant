package com.spake.brony.doubaoassistant.service.notification

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.spake.brony.doubaoassistant.R
import com.spake.brony.doubaoassistant.service.KwsService
import com.spake.brony.doubaoassistant.ui.MainActivity

internal class VoiceNotification(context: Context) {
    private val application = context.applicationContext
    private val manager = application.getSystemService(NotificationManager::class.java)

    fun createChannel() {
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID,
            application.getString(R.string.kws_channel), NotificationManager.IMPORTANCE_LOW))
    }

    fun build(assistantName: String, microphoneClosed: Boolean, replying: Boolean = false): Notification {
        val openIntent = Intent(application, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val open = PendingIntent.getActivity(application, 0, openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val toggleMicrophone = PendingIntent.getService(application, 1, Intent(application, KwsService::class.java)
            .setAction(if (microphoneClosed) KwsService.ACTION_START else KwsService.ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val builder = NotificationCompat.Builder(application, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(application.getString(when {
                !microphoneClosed -> R.string.kws_notification_title
                replying -> R.string.kws_reply_title
                else -> R.string.kws_session_title
            }, assistantName))
            .setContentText(application.getString(when {
                microphoneClosed && replying -> R.string.kws_reply_without_mic
                microphoneClosed -> R.string.kws_session_without_mic
                else -> R.string.kws_notification_text
            }))
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(R.drawable.ic_mic, application.getString(if (microphoneClosed) R.string.voice_enable_mic
                else R.string.stop_listening), toggleMicrophone)
        if (microphoneClosed && replying) {
            val pause = PendingIntent.getService(application, 2, Intent(application, KwsService::class.java)
                .setAction(KwsService.ACTION_PAUSE), PendingIntent.FLAG_IMMUTABLE)
            builder.addAction(R.drawable.ic_voice_pause, application.getString(R.string.voice_pause_chat), pause)
        }
        return builder.build()
    }

    fun update(notification: Notification) { manager.notify(ID, notification) }

    companion object {
        const val ID = 1
        private const val CHANNEL_ID = "local_kws"
    }
}
