# ─────────────────────────────────────────────────────────────────────────────
# Stationly release (R8) keep rules
#
# Most third-party libs (Compose, Coil, Ktor, Firebase, Play Services) ship
# their own consumer ProGuard rules, so we only add what reflection-based code
# in OUR codebase needs. If you add a new library that uses reflection, or a new
# model class parsed via Gson, extend this file.
# ─────────────────────────────────────────────────────────────────────────────

# Keep generic signatures / annotations R8 needs to honour the rules below.
-keepattributes Signature, *Annotation*, InnerClasses, EnclosingMethod
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations

# ── Our data models ──────────────────────────────────────────────────────────
# core models are (de)serialised by BOTH kotlinx.serialization and Gson
# (e.g. LineStatus / FcmPayload in FcmMessagingService, predictions cache in
# SummaryViewModel). Gson reflects over field names at runtime, so the fields
# must survive obfuscation. Keep the whole model tree to be safe.
-keep class com.stationly.core.model.** { *; }
-keep class com.stationly.mobile.**.model.** { *; }

# ── Gson ─────────────────────────────────────────────────────────────────────
# Gson uses reflection + generic TypeTokens. Keep its support classes and any
# field annotated for serialization.
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
# Don't warn about Gson's optional desugar-only references.
-dontwarn sun.misc.**

# ── kotlinx.serialization ────────────────────────────────────────────────────
# The Kotlin serialization plugin (2.x) ships consumer rules, but pin the
# essentials explicitly so an SDK/plugin bump can't silently drop them.
-keepclassmembers class **$$serializer { *; }
-keepclasseswithmembers class * {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.stationly.**$$serializer { *; }
-keepclassmembers class com.stationly.** {
    *** Companion;
}

# ── Kotlin runtime niceties ──────────────────────────────────────────────────
-keep class kotlin.Metadata { *; }
-keepclassmembers class **.WhenMappings { <fields>; }
# Coroutines debug agent references — never present at runtime.
-dontwarn kotlinx.coroutines.**

# ── Enums (used in when-expressions / serialization) ─────────────────────────
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# ── Crash readability ────────────────────────────────────────────────────────
# Keep source file names + line numbers so obfuscated stack traces stay
# decodable (Play Console can also de-obfuscate from the mapping.txt that R8
# emits at build/outputs/mapping/<flavor>Release/mapping.txt).
-keepattributes SourceFile, LineNumberTable
-renamesourcefileattribute SourceFile
