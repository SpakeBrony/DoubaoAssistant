package com.spake.brony.doubaoassistant.navigation

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.net.Uri
import android.util.Log
import com.spake.brony.doubaoassistant.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 将目的地交给本机地图；成功只表示已打开接收界面，不代表地图已经开始导航。 */
internal class NavigationLauncher(context: Context) {
    private val appContext = context.applicationContext
    private val packageManager = appContext.packageManager

    suspend fun navigate(destination: String): Boolean = withContext(Dispatchers.Main.immediate) {
        val name = destination.trim()
        if (name.isEmpty()) return@withContext false
        // 地名只能作为参数传递，不能将模型输出当成 URI、包名或 Intent 执行。
        val encoded = Uri.encode(name)
        val geo = Uri.parse("geo:0,0?q=$encoded&mode=d&intent=navigation")
        val map = request(Intent.ACTION_VIEW, geo)
        val actions = if (packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE))
            listOf(CAR_NAVIGATE, PHONE_NAVIGATE) else listOf(PHONE_NAVIGATE, CAR_NAVIGATE)
        // Android 标准导航接口兼容手机和车机；是否支持由已安装应用的 Intent filter 决定。
        // https://developer.android.com/develop/devices/assistant/intents-assistant-nav-app
        val navigation = actions.map { request(it, geo) } + listOf(
            // 高德手机版按名称选择终点后导航，无需本应用申请地图 Key 或定位权限。
            // https://lbs.amap.com/api/amap-mobile/guide/android/keyword-navi
            request(Intent.ACTION_VIEW, Uri.parse("androidamap://keywordNavi").buildUpon()
                .appendQueryParameter("sourceApplication", appContext.packageName)
                .appendQueryParameter("keyword", name).appendQueryParameter("style", "0").build()),
            // https://developer.android.com/guide/components/google-maps-intents
            request(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=$encoded&mode=d")),
        )
        val mapHandlers = handlers(map)
        // ResolverActivity 是系统选择器，不是默认地图；必须与实际可接收的应用核对。
        val defaultPackage = defaultPackage(map, mapHandlers) ?: if (mapHandlers.isEmpty()) {
            navigation.firstNotNullOfOrNull { defaultPackage(it, handlers(it)) }
        } else null
        if (defaultPackage != null) {
            // 保留用户默认地图：专用/标准导航接口不支持时，在同一地图打开目的地。
            return@withContext (navigation + map).any { launch(Intent(it).setPackage(defaultPackage)) }
        }

        // 没有默认地图时，每个应用只保留一个最合适的入口，交给系统选择。
        val choices = linkedMapOf<String, Intent>()
        for (intent in navigation + map) {
            for (handler in handlers(intent)) {
                val pkg = handler.activityInfo.packageName
                choices.putIfAbsent(pkg, Intent(intent).setPackage(pkg))
            }
        }
        if (choices.isEmpty()) return@withContext false
        if (choices.size == 1) return@withContext launch(choices.values.first())
        val chooser = Intent.createChooser(choices.values.first(), appContext.getString(R.string.navigation_choose_map))
            .putExtra(Intent.EXTRA_INITIAL_INTENTS, choices.values.drop(1).toTypedArray())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        start(chooser)
    }

    private fun request(action: String, uri: Uri): Intent = Intent(action, uri)
        .addCategory(Intent.CATEGORY_DEFAULT).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private fun handlers(intent: Intent): List<ResolveInfo> =
        packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY).filter {
            it.activityInfo?.let { activity ->
                activity.exported && activity.enabled && activity.packageName != appContext.packageName
            } == true
        }

    private fun defaultPackage(intent: Intent, candidates: List<ResolveInfo>): String? {
        val resolved = packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo ?: return null
        return candidates.firstOrNull {
            it.activityInfo.packageName == resolved.packageName && it.activityInfo.name == resolved.name
        }?.activityInfo?.packageName
    }

    private fun launch(intent: Intent): Boolean = handlers(intent).isNotEmpty() && start(intent)

    private fun start(intent: Intent): Boolean = try {
        appContext.startActivity(intent)
        true
    } catch (error: ActivityNotFoundException) {
        Log.w("NavigationLauncher", "Map activity is unavailable", error)
        false
    } catch (error: SecurityException) {
        Log.w("NavigationLauncher", "Map activity cannot be opened", error)
        false
    }

    private companion object {
        const val PHONE_NAVIGATE = "android.intent.action.NAVIGATE"
        const val CAR_NAVIGATE = "androidx.car.app.action.NAVIGATE"
    }
}
