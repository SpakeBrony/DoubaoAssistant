package com.spake.brony.doubaoassistant.apps

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 按本机应用显示名称或别名匹配启动入口，不把模型输出当作包名、URI 或组件执行。 */
internal class AppLauncher(context: Context) {
    private val appContext = context.applicationContext

    suspend fun open(appName: String): Boolean {
        val name = normalize(appName)
        if (name.isEmpty()) return false
        val component = withContext(Dispatchers.IO) {
            val manager = appContext.packageManager
            val activities = listOf(Intent.CATEGORY_LAUNCHER, Intent.CATEGORY_LEANBACK_LAUNCHER)
                .flatMap { category ->
                    manager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(category), 0)
                }.filter { result ->
                    result.activityInfo?.let { it.exported && it.enabled && it.applicationInfo.enabled } == true
                }
            val names = activities.associateWith { result ->
                setOf(normalize(result.loadLabel(manager).toString()),
                    normalize(result.activityInfo.applicationInfo.loadLabel(manager).toString()))
            }
            val aliases = ALIASES.firstOrNull { name in it }.orEmpty()
            val match = activities.firstOrNull { name in names.getValue(it) }
                ?: activities.firstOrNull { names.getValue(it).any { label -> label in aliases } }
                // 简称只对应一个应用时才使用，避免“音乐”匹配到多个应用后随意打开。
                ?: activities.filter { names.getValue(it).any { label -> label.contains(name) } }
                    .distinctBy { it.activityInfo.packageName }.singleOrNull()
            match?.activityInfo?.let { ComponentName(it.packageName, it.name) }
        } ?: return false
        return withContext(Dispatchers.Main.immediate) {
            try {
                appContext.startActivity(Intent.makeMainActivity(component)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED))
                true // 只表示启动请求已交给系统，不代表应用内部功能已执行。
            } catch (error: ActivityNotFoundException) {
                Log.w("AppLauncher", "App launcher is unavailable", error)
                false
            } catch (error: SecurityException) {
                Log.w("AppLauncher", "App cannot be opened", error)
                false
            }
        }
    }

    private fun normalize(value: String): String = value.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }

    private companion object {
        // 只补充常用叫法；其他应用直接匹配安装后的显示名称，无需在这里逐个登记。
        val ALIASES = listOf(
            setOf("bilibili", "哔哩哔哩", "b站"),
            setOf("qq音乐", "扣扣音乐"),
            setOf("网易云音乐", "网易云", "云音乐"),
            setOf("高德地图", "高德"),
            setOf("百度地图", "百度导航"),
            setOf("腾讯地图", "腾讯导航"),
            setOf("微信", "wechat"),
            setOf("qq", "扣扣"),
        )
    }
}
