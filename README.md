# DoubaoAssistant — 原生语音助手界面与本地唤醒

Android Automotive 项目已接入 sherpa-onnx KWS、豆包流式语音识别 Android SDK、Doubao-Seed-2.0-mini 流式文字回复和豆包双向流式 TTS。本地 KWS 随 APK 打包，不联网、不按次收费；语音转文字、AI 回复及语音合成使用云端服务，需要联网及已开通的资源，会产生服务用量。聊天文字保存到应用私有 SQLite 数据库。

## 豆包流式语音识别 SDK

使用官方 `com.bytedance.speechengine:speechengine_tob:0.0.15.0` 和 OkHttp 4.9.1。协议为 Seed，资源为用户选定的 `volc.seedasr.sauc.duration`（2.0 小时版），端点为 `wss://openspeech.bytedance.com/api/v3/sauc/bigmodel_async`。参考 [Android SDK 文档](https://docs.volcengine.com/docs/DoubaoVoice/large-model-streaming-automatic-speech-recognition-android-sdk-interface-documentation?lang=zh) 与 [ASR API 文档](https://docs.volcengine.com/docs/DoubaoVoice/LargemodelstreamingautomaticspeechrecognitionAPI?lang=zh)。

本机配置在项目根目录 `doubao-asr.properties`，已被 Git 忽略；新环境按 `doubao-asr.properties.example` 填写。新版填写 `apiKey`；旧版填写 `appId` 和 `accessToken`，不使用 Secret Key。两种同时填写时优先 API Key。已按用户确认去掉网页复制误带的 `svg` 后缀，不在文档中重复凭证。

构建分为 `personal` 自用版和 `general` 通用版，两者均提供 debug/release，均仅打包 ARM64。只有自用版的 BuildConfig 从本机 `doubao-asr.properties` 读取凭证，用于首次配置表单预填；通用版四个凭证默认值全部为空，不嵌入开发者的密钥。通用版应用 ID 为 `com.spake.brony.doubaoassistant.general`，可与保留原应用 ID 的自用版同时安装，配置和聊天记录互不共享。release 保留代码混淆和资源压缩；自用版 APK 仍含默认密钥，不能公开分发。

仍然只有一个 AudioRecord。16 kHz、单声道、PCM16 little-endian 同时用于 KWS 和 SDK 的 `RECORDER_TYPE_STREAM`，不启用 SDK 内置录音，不保存录音文件，不申请存储权限。前台直接识别，后台默认等待唤醒；开启并授权「后台悬浮聊天」后，后台可继续识别。暂停/继续按钮控制聊天，暂停会停止 AI/TTS、释放 ASR 并保留本地 KWS；麦克风按钮只控制采音，关闭后 KWS/ASR 停止，已在生成或播报的回答继续。关麦不暂停聊天，主界面和悬浮窗的暂停/继续按钮保持可用；关麦期间暂停或继续都不会重新打开录音，已暂停的聊天重新开麦后仍保持暂停。关麦后保留会话、常驻通知和已开启的后台悬浮窗，不恢复录音；有回复时使用 mediaPlayback 前台类型，回复完成后 Android 14+ 切换为 specialUse 聊天窗口待机，旧版 Android 使用无麦克风类型的前台服务；重新开麦不取消正在播报的回答。打开设置不暂停。SDK 仅有 ARM/ARM64 库，当前 APK 仅打包 `arm64-v8a`，需要支持运行 ARM64 应用的设备；不支持仅能运行 ARM32 应用的车机，也不能用 x86 模拟器测试此 SDK。

SDK 操作全部在现有录音工作线程进行，回调仅替换有界待处理数据；旧会话回调与新会话隔离，不在 SDK 回调中同步销毁。最大语音时长配置仍为 60 秒，接收超时配置为 70 秒。每轮语音分句最终确认且没有未完成文字后交付结果、释放旧请求，服务接续新 ASR；正常请求结束或 `CODE_RECEIVE_TIMEOUT` / 4001 也由服务接续，仅更新 ASR 请求代次，不取消正在生成/播报的回答、不重置 Response ID。应用的无人说话计时独立于 SDK：开启聊天或最新语音活动后等待 70 秒，等待 AI / 播报期间取消计时，回复结束后重新等待 70 秒；接续 ASR 不重置该计时。真正静默超时才显示「没有听到你的声音,已暂停聊天」。其他 SDK 错误及结果解析异常显示「语音识别异常，请重新开启麦克风」，保留原始诊断，不无限重试。聊天暂停时麦克风和本地 KWS 继续工作，但不再向云端 ASR 送音频。用户点击继续按钮，或说「可以说话了豆包」「你好豆包」「豆包你好」「在吗豆包」「豆包在吗」恢复。普通唤醒词也能解除「闭嘴豆包」或暂停按钮造成的聊天暂停，前后台均生效；关闭麦克风后不能语音恢复，只能手动开启。状态通过 `AsrRepository → KwsViewModel.uiState → Data Binding` 传给页面，页面不持有 SDK。单次长发言若触及 SDK 上限，或切换时网络异常，仍需实机验证分句完整性。

ASR 与双向 TTS 通过 SDK 的 `setWsClient` 使用 `DoubaoBinaryWsClient`，不更换语音 SDK、不手写语音协议。SDK 0.0.15.0 默认 `OkHttpWsClient.send` 将整包转成 UTF-8 并 `trim()` 后猜测 JSON；当二进制包头和长度字节均为控制字符时，会误删包头并发送裸 JSON 文本，触发 `unsupported protocol version 7`。替换后的发送层直接 `send(ByteString)`，完整保留 SDK 封包；旧连接回调隔离，取消连接后释放自有连接池和执行器。设备回归测试覆盖默认客户端复现、ASR/TTS 二进制包保持及 ASR SDK 实际首包/音频包，不录音、不连接云端。

ASR/TTS 错误回调按 `len` 使用 UTF-8 解码，直接用 `Log.e` 打印 SDK 原文（仅凭证脱敏），标签为 `DoubaoAsr` / `DoubaoTts`；UI 提示统一从 `res/values/strings.xml` 的 `R.string` 获取，不展示原始 JSON 或异常详情。识别失败、播报失败/超时/声音占用、聊天失败及历史记录错误均资源化；纯业务协调器只接收字符串读取函数，ViewModel 不持有 Context。同步接口只返回数字时，方法/指令与实际返回码保留在诊断中。脱敏后的警告、错误和 SDK 请求 logid 在 Debug / Release 均保留；详细播放进度仅在 Debug 输出，不记录音频或对话正文；过长消息受 Logcat 长度限制。ASR 启动日志记录实际 SDK 版本、Seed 协议配置和 URI。当前默认文案为中文，尚未增加其他语言译文。

开启分句与 1500 ms 判停；中间字幕替换显示，确定分句按时间去重，最多保留 40 条文字。默认前台且未暂停的聊天期间上传音频并产生云端用量；未开启悬浮聊天时，离开主页先只运行本地 KWS，收到七条恢复口令中的任意一条后直接开启后台 ASR 与 AI 聊天，不打开 App，也不要求悬浮窗授权。用户开启「后台悬浮聊天」、已授权且窗口成功显示时，离开主页可以直接延续同一段聊天。暂停、关麦或默认退到后台时停止向 ASR 送音频，旧代次回调不得继续触发回复。× 确认后停止服务并退出应用，不再切换话题。

本次仅基础编译，不使用凭证调用云端，不执行截图回归；鉴权、实际结果回调、断网、长时间运行与 16 KB 页大小需要 ARM 真机验证。仍保留 Automotive 必需特性，手机安装需另行调整设备特性配置。

## 豆包 Responses 流式回复与说话波形

设置 →「AI回复习惯」提供「极简 / 简单 / 详细」，默认「简单」，保存到 SharedPreferences 的 `reply_style`。每轮只在通过本地指令规则之后、发给 AI 的请求文字末尾追加所选提示句；用户消息、话题标题和页面均保留原文，提示句不写入 Room。修改不会重启语音或打断当前回复，下一次请求生效；回复长度仍由模型遵循提示决定。

麦克风关闭时，底部提示替换为文字输入框，hint 为「麦克风已关闭，输入消息回复」。软键盘发送键或实体回车将完整文字送到与 ASR 确定句共用的入口，先判断当前助手称呼和操作规则，再按需进入 AI、保存历史/上下文和 TTS；不会重新打开麦克风、KWS 或 ASR。新文字可打断旧 AI/TTS；「关闭麦克风」仍不取消当前输出。首条输入等待历史加载完成，避免覆盖旧话题。输入为空不发送，最多 8192 字符。

使用 `https://ark.cn-beijing.volces.com/api/v3/responses`，模型 `doubao-seed-2-0-mini-260428`。在忽略的 `doubao-asr.properties` 中填写独立的 `arkApiKey`；它与语音 SDK 的凭证不同，自用的 debug 和 release APK 均读取此配置。请求只发送识别后的文字，不发送示例图片；开启 `stream`，关闭深度思考，单次输出上限 2048 token。参考 [Responses 文本生成文档](https://docs.volcengine.com/docs/ark/responses-api-text-generation?lang=zh) 和 [流式输出事件](https://docs.volcengine.com/docs/ark/output-and-text?lang=zh)。

ASR 返回确定分句且没有未完成文字后，`ConversationCoordinator` 先发布用户消息，再请求模型；增量文字累计到下方的同一条豆包回复，保持现有浅/深色布局。ASR 的连接状态不会覆盖已有回复。重新说话、暂停聊天、进入设置或结束会话会取消未完成请求，旧请求不能更新新会话；网络失败显示错误，不伪造回答、不自动重发付费请求。请求属于服务生命周期，不属于 Activity，也不存入全局单例。

上下文 ID 已保存到 SQLite 的 messages.previous_response_id：该列保存本条 AI 回复成功完成时豆包返回的 Response ID，下一轮作为请求的 previous_response_id 发送。每次请求都经 ChatHistoryRepository 的串行队列读取当前 topic_id 下最后一条 is_assistant=1 且 ID 非空的消息，等已排队写入完成后再读，不依赖进程内镜像，也不受 UI 最近 40 条的限制。未完成、被打断或失败的回答没有有效 ID；重启应用后仍可续接当前话题，新话题或清除记录后不会串用旧话题 ID。数据库读取失败时不擅自无上下文发送付费请求；不自动重试过期或无效的云端 ID。store:true 仍要求火山平台保存对应上下文，本机保存或删除 ID 不等于控制云端数据保留期限。

中间三点在静默时保持静止；ASR 检测到正在说话后变为七条对称音量条，读取同一个 AudioRecord 的 RMS 音量。初始数字门限约 -50 dBFS，不是经过校准的环境声压分贝；音量不足时不会波动，单有音量但没有 ASR 未完成文字也不会波动。首次动画会有 ASR 首字延迟。动画仅做 100 ms 有限插值，控件分离时取消，不播放无限假波形。句末由服务端 1.5 秒 VAD 判停确认，不单靠音量判句；不同麦克风、噪声与网络条件仍需实机验证。

本次只编译 APK 和测试源码，未运行新增测试、截图回归或真实云端请求。实际合成、播放器最终回调、音频焦点和打断延迟需要 ARM 真机验证。

## 双向流式 TTS 与打断

复用已经接入的 `speechengine_tob:0.0.15.0`，使用 `BITTS_ENGINE`、`wss://openspeech.bytedance.com/api/v3/tts/bidirection`、资源 `seed-tts-2.0` 与音色 `zh_female_vv_uranus_bigtts`，按用户确认复用现有语音 `apiKey` 或 `appId + accessToken`。参考 [双向 WebSocket 文档](https://docs.volcengine.com/docs/DoubaoVoice/WebSocketBidirectionalStreaming-V3?lang=zh) 和 [双向 TTS Android SDK](https://docs.volcengine.com/docs/DoubaoVoice/BidirectionalStreamingTTS-AndroidSDKInterfaceDocumentation?lang=zh)。旧 SDK 示例中的 `volc.service_type.10029` 不用于本次 2.0 音色。

每条回复使用一个合成会话：启动引擎后在工作线程按顺序发送 StartSession、逐段 TaskRequest，模型结束时发送 FinishSession；SDK 负责异步指令排队，不等待未返回的 ConnectionStarted / SessionStarted 回调才发出指令。只喂累计回复新增的后缀，不重复朗读前文，不在应用端等整句才发送；音频使用 PCM/24 kHz 和 SDK 播放器，不落音频文件、不申请存储权限。连接/播放器实例在完成、取消、失败或服务销毁时释放，不永久占用空闲合成连接。设置里的慢/标准/快语速分别映射为 -20/0/20，新回复开始时读取设置。

回复下方的喇叭重播入口暂时隐藏，不占布局空间，保留重播逻辑供以后恢复；不影响回复自动语音播放。

ASR 出现新的未完成文字或确定语句时，同时取消旧 Responses 请求和旧 TTS（暂停播放器、取消合成、销毁旧引擎），包括“文字生成已完，但声音还没播完”的情况。旧音频和旧回复回调不能更新新一轮状态。麦克风、ASR 和 KWS 都不因自动打断而关闭，等用户说完后再回答；“闭嘴豆包”是用户主动静音，仍沿用原来的暂停聊天规则。

TTS 使用独立工作线程，不与录音推理共用线程，也不在 SDK 回调里停止/销毁；累计文本与唤醒信号有界，不排队保存音频。播报申请短暂音频焦点，焦点被其他应用抢走时停止语音、保留文字聊天。TTS 失败独立显示，不关闭 ASR、不无限自动重试；单次播报最长 5 分钟作为资源释放上限。

自动打断依赖 ASR 首字结果，不是零延迟人声检测。按用户此前决定，本次不增加软件 AEC/扬声器参考信号处理；已有系统 AEC 仍保留。**无法保证排除豆包声音被麦克风识别后的自触发**，要在目标设备上验证。

## 本地聊天记录

使用 Jetpack Room 2.8.5，通过 `Entity → DAO → RoomDatabase` 管理应用私有 SQLite 文件 `databases/chat_history.db`，不再手写建表、Cursor 或旧版迁移。复用现有 KAPT 生成数据库实现；DAO 的 `@Query` 由 Room 编译检查，所有数据库访问仍在 Repository 的有界 IO 队列或 IO 读取中执行，不允许主线程查询。`topics` 保存话题 ID、第一条用户消息和创建时间；`messages` 通过 `topic_id` 外键关联话题，保存时间（毫秒时间戳）、内容、角色（`is_assistant`：0 用户、1 豆包 AI、2 系统）和可空的 `previous_response_id`，另有内部流水号与消息 ID 用于更新和排序。只有成功完成的 AI 回复保存本轮返回的 Response ID，用户、系统及未完成的 AI 回复不填写；不保存凭证或音频。空话题的第一句话留空，收到第一条用户消息时填写，后续消息不覆盖它。

按当前仅测试最新版的要求，Room 从版本 1 开始，不提供历史 `Migration`，配置 `fallbackToDestructiveMigration(dropAllTables = true)`：旧 SQLite v3 测试库或不匹配的数据库版本会清空重建，不保留旧聊天和上下文 ID。安装更新本身不删除昵称或设置。以后改 Entity 结构仍要提高 Room 版本号或清除测试数据；相同版本的结构校验失败不自动重建。正式发布且需要保留历史时，必须移除这项测试期破坏性策略并提供迁移。

用户确定文字立即提交保存；豆包回复更新同一个消息 ID，最多约每 500 ms 提交一次累计文字，完成、失败或被打断时补交最后收到的文字，因此不会逐字插入重复行。写入在有界 IO 队列执行，服务关闭时排空已提交的写入；磁盘/队列失败显示记录错误，不静默声称已保存。强制杀进程/断电可能丢失尚未提交或尚未完成落盘的最后一小段文字。

点击 × 弹出退出应用的确认弹窗；取消不改变任何状态。确定后移除当前任务，停止麦克风、KWS、ASR、AI 和播报并移除悬浮窗；录音工作线程释放资源、SQL 写入队列排空后才结束自身进程，不清除聊天记录或创建新话题。退出后不能语音唤醒，需要手动打开应用。重新启动服务只恢复当前话题最近 40 条到 UI，不跨话题混入历史，也不自动重播；Response ID 随 AI 回复保存在 SQLite。数据库及 journal 排除系统云备份和设备迁移；本地 SQLite 为明文，受应用沙箱保护，不等于数据库加密。卸载或清除应用数据会删除记录，当前未添加历史话题列表、导出、分页或保留期限功能。

设置 →「清除聊天记录」与 × 不同：取消不改变任何状态；确认后回到聊天页，取消旧 AI/TTS、清除当前列表及 Response 上下文，删除本机 `messages` 和 `topics` 的所有旧记录并创建一个新的空话题。数据库删除在同一个事务中执行，排在旧回复最后一次保存之后、新消息写入之前，防止旧排队写入恢复已删除记录。失败回滚并通过聊天记录错误提示告知，不修改昵称或其他设置；保留当前麦克风/KWS 启停状态，操作不自动解除手动暂停或打开已关闭的麦克风。删除无法在应用内撤销；这不是安全擦除磁盘或删除云端 Responses 数据。

## 运行

用 Android Studio 打开项目，默认使用自用版 `personalDebug`（预填本机凭证），需要通用版时在 Build Variants 选择 `generalDebug`，运行 `automotive` 到支持 ARM64 应用的 Android 车机。启动入口 `PermissionActivity` 同时负责服务凭证和麦克风权限：首次启动必须确认保存凭证，即使自用版已预填或麦克风已授权也不会跳过。配置页只使用 App ID + Access Token；另外必填 Ark API Key，不需要 Secret Key。只有保存成功且麦克风已授权才进入主页并启动监听。之后重新启动自动读取已保存配置，满足权限后直接进入主页，不做开机自动录音。Android 13 及以上同时申请通知权限，但通知拒绝不会阻止已授权的麦克风功能。

拒绝授权留在权限页，可再次申请或通过系统返回退出。永久拒绝时「前往设置」打开当前应用的系统权限设置，返回后重新检查。`MainActivity` 同时检查已保存的有效凭证和麦克风权限，服务也拦截未配置的启动、文字发送与重播请求，防止内部入口或通知绕过门禁；权限撤销后关闭录音服务并返回配置页。请求流程使用 AndroidX Activity Result，不绘制假的系统权限弹窗，依据 [Android 权限文档](https://developer.android.com/training/permissions/requesting)。

服务凭证以 AES-256-GCM 加密后写入应用私有 SharedPreferences `credentials.xml`；加密密钥由 Android Keystore 管理，不写入 SP。每次保存生成新 IV，凭证 SP 排除云备份和设备迁移，输入框不写入 Activity 保存状态、不参与自动填充，日志对运行时凭证脱敏。密钥失效或密文损坏时必须重新配置，不自动改用自用版默认凭证。保存不会验证云端账号是否有效，实际鉴权在服务调用时完成。

设置 →「服务凭证」→「修改」可重新编辑。打开时停止当前录音与回复，保存后使用新配置重新进入聊天；不会清除历史或其他设置。清除聊天记录不删除凭证；清除应用数据或卸载后需要重新配置。

## 回答模式与设置保存

设置 →「回答模式」默认选择「聊天回答」：回答后继续聆听，保持连续对话。「单次回答」会在本轮 AI 文本流和 TTS 播放都完成后暂停云端聊天；采音和 KWS 保留，说唤醒词或手动继续后可在原话题内接着聊，不清空记录和上下文。模式以提问时的选择为准，修改设置不会打断当前回答；取消、请求/播报失败、独立的系统提示音或重播不会被当作一轮成功回答。关闭麦克风后的文字输入仍可逐条发送，不自动开麦。

设置项按单选、开关、操作按钮排列，操作按钮在说明右侧纵向居中。昵称、外观、字幕、语速、回复习惯、回答模式、后台聊天和左右布局都通过 `AssistantPreferences` 保存在私有 SP `assistant.xml`；键名集中管理，保留旧键，重新打开应用不会重置。服务凭证仍单独加密存储；系统权限是否允许以 Android 实际授权状态为准，不以 SP 伪装授权。

## UI 设计还原

视觉依据为用户提供的 `../High-Fidelity UI Design/src/App.tsx`、`src/index.css` 和 `DESIGN.md`，参考目录保持只读。实现为原生 Views + Data Binding + ViewModel + StateFlow，不使用 WebView，不引入 Compose 或第三方 UI 框架。

- 浅深色直接使用原设计的语义颜色，分别放在 `res/values/colors.xml` 与 `res/values-night/colors.xml`；布局结构完全共用。外观设置支持浅色、深色、跟随系统，使用 [AppCompat DayNight](https://developer.android.com/develop/ui/views/theming/darktheme)，并持久化用户选择。
- `AmbientBackgroundView` 绘制粉色、蓝色、淡紫色三层径向渐变，图标由原设计 SVG 路径转换为 Android VectorDrawable。`DesignDimensions` 对应 CSS 的 clamp、标准横屏与超宽断点；额外压缩手机横屏低于 540 dp 的竖向间距，触控目标仍至少 48 dp。
- 权限页、顶部设置/字幕按钮、对话列、底部状态与圆形麦克风/结束按钮、左侧设置面板按原设计实现。点「字」或设置开关隐藏/显示文字，保留实际对话数据；主题、字幕和播报速度保存到 SharedPreferences，播报速度用于下一条 TTS 回复。
- `AsrRepository` 发布识别连接状态，`ConversationRepository` 发布用户文字及真实模型回复，ViewModel 自动订阅；`onConversationChanged` 保留供设计测试使用。设计示例回复只用于截图测试。连接中显示连接状态，连接成功才显示「可以开始说话」，不伪报 AI 回答。
- 主页操作栏从上到下为「字」、麦克风、暂停/继续、退出（×），整组纵向居中；圆形按钮缩小，触摸尺寸不少于 48dp，聊天文字为按钮侧预留空间。麦克风按钮只开启/关闭采音，不停止 AI/TTS；暂停/继续按钮控制聊天、保留 KWS。关麦但仍在回复时可点击暂停停止输出；关麦且回复结束后暂停/继续按钮禁用，必须手动开麦才重新监听。× 确认后关闭服务并退出进程，聊天记录保留。设置 →「页面布局」选择按钮在左或在右，立即镜像操作栏、对侧顶部「…」、设置面板及聊天留白，选择保存到 SharedPreferences 的 `controls_on_left`，默认按钮在右。浅深色共用布局，不改变渐变背景或字号。「…」仅打开设置，不暂停 ASR、KWS、回复或播报，不重启服务、不清除聊天上下文，也不会解除用户主动设置的「闭嘴豆包」。暂停/继续图标采用 Lucide，许可证随 APK 打包。
- 对话文字按用户确认的目标图调整：小屏基准为豆包 26sp / 用户 20sp，宽车机最多 36sp / 28sp；正常字重、无额外字距，豆包/用户分别为 1.45 / 1.5 倍行距，段后 32dp，旧消息不再自动降为 45% 不透明度。颜色仍使用浅深色主题的正文/次要文字色；文字列按扣除右侧按钮后的实际可用宽度居中，避免再次按整屏宽度扣除留白。
- 设置面板 240 ms 滑入/遮罩淡入；状态圆点和波形轻量动画。系统关闭动画时不运行这些动效，页面不可见或控件分离时取消动画，页面停止时结束界面过渡。设置打开时屏蔽背景焦点与无障碍导航。

原生截图为 `automotive/build/reports/ui-snapshots/`，包含 11 个设计状态 × 浅深两种主题，以及 1920 × 720 和 800 × 360 的横屏检查。截图由 Robolectric 原生绘图生成，非 Photoshop 或网页截图。已用 1280 × 720 的原 CSS 浏览器参考图进行视觉对照；系统中文字体、字体缩放和不同 DPI 仍需在目标车机上验收，不能把主机截图当成实机逐像素一致性保证。

可选的只读参考图生成工具：`node tools/render-design-reference.cjs '../High-Fidelity UI Design' <playwright 包路径>`。需要本地 Playwright 和 Google Chrome，输出到 `automotive/build/reports/design-reference/`；不执行原型的语音代码、不申请麦克风、不修改设计项目。

| 短语 | 本地事件 | 状态变化 |
| --- | --- | --- |
| 你好豆包 | `RESUME` | 恢复语音聊天；前后台均可直接开启 ASR，不打开 App，不依赖悬浮聊天开关 |
| 豆包你好 / 在吗豆包 / 豆包在吗 | `RESUME` | 同上，只恢复说话，不打开 App |
| 闭嘴豆包 / 豆包闭嘴 | ASR `STOP_REPLY` | 停止聊天和旧回复，直接 TTS「好的」，保留 KWS |
| 豆包关闭麦克风 / 关闭麦克风豆包 | ASR `CLOSE_MICROPHONE` | 只关闭采音，当前 AI 请求和 TTS 继续；不播报关麦确认，只能手动开麦 |
| 可以说话了豆包 / 说话豆包 / 豆包说话 | `RESUME` | 同上，七条口令统一恢复说话；麦克风关闭后口令无效 |
| 退下吧豆包 / 退下豆包 / 豆包退下 | ASR `RETURN_HOME` | 返回桌面，操作请求成功后直接 TTS「我退下了」，保留 KWS 和话题 |
| 退出豆包 / 豆包退出 / 退出吧豆包 / 下去吧豆包 | ASR `RETURN_HOME` | 同退下，不杀进程、不清空话题 |

以上为默认称呼「豆包」的口令。设置 → 助手称呼 → 修改，会弹出输入框；确认 2～4 个汉字后将称呼保存到 SharedPreferences 的 `assistant_name`，取消或校验失败不保存。昵称默认「豆包」，重启应用后读取已保存的称呼。

指令分为两个枚举：`KwsCommand` 只有 RESUME，`AsrCommand` 只有 STOP_REPLY/RETURN_HOME/CLOSE_MICROPHONE。`speech/asr/AsrCommands.kt` 集中维护不带助手称呼的动作关键词和可选回复资源：闭嘴 →「好的」；退下/退出/下去吧 →「我退下了」；关闭麦克风没有确认文案。匹配仍要求 ASR 确定文字同时包含当前称呼，忽略标点和空格，多个动作按关麦、闭嘴、退下优先级只执行一个；引用或否定句也可能触发。操作不送 AI：先保存 ASR 用户原句为角色 0，闭嘴/退下执行后无论成功或失败均保存系统文案为角色 2，并把 strings 原文交给 TTS；失败不冒充成功。关麦只保留用户原句，不新增系统成功消息或确认 TTS，停止采音失败则显示资源化错误。这里的 TTS 仍是云端合成，需要网络与凭证；不再使用 haode 或 MediaPlayer 播放 ASR 操作确认。

KWS 词表仅保留七条唤醒/恢复短语，均随昵称替换。ASR 操作无需加入拼音词表，仅扩展 AsrCommands 的规则；动作关键词不硬编码「豆包」，匹配时读取当前称呼。`keywords.txt` 是默认唤醒模板，空 `base_keywords.txt` 初始化模型，当前词表传给 `createStream(keywords)`，动态昵称切换仍在录音线程释放旧流，不重启麦克风。

本地提示音仅用于 KWS：七条 RESUME 口令统一播放 haode.mp3，SoundPool 增益均为 70%；AI 与直接确认 TTS 使用正常 SDK 音量。KWS 命中一律取消旧 ASR 请求、清空旧句并重新允许监听，包括正常前台聊天；不重启 AudioRecord。闭嘴先停止旧回复后直接 TTS 确认，退下成功后直接 TTS 确认。语音关麦与按钮关麦都只停止 KWS/ASR 采音，不取消当前 Responses 或 TTS，不追加确认播报；当前回答结束后仍保留通知和聊天小窗，没有回答时直接进入关麦待机；通知和小窗内的麦克风按钮可以手动重开。已配置关麦口令的临时前缀（称呼在前或在后）先延后语音打断，确定句匹配后才关麦；临时文字改写为普通问题时恢复打断，最终没有称呼的句子仍作为普通问题处理。ASR 若先把关麦前缀识别成其他普通文字，已发生的打断无法撤回；带任意前导句或混合多个操作的说法仍需实机验证。确认可在暂停时播报，播完或失败后不自动开麦。暂停、退出或再次唤醒会清除待播确认，旧音频回调不能触发新确认。

拼音使用 [mozillazg/pinyin-data](https://github.com/mozillazg/pinyin-data) 的 MIT 离线数据，版本及许可证位于 `assets/kws/pinyin-SOURCE.md` 与 `pinyin-LICENSE.txt`。逐行读取，仅保留昵称所需字的、模型 token 支持的读音；多音字组合最多 64 种，无法转换或超过上限时保留旧称呼并提示换用常用汉字，不联网、不加载完整字典到常驻内存。

前台开启麦克风即可直接聊天，默认后台仅 KWS。七条 KWS 口令命中会结束旧 ASR 请求、清空旧句并恢复说话；后台也直接开启 ASR，不拉起 App。暂停时 ASR 停止，因此闭嘴/退下/关麦这些 ASR 操作不可用，只能通过 KWS 恢复后操作；关闭麦克风后 KWS 也停止，只能手动开启。后台收到恢复口令或已开启悬浮聊天后可继续 ASR 操作。通知停止与麦克风按钮只停止采音并保留当前回答；× 确认停止所有工作并退出进程。

ASR 确定句命中退下/退出/下去吧与当前昵称后，服务发出 ACTION_MAIN + CATEGORY_HOME 返回系统桌面，调用失败时不播报「我退下了」。默认暂停识别和旧回复，保留 KWS、话题和 Response ID；后台悬浮聊天开启且授权时可继续当前聊天。确认 TTS 不因默认后台暂停而被截断。后台说任一七条 KWS 口令都直接恢复语音聊天，不拉起 MainActivity，也不改变页面设置。

已移除「后台唤醒打开页面」设置和语音拉起 App 的路径。七条本地口令统一恢复说话，后台可直接请求 AI，无需显示小窗。后台悬浮聊天开关只控制窗口显示及离开主页面时是否自动延续聊天；首次开启小窗时仍需要授权“显示在其他应用上层”。不打开小窗也可以在麦克风开启后通过任一口令恢复后台聊天。

「后台悬浮聊天」默认关闭，保存到 SharedPreferences 的 `background_chat`。开启后若没有授权，会打开系统悬浮窗授权页；不自动打开原本关闭的麦克风。现有录音服务退到后台后显示缩小版聊天窗口，上方同步聊天记录与流式文字，下方显示可点击开关的麦克风按钮、音量波形和暂停/恢复按钮。长按标题栏或空白边缘可拖动，聊天区可滚动，四角不显示标记，拖动四角仍可缩放；默认 320 × 360 dp，最小 240 × 220 dp，最大 640 × 720 dp，始终限制在屏幕可用范围。位置和尺寸保存到 SharedPreferences 的 `chat_overlay_window`，服务重启或下次打开时恢复，屏幕尺寸变化时自动收回可见范围。悬浮窗沿用主页面的浅色/深色渐变，不抢输入焦点，不创建第二路录音或第二个 Service。返回前台、关闭悬浮开关、撤销授权或退出应用会移除窗口；关麦只停止 KWS/ASR 采音，保留通知和后台小窗；服务同时取消窗口回调和权限监听，避免持有 Activity 或泄漏窗口。悬浮窗没有授权或添加失败时不显示窗口，不影响本地口令恢复后台 ASR；后台仍使用现有 70 秒无人说话暂停、语音打断及本地记录逻辑。仅支持用户已从前台启动的麦克风前台服务延续运行，不保证强制停止/系统杀进程、锁屏或厂商后台限制下的可用性；需在目标车机和蓝牙设备验证。

`AsrController` 负责启停规则，不是第二个 Service；`DoubaoAsrRecognizer` 实际识别。KWS 快照的 `asrRequested` 仍只表示业务需求，实际连接状态与错误由 `AsrSnapshot` 独立发布。

此项目要求 `android.hardware.type.automotive`，用于原生 Android Automotive/Android 车机，不是手机投屏的 Android Auto App。当前仍仅用于停车时验证，未做驾驶中可用性与车机发布审核。

## 代码入口

按职责划分包，界面、ViewModel、Android 服务、数据类型和语音引擎配置分别存放；测试目录与对应生产代码包一致。

```text
com.spake.brony.doubaoassistant/
├── ui/                 PermissionActivity.kt、MainActivity.kt、AppearanceTheme.kt、DesignDimensions.kt
│   ├── base/           BaseActivity.kt
│   ├── binding/        VoiceBindingAdapters.kt
│   └── widget/         渐变、对话、状态波形、字幕开关
├── viewmodel/          KwsViewModel.kt、PermissionViewModel.kt（各自同文件包含 UiState）
│   └── base/           BaseViewModel.kt
├── service/            KwsService.kt（生命周期、指令分发与组件协调）
│   └── notification/   VoiceNotification.kt（通知渠道、内容与控制入口）
├── conversation/       ConversationCoordinator.kt（判句后请求、取消与多轮状态）
├── data/model/         语音状态、对话内容、外观设置
├── data/repository/    KwsRepository.kt、AsrRepository.kt、ConversationRepository.kt、ChatHistoryRepository.kt、AssistantPreferences.kt
├── data/local/         ChatHistoryDatabase.kt（RoomDatabase）
│   ├── entity/         ChatTopicEntity.kt、ChatMessageEntity.kt
│   └── dao/            ChatHistoryDao.kt
├── data/network/       DoubaoResponsesClient.kt、ResponseStreamParser.kt
└── speech/
    ├── SpeechSdkEnvironment.kt（ASR/TTS 共用一次初始化，不保存 Context）
    ├── kws/            KwsModel.kt、KwsKeywords.kt、KwsRecognizer.kt
    ├── audio/          MicrophoneRecorder.kt、VoiceLevelMeter.kt、VoiceCuePlayer.kt
    ├── tts/            DoubaoTtsPlayer.kt、StreamingTts.kt
    └── asr/            AsrController.kt、DoubaoAsrRecognizer.kt、AsrTranscript.kt
```

- `automotive/src/main/java/com/spake/brony/doubaoassistant/speech/kws/KwsModel.kt`：sherpa 模型加载配置，不是界面 ViewModel；包含模型路径、16 kHz 采样率、CPU 线程数、关键词得分和门限。
- `automotive/src/main/java/com/spake/brony/doubaoassistant/speech/asr/AsrController.kt`：纯 Kotlin 控制规则，前台免唤醒、后台语音恢复、静音和显式恢复；只由服务主线程调用，不持有 Context、页面或音频。
- `automotive/src/main/assets/kws/keywords.txt`：带声调的拼音声母/韵母序列及中文标签；不能直接把中文整句当作 token。
- `automotive/src/main/java/com/spake/brony/doubaoassistant/data/model/KwsCommand.kt`、`AsrCommand.kt`：分别定义唤醒/恢复和 ASR 操作枚举；`speech/asr/AsrCommands.kt` 维护操作关键词、固定回复资源。`AssistantMode.kt` 是助手模式，KwsUiState 仍放在 KwsViewModel 同文件上方。
- `automotive/src/main/java/com/spake/brony/doubaoassistant/service/KwsService.kt`：保留一个 Service，负责生命周期、控制指令、前后台规则和组件协调；不再直接创建 AudioRecord、管理音频效果、调用 sherpa 推理 API 或拼装通知。采音、KWS 与 ASR 仍共用一条串行工作线程、一份 PCM，不增加 Service 或常驻线程，不改变聊天与数据库规则。
- `automotive/src/main/java/com/spake/brony/doubaoassistant/speech/audio/MicrophoneRecorder.kt`：创建 VOICE_COMMUNICATION / 16 kHz / 单声道 PCM16 录音，管理 AEC/降噪、阻塞读取与资源释放。主线程只发停止信号，录音线程在 finally 中关闭效果和 AudioRecord；初始化前的 stop 会阻止旧任务重新开麦，停止与释放可重复调用。
- `automotive/src/main/java/com/spake/brony/doubaoassistant/speech/kws/KwsRecognizer.kt`：管理本地模型、当前称呼的关键词流、解码与命中重置；改名时只替换流，不重启录音或模型。创建、推理、流替换和释放都在录音工作线程，命中回调返回关键词及对应称呼，服务继续检查代次以忽略旧回调。
- `automotive/src/main/java/com/spake/brony/doubaoassistant/service/notification/VoiceNotification.kt`：仅使用 Application Context，构建原有通知渠道、Logo、手动打开页面入口和麦克风/暂停按钮；麦克风或 mediaPlayback 前台服务类型仍由 Service 决定。
- `automotive/src/main/java/com/spake/brony/doubaoassistant/data/repository/KwsRepository.kt`：进程内最新状态和页面可见性通道；只保存快照及布尔值，不保存 Context、Service、ViewModel、回调或音频。服务观察可见性的协程在 `onDestroy` 取消。
- `automotive/src/main/java/com/spake/brony/doubaoassistant/viewmodel/base/BaseViewModel.kt`：所有 ViewModel 的公共父类，继承 AndroidX `ViewModel`；当前不包含业务逻辑。
- `automotive/src/main/java/com/spake/brony/doubaoassistant/viewmodel/KwsViewModel.kt`：继承 `BaseViewModel`，保留页面自己的只读 `StateFlow<KwsUiState>`；用 `viewModelScope` 接收数据层状态，ViewModel 清理时自动取消订阅。不持有 Context、Resources、Activity、View、Service 或服务回调，也不屏蔽 `StaticFieldLeak`。
- `automotive/src/main/java/com/spake/brony/doubaoassistant/ui/base/BaseActivity.kt`：所有 Activity 的公共父类，继承 `AppCompatActivity`；统一原设计的全屏显示与手势唤出系统栏，不包含语音业务逻辑。
- `automotive/src/main/java/com/spake/brony/doubaoassistant/ui/PermissionActivity.kt`：权限门禁与永久拒绝恢复；`PermissionViewModel` 不持有 Activity/Context，只保存权限 UI 状态。
- `automotive/src/main/java/com/spake/brony/doubaoassistant/ui/MainActivity.kt`：继承 `BaseActivity`，使用 `by viewModels()`，负责绑定布局、权限复查、文案解析、设置和发送语音控制指令；没有 `bindService`、`ServiceConnection` 或 Service 实例引用。`onStart/onStop` 上报页面可见性，让服务切换聊天规则，不关闭 KWS；只有权限入口的首次进入指令或用户主动开始操作启动录音，页面重建不会重复启动。
- `automotive/src/main/res/layout/activity_main.xml`：Data Binding 订阅 ViewModel 的 StateFlow，由页面使用当前资源解析文案；设置 `lifecycleOwner` 后自动刷新，无需 `findViewById` 或手动 `render()`。

状态传递路径为 `KwsService → KwsRepository → KwsViewModel.uiState → Data Binding`，Service 不知道页面或 ViewModel。数据层只有最新状态，重新打开页面直接读取当前快照，无需重新绑定服务。

`command` 是最近命中的事件，`detectionCount` 在服务实例内递增；StateFlow 是状态通道，不是逐条指令队列，会合并快速更新且重放最新值。AI 请求、取消和 TTS 控制在服务/业务层处理，不靠页面订阅快照触发动作，避免漏执行或页面重开时重复请求。监听状态只在进程内保存，进程结束后回到未监听；聊天文字和 AI 回复的 Response ID 保存到 SQLite，发送 ID 以数据库查询为准。

RESUME 仅由 KWS 执行，会更新 ASR 请求代次以丢弃旧回调；操作指令仅由 ASR 的确定句执行。STOP_REPLY 暂停识别与旧回复后说「好的」，RETURN_HOME 返回桌面后说「我退下了」，这两类固定句不经过 Responses，不新增 AI 请求，记录为角色 2，不携带 Response ID。CLOSE_MICROPHONE 只关闭输入，保留当前 AI 请求和 TTS，没有固定确认或系统成功消息。

ASR 已共用当前 `AudioRecord` 的 PCM 数据，不另开录音。当前没有唤醒前音频缓存；后台先说恢复口令，等「好的」提示音后再问问题，避免丢掉与唤醒词连说的问题开头。

## 模型与第三方来源

- Android SDK：[`sherpa-onnx-1.13.8.aar`](https://github.com/k2-fsa/sherpa-onnx/releases/tag/v1.13.8)，本地依赖位于 `automotive/libs/`，包含 ARM64、ARMv7、x86、x86_64 四种 ABI。
- 模型：[`sherpa-onnx-kws-zipformer-zh-en-3M-2025-12-20`](https://github.com/k2-fsa/sherpa-onnx/releases/tag/kws-models)。采用 chunk-8 的 INT8 encoder/joiner 和 FP32 decoder，模型文件约 5.3 MB。官方标注 chunk-8 模型块延迟为 160 ms，这不是整句命中的总延迟。
- 关键词格式和模型说明：[官方文档](https://k2-fsa.github.io/sherpa/onnx/kws/pretrained_models/index.html)。新增短语时应同时修改关键词文件和 `KwsCommand.fromKeyword`，再运行词表测试。
- 框架 Apache-2.0 许可证保存在 `automotive/src/main/assets/kws/APACHE-2.0.txt`。
- 模型发布者的 Apache-2.0 模型卡原文保存在 `automotive/src/main/assets/kws/MODEL_CARD.md`，来源为 [ModelScope](https://modelscope.cn/models/pkufool/icefall-kws-zipformer-zh-en-3M-2025-12-20)。

已按 GitHub 官方 release digest 校验下载文件：

| 下载文件 | SHA-256 |
| --- | --- |
| sherpa-onnx-1.13.8.aar | `633c24321e06b1fe79feafa03ea16cbc0f8a286641e2da3559bac91bdb13bd96` |
| sherpa-onnx-kws-zipformer-zh-en-3M-2025-12-20.tar.bz2 | `68447f4fbc67e70eee3a93961f36e81e98f47aef73ce7e7ca00885c6cd3616a6` |

## 验证

```sh
# 正常 debug/release 构建会分别生成两种版本
./gradlew :automotive:assembleDebug
./gradlew :automotive:assembleRelease
# 两种构建的主机测试与 Lint
./gradlew :automotive:testGeneralDebugUnitTest :automotive:testPersonalDebugUnitTest :automotive:lintGeneralDebug :automotive:lintPersonalDebug
# 连接设备后运行
./gradlew :automotive:connectedGeneralDebugAndroidTest
```

最后一条需要连接支持 ARM64 应用的 Android 车机或模拟器，会验证 APK 内的模型/JNI 初始化、静音输入不误唤醒，以及页面重建后保留同一个 ViewModel、不会自动启动录音、页面生命周期正确更新可见性。单元测试覆盖前台免唤醒与 KWS 显式重开 ASR、默认后台只监听关键词、七条口令后台直接恢复 ASR 且不启动页面、「退下 → 桌面 → 恢复说话 → 原话题继续」循环、静音跨页面进出保持、显式恢复和停止监听后的指令无效，以及所有关键词 token 存在于模型词表、所有标签都有事件映射。

内存相关回归：单元测试覆盖状态更新、错误恢复、ViewModel 清理后订阅数归零、新页面读取最新状态及服务错误状态保留；Robolectric 回归验证服务销毁后不再响应页面可见性或设置状态。设备测试模拟后台状态更新与页面重建，不实际录音。协程测试库与现有运行时同为 1.7.3；新增 Robolectric 4.16.1 仅用于主机测试，不打包进 APK。此项目不通过屏蔽内存泄漏警告来通过检查，并将 Lint 的 `StaticFieldLeak` 升级为错误。Lint 和订阅释放测试不代替真实车机长时间启停、页面重建和 Java/Native 内存曲线验证。

本次 UI 接入验证（2026-10-10）：debug APK 和 instrumentation 测试 APK 编译成功；本地测试覆盖权限门禁、同意/拒绝回调、永久拒绝的设置跳转、权限撤销、设置保存、聊天静音、结束状态和订阅释放，并生成原生界面截图。Android lint 无错误；不忽略告警，依赖版本等警告仍保留。

之前同版本 sherpa-onnx 主机解码实际加载了项目中的量化模型，五条短语在合成普通话音频上均准确命中一次；静音和「今天下午天气怎么样」没有触发。主机验证不等于 Android JNI/麦克风实测；当前没有连接设备，instrumentation 测试尚未执行。

调试 APK：
- 通用版：`automotive/build/outputs/apk/general/debug/automotive-general-debug.apk`，首次配置为空。
- 自用版：`automotive/build/outputs/apk/personal/debug/automotive-personal-debug.apk`，首次预填本机凭证，请勿公开分发。

Release 默认生成 `automotive/build/outputs/apk/{general,personal}/release/automotive-{general,personal}-release-unsigned.apk`，安装前需要签名；Android Studio Generate Signed APK 中也可分别选择两种 flavor。历史 `automotive/release/automotive-release.apk` 不会被这些任务覆盖，不代表最新构建。

录音使用 `VOICE_COMMUNICATION`，设备提供回声消除/降噪时尝试启用，AEC 状态保留在快照中，新设计页不额外插入调试控件。默认门限 `0.25` 仅为初始值，未经过实车标定。应使用真实麦克风，在空调、路噪、音乐、多人说话和 TTS 播放条件下检查漏唤醒、误唤醒和打断延迟；系统报告 AEC 已开启也不能保证能消除所有扬声器回声。
