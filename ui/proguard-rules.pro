# ============================================================================
# KAPO VPN — R8/ProGuard keep rules for the release build.
#
# The AmneziaWG tunnel is a native (Go/C) library bound to Java by EXACT class
# and method names via static JNI (Java_org_amnezia_awg_...). If R8 renames or
# strips these classes the app builds fine but crashes at runtime with
# UnsatisfiedLinkError / ClassNotFound the moment you tap Connect. The debug
# build isn't minified, so this only bites the release/googleplay build — keep
# these rules in place.
# ============================================================================

# --- Native tunnel binding: never rename or strip -------------------------
-keep class org.amnezia.awg.backend.** { *; }
-keep class org.amnezia.awg.crypto.** { *; }
-keep class org.amnezia.awg.config.** { *; }
-keep class org.amnezia.awg.util.** { *; }

# The VpnService subclass is referenced from the manifest + system.
-keep class org.amnezia.awg.backend.GoBackend$VpnService { *; }

# Any class that declares native methods (belt-and-suspenders across modules).
-keepclasseswithmembernames class * {
    native <methods>;
}

# --- Missing compile-only annotations (JSR-305 / FindBugs) ----------------
# org.amnezia.awg.util.NonNullForAll is annotated with javax.annotation.meta.*
# (TypeQualifierDefault), which is a compile-only dependency not present at
# runtime. R8 full-mode turns the missing reference into a hard error, so tell
# it not to warn. This was the ":ui:minifyGoogleplayWithR8" failure.
-dontwarn javax.annotation.**
-dontwarn org.jetbrains.annotations.**
-dontwarn org.codehaus.mojo.animal_sniffer.**

# --- Kotlin coroutines: guard against over-aggressive shrinking -----------
-keepclassmembers class kotlinx.coroutines.** { *; }
-dontwarn kotlinx.coroutines.**

# --- Enums used across the JNI/config boundary keep their valueOf/values ---
-keepclassmembers enum org.amnezia.awg.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# --- Parcelables (config/tunnel models) -----------------------------------
-keepclassmembers class org.amnezia.awg.** implements android.os.Parcelable {
    public static final ** CREATOR;
}

# ViewBinding / DataBinding generated classes are kept by AGP defaults.
