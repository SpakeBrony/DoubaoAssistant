# DoubaoAssistant

面向吉利星越 L 的 Android 车载语音助手，集成本地唤醒、豆包流式语音识别、AI 对话、语音播报与车辆控制。

> **车型限制：车辆控制功能仅限吉利星越 L，不支持其他车型。** 不同年款、配置和固件的接口及权限可能不同，具体能力需要在目标车机上确认。

## 功能概览

- **语音交互**：本地唤醒、流式识别、AI 连续对话、流式播报和语音打断。
- **车辆控制**：空调、温度、风速、内外循环、后窗加热、座椅加热与通风，以及车窗、天窗和遮阳帘开度。
- **系统操作**：打开应用、发起导航、电话呼叫、QQ 音乐控制和音量调节。
- **信息查询**：中国城市天气、时间、日期和星期查询。
- **交互与记录**：文字输入、后台悬浮聊天、本地聊天记录、浅深色主题及助手称呼设置。

完整功能、运行流程图和使用限制见 [项目说明](项目说明.md)。

## 运行要求

| 项目 | 要求 |
| --- | --- |
| 当前 APK 的设备要求 | 支持 ARM64 应用的 Android 车机，Android 9（API 28）及以上 |
| 平台声明 | 当前要求 `android.hardware.type.automotive`，面向原生车机应用 |
| 车辆控制 | 吉利星越 L，且固件提供所需 ECARX 接口和权限 |
| 网络 | 语音识别、AI 对话和语音合成需要联网 |
| 凭证 | 语音 App ID、Access Token，以及火山方舟 Ark API Key |
| 开发环境 | Android Studio、JDK 17 或以上兼容版本、Android SDK 36；使用仓库内 Gradle Wrapper |

当前只打包 ARM64 是项目的构建选择：本地 sherpa-onnx 库包含 ARM32、ARM64、x86 和 x86_64，豆包语音 SDK 包含 ARM32 和 ARM64。其他架构未从原始库中删除，而是通过 `abiFilters` 限制了 APK 的打包范围；详细说明见 [CPU 架构兼容性](项目说明.md#cpu-架构兼容性)。

## 快速开始

### 1. 获取服务凭证

在豆包语音旧版控制台获取同一应用的 **App ID + Access Token**，开通所需 ASR、TTS 服务；在火山方舟获取 **Ark API Key**，开通对应模型。

官方入口和具体步骤见 [凭证与服务配置](项目说明.md#凭证与服务配置)。

### 2. 配置与构建

自用构建：将 `doubao-asr.properties.example` 复制为根目录的 `doubao-asr.properties`，填写三项配置：

```properties
appId=你的语音AppID
accessToken=你的语音AccessToken
arkApiKey=你的方舟APIKey
```

```sh
./gradlew :automotive:assemblePersonalDebug
```

分发给其他人时使用通用版，由对方安装后填写自己的凭证：

```sh
./gradlew :automotive:assembleGeneralDebug
```

`doubao-asr.properties` 已被 Git 忽略。**自用版 APK 会嵌入本机凭证，不应公开分发。**

### 3. 安装与使用

1. 从 `automotive/build/outputs/apk/` 下对应版本目录取得 APK，安装到目标车机。
2. 首次打开时填写或确认凭证，保存并授予麦克风权限。
3. 进入主页后直接说话；默认退到后台后，通过“你好豆包”等口令恢复聊天。
4. 需要后台悬浮窗时，在设置中开启“后台悬浮聊天”并授予悬浮窗权限。

## 构建版本

| 版本 | 用途 | 凭证处理 |
| --- | --- | --- |
| `personal` | 自用，默认版本 | 从本机配置文件读取凭证并嵌入 APK，用于首次预填 |
| `general` | 通用分发 | 不嵌入开发者凭证，安装后由使用者填写 |

两种版本都包含 `debug` 和 `release`，共四种 Build Variant。Release 启用代码混淆和资源压缩，当前命令行构建还需要自行签名后安装。完整命令和区别见 [构建版本](项目说明.md#构建版本)。

## 文档索引

| 文档 | 内容 |
| --- | --- |
| [项目说明](项目说明.md) | 功能、流程图、Key 获取、版本区别与车型限制 |
| [技术说明](docs/ARCHITECTURE.md) | 模块职责、线程与数据流、协议配置、存储规则及已知限制 |
| [贡献指南](CONTRIBUTING.md) | 代码风格、修改范围、凭证处理和编译约定 |
| [代理协作约定](AGENTS.md) | 本项目的自动化测试与行为保留要求 |

## 第三方来源

- 车控参考：[Schum-io/Geely_Monjaro_oneOS_Hvac](https://github.com/Schum-io/Geely_Monjaro_oneOS_Hvac)。本项目的车控适用范围仍仅限星越 L。
- 本地唤醒：[k2-fsa/sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx)，模型和许可证说明位于 [KWS 资源目录](automotive/src/main/assets/kws/)。
- 昵称拼音：[mozillazg/pinyin-data](https://github.com/mozillazg/pinyin-data)，来源见 [pinyin-SOURCE.md](automotive/src/main/assets/kws/pinyin-SOURCE.md)。
- 天气与地点：[Open-Meteo](https://open-meteo.com/) 和 [GeoNames](https://www.geonames.org/)，使用前应核对各自的服务条款。

第三方组件和资源的许可证仅适用于相应内容；本仓库尚未提供项目自身的根目录 `LICENSE`。

## 验证约定

功能由使用者在目标设备上验证。除非明确要求，不新增、恢复或运行自动化测试、测试脚本和自检程序；可以运行不执行测试的编译任务。编译通过不代表云端鉴权或实车控制已验证通过。
