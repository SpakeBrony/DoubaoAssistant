# 豆包 SDK 的其余接口由 AAR 自带的 consumer rules 保护。
# SDK 实现类在 AAR 中已重命名，按接口保留，不依赖不存在的 SpeechEngineImpl 类名。
-keep class * implements com.bytedance.speech.speechengine.SpeechEngine { *; }

# sherpa-onnx 的 AAR 未提供规则；JNI 依赖配置/结果的类名、字段和构造签名。
-keep class com.k2fsa.sherpa.onnx.** { *; }

# XML 中的自定义 View 通过反射创建。
-keep public class com.spake.brony.doubaoassistant.ui.** extends android.view.View {
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
}

# Room / OkHttp 使用各自的 consumer rules，无需整包保留。
# 保留栈位置，以及警告、错误和请求 logid；仅移除 verbose/debug。
-keepattributes SourceFile,LineNumberTable
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
}

# OkHttp 可选 TLS 适配器，应用未引入这些独立 Provider。
-dontwarn org.bouncycastle.jsse.BCSSLParameters
-dontwarn org.bouncycastle.jsse.BCSSLSocket
-dontwarn org.bouncycastle.jsse.provider.BouncyCastleJsseProvider
-dontwarn org.conscrypt.Conscrypt$Version
-dontwarn org.conscrypt.Conscrypt
-dontwarn org.conscrypt.ConscryptHostnameVerifier
-dontwarn org.openjsse.javax.net.ssl.SSLParameters
-dontwarn org.openjsse.javax.net.ssl.SSLSocket
-dontwarn org.openjsse.net.ssl.OpenJSSE
