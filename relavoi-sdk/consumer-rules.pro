# Consumer ProGuard rules — apply when an app embeds the Relavoi SDK.

# Keep public API surface
-keep class com.relavoi.sdk.Relavoi { *; }
-keep class com.relavoi.sdk.RelavoiConfig { *; }
-keep class com.relavoi.sdk.RelavoiException { *; }
-keep class com.relavoi.sdk.session.** { *; }
-keep class com.relavoi.sdk.events.** { *; }
-keep class com.relavoi.sdk.verification.** { *; }

# Keep serializable classes intact (kotlinx-serialization)
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
