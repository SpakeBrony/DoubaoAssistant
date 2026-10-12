package com.spake.brony.doubaoassistant.service.notification

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import com.spake.brony.doubaoassistant.R
import com.spake.brony.doubaoassistant.service.KwsService
import com.spake.brony.doubaoassistant.ui.MainActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 35])
class VoiceNotificationTest {
    @Test fun recordingStandbyAndPlaybackNotificationsExposeMicrophoneControl() {
        val application: Application = RuntimeEnvironment.getApplication()
        val notifications = VoiceNotification(application)
        notifications.createChannel()
        val manager = application.getSystemService(NotificationManager::class.java)
        for ((microphoneClosed, replying) in listOf(false to false, true to false, true to true)) {
            val notification = notifications.build("小白", microphoneClosed, replying)
            assertEquals(R.mipmap.ic_launcher, notification.smallIcon.resId)
            assertNotNull(manager.getNotificationChannel(notification.channelId))
            assertEquals(application.getString(when {
                !microphoneClosed -> R.string.kws_notification_title
                replying -> R.string.kws_reply_title
                else -> R.string.kws_session_title
            }, "小白"), notification.extras.getString(Notification.EXTRA_TITLE))
            val openIntent = shadowOf(notification.contentIntent).savedIntent
            assertEquals(MainActivity::class.java.name, openIntent.component?.className)
            val stopIntent = shadowOf(notification.actions.first().actionIntent).savedIntent
            assertEquals(KwsService::class.java.name, stopIntent.component?.className)
            assertEquals(if (microphoneClosed) KwsService.ACTION_START else KwsService.ACTION_STOP, stopIntent.action)
            assertEquals(if (microphoneClosed && replying) 2 else 1, notification.actions.size)
            if (microphoneClosed && replying) assertEquals(KwsService.ACTION_PAUSE,
                shadowOf(notification.actions.last().actionIntent).savedIntent.action)
            if (microphoneClosed && !replying) assertEquals(application.getString(R.string.kws_session_without_mic),
                notification.extras.getString(Notification.EXTRA_TEXT))
            notifications.update(notification)
            assertNotNull(shadowOf(manager).getNotification(VoiceNotification.ID))
        }
        manager.cancel(VoiceNotification.ID)
    }
}
