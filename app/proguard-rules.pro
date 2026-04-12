# Kotlin Serialization
-keepattributes *Annotation*, SerializationSignature
-keep class kotlinx.serialization.Serializable { *; }
-keepclassmembers class kotlinx.serialization.Serializable {
    *** Companion;
    static final kotlinx.serialization.KClass serializer;
}
-keepclassmembers class ** {
    static final *** serializer(...);
}

# Keep Serializable DTOs/models used across the app (serialization entry points)
-keep class com.tamimarafat.ferngeist.core.model.** { *; }
-keep class com.tamimarafat.ferngeist.feature.serverlist.helper.** { *; }
-keepclassmembers class com.tamimarafat.ferngeist.** {
    static final ** serializer(...);
}

# Room
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-keep @androidx.room.Dao interface *
-dontwarn androidx.room.**

# Hilt / Dagger
-dontwarn dagger.hilt.**
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
-keep class * extends dagger.hilt.android.internal.managers.ViewComponentManager$FragmentContextWrapper { *; }

# Ktor
-keep class io.ktor.** { *; }
-keep class kotlinx.coroutines.** { *; }
-dontwarn io.ktor.**

# Compose
-keep class androidx.compose.** { *; }
-dontwarn androidx.compose.**

# ACP SDK
-keep class com.agentclientprotocol.** { *; }
-dontwarn com.agentclientprotocol.**

# Markdown renderer
-keep class com.mikepenz.** { *; }
-dontwarn com.mikepenz.**

# Preserve line numbers for crash logs
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Remove logging in release
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
}