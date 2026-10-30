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
        val ALIASES = listOf(

            // ==================== 社交聊天 ====================
            setOf("微信", "wechat", "weixin"),
            setOf("qq", "扣扣", "腾讯qq"),
            setOf("企业微信", "企微", "企业weixin"),
            setOf("钉钉", "dingtalk"),
            setOf("飞书", "lark", "feishu"),
            setOf("tim", "腾讯tim"),
            setOf("微博", "新浪微博"),
            setOf("小红书", "小红", "rednote"),
            setOf("知乎", "zhihu"),
            setOf("百度贴吧", "贴吧"),
            setOf("豆瓣", "douban"),
            setOf("陌陌", "momo"),
            setOf("探探", "tantan"),
            setOf("soul", "灵魂社交"),

            // ==================== 短视频 ====================
            setOf("抖音", "douyin"),
            setOf("抖音极速版", "抖音极速"),
            setOf("快手", "kuaishou"),
            setOf("快手极速版", "快手极速"),
            setOf("bilibili", "哔哩哔哩", "b站", "哔哩"),
            setOf("西瓜视频", "西瓜"),
            setOf("好看视频", "好看"),
            setOf("微视", "腾讯微视"),

            // ==================== 影视视频 ====================
            setOf("腾讯视频", "腾讯影视"),
            setOf("爱奇艺", "iqiyi"),
            setOf("优酷", "youku"),
            setOf("芒果tv", "芒果视频"),
            setOf("搜狐视频", "搜狐影视"),
            setOf("咪咕视频", "咪咕影视"),
            setOf("央视频", "央视视频"),
            setOf("央视影音", "cctv影音"),
            setOf("哔哩哔哩tv版", "云视听小电视"),

            // ==================== 音乐 ====================
            setOf("qq音乐", "扣扣音乐", "腾讯音乐"),
            setOf("网易云音乐", "网易云", "云音乐"),
            setOf("酷狗音乐", "酷狗", "kugou"),
            setOf("酷我音乐", "酷我", "kuwo"),
            setOf("汽水音乐", "汽水"),
            setOf("咪咕音乐", "咪咕"),
            setOf("波点音乐", "波点"),
            setOf("千千音乐", "千千"),
            setOf("唱吧", "changba"),
            setOf("全民k歌", "k歌"),

            // ==================== 电台 / 有声书 ====================
            setOf("喜马拉雅", "喜马拉雅fm", "喜马"),
            setOf("蜻蜓fm", "蜻蜓"),
            setOf("荔枝fm", "荔枝"),
            setOf("懒人听书", "懒人"),
            setOf("番茄畅听", "番茄听书"),
            setOf("云听", "中央广播电视总台云听"),
            setOf("得到", "得到app"),

            // ==================== 地图导航 ====================
            setOf("高德地图", "高德", "amap", "高德导航"),
            setOf("百度地图", "百度导航", "baidumap"),
            setOf("腾讯地图", "腾讯导航"),
            setOf("凯立德导航", "凯立德"),
            setOf("搜狗地图", "搜狗导航"),

            // ==================== 打车出行 ====================
            setOf("滴滴出行", "滴滴", "滴滴打车"),
            setOf("哈啰", "哈啰出行", "哈罗"),
            setOf("曹操出行", "曹操打车", "曹操"),
            setOf("t3出行", "t3打车"),
            setOf("嘀嗒出行", "嘀嗒拼车"),
            setOf("花小猪打车", "花小猪"),
            setOf("首汽约车", "首汽"),

            // ==================== 旅行 / 交通 ====================
            setOf("铁路12306", "12306", "铁路购票"),
            setOf("携程旅行", "携程", "ctrip"),
            setOf("飞猪旅行", "飞猪"),
            setOf("去哪儿旅行", "去哪儿", "qunar"),
            setOf("同程旅行", "同程"),
            setOf("航旅纵横", "航旅"),
            setOf("高铁管家", "高铁"),
            setOf("马蜂窝", "马蜂窝旅游"),

            // ==================== 购物 ====================
            setOf("淘宝", "手机淘宝", "taobao"),
            setOf("天猫", "tmall"),
            setOf("京东", "jd", "jingdong"),
            setOf("拼多多", "拼夕夕", "pdd"),
            setOf("闲鱼", "咸鱼", "xianyu"),
            setOf("唯品会", "唯品"),
            setOf("得物", "毒app"),
            setOf("1688", "阿里巴巴"),
            setOf("苏宁易购", "苏宁"),

            // ==================== 外卖 / 生活 ====================
            setOf("美团", "meituan"),
            setOf("美团外卖", "美团点外卖"),
            setOf("饿了么", "饿了嘛", "eleme"),
            setOf("大众点评", "点评"),
            setOf("盒马", "盒马鲜生"),
            setOf("叮咚买菜", "叮咚"),
            setOf("美团优选", "优选"),
            setOf("58同城", "五八同城"),
            setOf("安居客", "anjuke"),
            setOf("贝壳找房", "贝壳"),

            // ==================== 支付 / 金融 ====================
            setOf("支付宝", "alipay", "支付包"),
            setOf("云闪付", "银联云闪付"),
            setOf("数字人民币", "数字钱包"),
            setOf("招商银行", "招行", "掌上生活"),
            setOf("中国工商银行", "工商银行", "工行"),
            setOf("中国建设银行", "建设银行", "建行"),
            setOf("中国农业银行", "农业银行", "农行"),
            setOf("中国银行", "中行"),
            setOf("交通银行", "交行"),
            setOf("邮储银行", "邮政储蓄银行"),

            // ==================== 浏览器 ====================
            setOf("chrome", "谷歌浏览器", "谷歌chrome"),
            setOf("edge", "微软浏览器", "microsoftedge"),
            setOf("firefox", "火狐浏览器", "火狐"),
            setOf("夸克", "夸克浏览器"),
            setOf("uc浏览器", "uc"),
            setOf("qq浏览器", "扣扣浏览器"),
            setOf("via", "via浏览器"),
            setOf("kiwi", "kiwi浏览器"),

            // ==================== 网盘 / 文件 ====================
            setOf("百度网盘", "百度云", "百度云盘"),
            setOf("阿里云盘", "阿里网盘"),
            setOf("夸克网盘", "夸克云盘"),
            setOf("迅雷", "迅雷下载"),
            setOf("115网盘", "115"),
            setOf("123云盘", "123网盘"),
            setOf("中国移动云盘", "和彩云"),

            // ==================== 办公 ====================
            setOf("wps", "wpsoffice", "wps办公"),
            setOf("腾讯会议", "会议"),
            setOf("腾讯文档", "腾讯在线文档"),
            setOf("石墨文档", "石墨"),
            setOf("印象笔记", "evernote"),
            setOf("有道云笔记", "有道笔记"),
            setOf("网易邮箱大师", "邮箱大师"),
            setOf("qq邮箱", "扣扣邮箱"),
            setOf("outlook", "微软邮箱"),
            setOf("notion", "notion笔记"),

            // ==================== AI 助手 ====================
            setOf("豆包", "doubao", "豆包ai"),
            setOf("deepseek", "深度求索", "深度搜索"),
            setOf("chatgpt", "gpt", "openai"),
            setOf("kimi", "kimi智能助手", "月之暗面"),
            setOf("通义", "通义千问", "千问"),
            setOf("文小言", "文心一言", "百度ai"),
            setOf("腾讯元宝", "元宝", "元宝ai"),
            setOf("讯飞星火", "星火", "星火大模型"),
            setOf("智谱清言", "智谱", "清言"),
            setOf("天工ai", "天工"),
            setOf("秘塔ai搜索", "秘塔"),

            // ==================== 小说 / 阅读 ====================
            setOf("番茄免费小说", "番茄小说"),
            setOf("七猫免费小说", "七猫小说", "七猫"),
            setOf("起点读书", "起点", "起点中文网"),
            setOf("微信读书", "微信阅读"),
            setOf("qq阅读", "扣扣阅读"),
            setOf("掌阅", "ireader"),
            setOf("书旗小说", "书旗"),
            setOf("纵横小说", "纵横中文网"),

            // ==================== 游戏 / 社区 ====================
            setOf("tap tap", "taptap", "踏踏"),
            setOf("好游快爆", "快爆"),
            setOf("米游社", "米哈游社区"),
            setOf("网易大神", "大神"),
            setOf("steam", "蒸汽平台"),
            setOf("wegame", "腾讯游戏平台"),
            setOf("王者营地", "营地"),

            // ==================== 国际应用 ====================
            setOf("youtube", "油管"),
            setOf("youtube music", "youtubemusic"),
            setOf("spotify", "声田"),
            setOf("netflix", "奈飞", "网飞"),
            setOf("tiktok", "国际抖音"),
            setOf("instagram", "ins", "照片墙"),
            setOf("facebook", "脸书", "fb"),
            setOf("whatsapp", "瓦次艾普"),
            setOf("telegram", "电报", "tg"),
            setOf("discord", "discord聊天"),
            setOf("x", "twitter", "推特"),
            setOf("googlemaps", "谷歌地图"),
            setOf("gmail", "谷歌邮箱"),
            setOf("googleplay", "谷歌商店", "play商店"),

            // ==================== 汽车相关 ====================
            setOf("吉利汽车", "吉利"),
            setOf("吉利银河", "银河汽车"),
            setOf("领克", "领克汽车", "lynkco"),
            setOf("极氪", "极氪汽车", "zeekr"),
            setOf("比亚迪汽车", "比亚迪"),
            setOf("理想汽车", "理想app"),
            setOf("蔚来", "蔚来汽车", "nio"),
            setOf("小鹏汽车", "小鹏"),
            setOf("特斯拉", "tesla"),
            setOf("懂车帝", "懂车"),
            setOf("汽车之家", "车之家"),
            setOf("易车", "易车汽车"),
            setOf("车来了", "公交来了"),
            setOf("交管12123", "12123", "交管"),
            setOf("e充电", "国网充电"),
            setOf("特来电", "特来电充电"),
            setOf("星星充电", "星星"),
        )
    }
}
