# ═══════════════ 鸿胪寺 release 混淆规则 ═══════════════
# 目标：release 版（debuggable=false、可混淆、可裁资源）能正常跑。
# 策略：**我们自己那两个包整体保留**——代码量不大，换来的是绝不因混淆出怪问题
# （ViewModel 的反射构造、Compose 顶层函数、@SerialName 中文名都安全）；
# 第三方（AndroidX / Compose / OkHttp）交给它们自带的 consumer 规则。

# 注解要留着：kotlinx.serialization 靠 @SerialName / @Serializable 生成和绑定字段名
-keepattributes RuntimeVisibleAnnotations,RuntimeInvisibleAnnotations,AnnotationDefault,Signature,InnerClasses,EnclosingMethod

# 自己的代码：UI / 后端 两包整体保留
-keep class UI.** { *; }
-keep class 后端.** { *; }

# 本地模型层 = llama-android 成熟绑定（dev.ffmpegkit.llama.*）。
# 它的 LlamaJNI 全是 native 方法，类名/方法名必须原样保留，
# 否则 R8 改名后 libllama_jni.so 里的符号对不上 → 一加载就 UnsatisfiedLinkError。
-keep class dev.ffmpegkit.llama.** { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}

# ── kotlinx.serialization ──
-keepclassmembers class **$$serializer { *; }
-keepclasseswithmembers class * {
    kotlinx.serialization.KSerializer serializer(...);
}
-if @kotlinx.serialization.Serializable class **
-keep class <1> { *; }

# ── 第三方库不用警告 ──
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
