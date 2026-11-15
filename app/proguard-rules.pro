#############################################
# General / Debugging
#############################################

#-keepattributes SourceFile,LineNumberTable
#-renamesourcefileattribute SourceFile

-keepattributes Signature
-keepattributes *Annotation*
-keepattributes EnclosingMethod
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault

-dontobfuscate

# Release builds only keep warnings and errors: strip verbose, debug and info logs
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}


#############################################
# Kotlin / Coroutines / Serialization
#############################################

-keep class kotlin.** { *; }
-dontwarn kotlin.**

-keep class kotlinx.** { *; }

# Serializable Companion handling
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
}

-if @kotlinx.serialization.Serializable class ** {
    static **$* *;
}
-keepclassmembers class <2>$<3> {
    kotlinx.serialization.KSerializer serializer(...);
}

-if @kotlinx.serialization.Serializable class ** {
    public static ** INSTANCE;
}
-keepclassmembers class <1> {
    public static <1> INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}

-keep class kotlinx.serialization.** { *; }
-keep interface kotlinx.serialization.internal.GeneratedSerializer { *; }
-keepclassmembers class * implements kotlinx.serialization.internal.GeneratedSerializer {
	*** typeParametersSerializers(...);
	*** childSerializers(...);
}
-keepclassmembers interface kotlinx.serialization.internal.GeneratedSerializer {
	*** typeParametersSerializers(...);
	*** childSerializers(...);
}
            
#############################################
# Core App / Extensions
#############################################

-keep class nox.kiwami.** { *; }
-keep class nox.kiwami.download.DownloadsManager { *; }

-keep class eu.kanade.** { *; }
-keep class uy.kohesive.injekt.** { *; }

-keepclassmembers class uy.kohesive.injekt.api.FullTypeReference {
    <init>(...);
}

# Keep NanoHTTPD for anime extensions and local proxy servers
-keep class fi.iki.elonen.** { *; }
-dontwarn fi.iki.elonen.**
-keep class org.nanohttpd.** { *; }
-dontwarn org.nanohttpd.**

#############################################
# Networking (OkHttp + Okio)
#############################################

-keep class okhttp3.** { *; }
-dontwarn okhttp3.**

-keep class okio.** { *; }
-dontwarn okio.**

-keep class com.squareup.zstd.** { *; }
-dontwarn com.squareup.zstd.**


#############################################
# Android / Jetpack
#############################################

-keep class androidx.preference.** { *; }

# WorkManager database
-keep class androidx.work.impl.WorkDatabase_Impl { *; }


#############################################
# Gson / JSON / HTML Parsing
#############################################

-keep class com.google.gson.** { *; }
-keep class com.google.gson.reflect.TypeToken { *; }

-keep class org.jsoup.** { *; }
-keepclassmembers class org.jsoup.nodes.Document { *; }

-dontwarn org.json.**
-keep class org.json.** { *; }


#############################################
# QuickJS / Native / Unsafe
#############################################

-keep,allowoptimization class app.cash.quickjs.** { public protected *; }

-keep class rx.internal.util.unsafe.** { *; }

-dontwarn sun.misc.Unsafe
-dontwarn org.graalvm.nativeimage.**
-dontwarn com.oracle.svm.core.annotate.**



# Keep RxJava unsafe internals
-keep class rx.internal.util.unsafe.** { *; }

# Keep fields (VERY IMPORTANT)
-keepclassmembers class rx.internal.util.unsafe.** {
    long producerIndex;
    long consumerIndex;
}

# Keep all rx internal operators (safe side)
-keep class rx.internal.** { *; }

# Prevent stripping Unsafe usage
-dontwarn sun.misc.Unsafe

#############################################
# Charts (AAChart)
#############################################

-keep class com.github.aachartmodel.** { *; }
-dontwarn com.github.aachartmodel.**

#############################################
# libtorrent4j
#############################################
-keep class org.libtorrent4j.swig.libtorrent_jni { *; }

#############################################
# FFmpegKit (com.antonkarpenko)
#############################################
-keep class com.antonkarpenko.ffmpegkit.** { *; }
-dontwarn com.antonkarpenko.ffmpegkit.**

#############################################
# WebGPU Viewer (ca.mpreg:webgpuviewer)
#############################################
-keep class ca.mpreg.webgpuviewer.** { *; }
-dontwarn ca.mpreg.webgpuviewer.**

#############################################
# libass (io.github.peerless2012:ass-media / ass-kt)
#############################################
# libass is native code that calls back into Kotlin/Java classes, methods and fields through JNI.
# R8 cannot see those calls, so it removed or changed them in release builds and the native side
# aborted the whole process as soon as the player started (screen rotated, then the app restarted).
-keep class io.github.peerless2012.** { *; }
-keepclassmembers class io.github.peerless2012.** { *; }
-dontwarn io.github.peerless2012.**

# Any class with native methods may be called back from native code, so R8 must leave it alone
-keepclasseswithmembers,includedescriptorclasses class * {
    native <methods>;
}

#############################################
# Cronet / protobuf
#############################################
# R8 class merging breaks protobuf-lite when Cronet reads its flags file, which aborts ART on release builds
-keep class com.google.protobuf.** { *; }
-keep class org.chromium.net.** { *; }
-dontwarn org.chromium.net.**

#############################################
# ML Kit / Firebase component registrars
#############################################
# ML Kit and Firebase create their components by class name through reflection (ComponentDiscovery).
# R8 removed the no-argument constructor of CommonComponentRegistrar, so ML Kit failed to start and the
# release build crashed on launch ("NoSuchMethodException: CommonComponentRegistrar.<init>").
-keep class * implements com.google.firebase.components.ComponentRegistrar { *; }
-keep class com.google.mlkit.common.** { *; }
-dontwarn com.google.mlkit.**

#############################################
# ML Kit Text Recognition
#############################################
-keep class com.google.mlkit.vision.** { *; }
-dontwarn com.google.mlkit.vision.**
-keep class com.google.android.gms.internal.mlkit_vision_text** { *; }
-dontwarn com.google.android.gms.internal.mlkit_vision_text**
