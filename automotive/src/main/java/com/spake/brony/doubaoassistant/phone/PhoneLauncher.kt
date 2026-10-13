package com.spake.brony.doubaoassistant.phone

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.util.Log
import com.spake.brony.doubaoassistant.ui.PhonePermissionActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** 查询本机联系人并交给系统直接呼叫；缺少权限时等待系统授权结果。 */
internal class PhoneLauncher(context: Context) {
    private val appContext = context.applicationContext

    suspend fun dial(phoneNumber: String?, contactName: String?): Boolean {
        if ((phoneNumber == null) == (contactName == null)) return false
        val normalizedNumber = phoneNumber?.let { normalizeNumber(it) ?: return false }
        val name = contactName?.let { it.trim().takeIf(String::isNotEmpty) ?: return false }
        val required = buildList {
            add(Manifest.permission.CALL_PHONE)
            if (name != null) add(Manifest.permission.READ_CONTACTS)
        }
        val missing = required.filter { appContext.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty() && !PhonePermissionActivity.request(appContext, missing)) return false
        currentCoroutineContext().ensureActive()
        if (required.any { appContext.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }) return false
        val number = normalizedNumber ?: findFirstNumber(checkNotNull(name)) ?: return false
        return withContext(Dispatchers.Main.immediate) {
            // 使用固定 tel scheme，模型提供的号码不能变成任意 URI 或拨号控制码。
            val intent = Intent(Intent.ACTION_CALL, Uri.fromParts("tel", number, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (intent.resolveActivity(appContext.packageManager) == null) return@withContext false
            currentCoroutineContext().ensureActive()
            try {
                appContext.startActivity(intent)
                true // 只表示系统接受呼叫请求，不代表电话接通。
            } catch (error: ActivityNotFoundException) {
                Log.w("PhoneLauncher", "Dialer is unavailable", error)
                false
            } catch (error: SecurityException) {
                Log.w("PhoneLauncher", "Call cannot be initiated", error)
                false
            }
        }
    }

    private suspend fun findFirstNumber(name: String): String? = withContext(Dispatchers.IO) {
        try {
            // 仅精确匹配本机通讯录姓名；号码不发送给模型，不按同音或相似姓名猜联系人。
            appContext.contentResolver.query(Phone.CONTENT_URI, arrayOf(Phone.NUMBER),
                "${Phone.DISPLAY_NAME} = ?", arrayOf(name), null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    currentCoroutineContext().ensureActive()
                    // 同名或一人多号时，使用通讯录返回的第一个有效号码。
                    normalizeNumber(cursor.getString(0).orEmpty())?.let { return@withContext it }
                }
            }
            null
        } catch (error: SecurityException) {
            Log.w("PhoneLauncher", "Contacts permission is unavailable", error)
            null
        }
    }

    private fun normalizeNumber(value: String): String? {
        val number = value.trim().filterNot { it in " -()（）" }
        return number.takeIf { it.matches(Regex("\\+?[0-9]+")) }
    }
}
