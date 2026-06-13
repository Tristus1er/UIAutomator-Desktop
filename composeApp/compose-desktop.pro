# ProGuard rules for the release (shrunk/optimized) desktop build.
#
# Two things in this app break under a naive shrink:
#   1. kotlinx.serialization — the generated `$$serializer` classes and the
#      `Companion.serializer()` accessors are reached reflectively, so the
#      shrinker sees no static reference and removes/renames them. That makes
#      saving/loading sessions, the graph layout and the rule library throw at
#      runtime. Sessions and rules are the whole point of the app.
#   2. kotlinx.coroutines' Main dispatcher — `Dispatchers.Main.immediate` (used
#      all over AppState) is resolved through a ServiceLoader that finds the
#      Swing factory. Strip the factory or its service entry and the app dies
#      at first use with "Module with the Main dispatcher failed to initialize".
#
# The Compose Multiplatform Gradle plugin already supplies the Compose/Skiko
# keep rules; this file only adds the two above.

# Keep metadata the serialization runtime reads off classes / members.
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault,InnerClasses,EnclosingMethod,Signature

# ---------------------------------------------------------------------------
# kotlinx.serialization — canonical rules (from the library's README).
# ---------------------------------------------------------------------------

# Keep `Companion` object fields of serializable classes.
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
}

# Keep `serializer()` on companion objects (default and named) of serializable classes.
-if @kotlinx.serialization.Serializable class ** {
    static **$* *;
}
-keepclassmembers class <2>$<3> {
    kotlinx.serialization.KSerializer serializer(...);
}

# Keep `INSTANCE.serializer()` of serializable objects (e.g. `data object`s
# used for the sealed RuleAction / ScrollAmount hierarchies).
-if @kotlinx.serialization.Serializable class ** {
    public static ** INSTANCE;
}
-keepclassmembers class <1> {
    public static <1> INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}

# Belt-and-suspenders for this app's own model: keep every generated serializer
# and its companion outright, so neither shrinking nor obfuscation can touch the
# session / layout / rule classes.
-keep,includedescriptorclasses class com.salaun.tristan.uiautomator.**$$serializer { *; }
-keepclassmembers class com.salaun.tristan.uiautomator.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}

# ---------------------------------------------------------------------------
# kotlinx.coroutines — canonical rules + the Swing main dispatcher this app
# uses for `Dispatchers.Main.immediate`. (Desktop ProGuard does not auto-apply
# the consumer rules embedded in the coroutines jar.)
# ---------------------------------------------------------------------------

# ServiceLoader support for the Main dispatcher factory and its Swing impl.
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keep class kotlinx.coroutines.swing.SwingDispatcherFactory { *; }

# Volatile fields are updated through AtomicFieldUpdaters by name; don't mangle.
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}
-keepclassmembers class kotlin.coroutines.SafeContinuation {
    volatile <fields>;
}

# Debug-agent-only references that are absent in a normal run.
-dontwarn java.lang.instrument.ClassFileTransformer
-dontwarn java.lang.instrument.Instrumentation
-dontwarn sun.misc.SignalHandler
-dontwarn sun.misc.Signal
-dontwarn java.lang.ClassValue
-dontwarn org.codehaus.mojo.animal_sniffer.IgnoreJRERequirement
