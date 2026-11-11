-keep class kotlinx.serialization.** { *; }
-keepclassmembers class app.synapse.localllm.** {
    @kotlinx.serialization.Serializable *;
}

# embedded_llama.cpp resolves this class and its native methods by their JNI names.
-keep class app.synapse.localllm.data.runtime.embedded.EmbeddedLlamaEngine { *; }

# WebRTC JNI_OnLoad resolves this class by name; R8 cannot see the native reference.
-keep class org.jni_zero.JniInit { *; }

# WebRTC native code looks up Java callbacks and methods outside R8's static call graph.
# Preserve JNI method descriptors and native-to-Java entry points for call setup.
-keepclasseswithmembernames,includedescriptorclasses class org.webrtc.** {
    native <methods>;
}
-keepclasseswithmembers,includedescriptorclasses class * {
    @org.webrtc.CalledByNative <methods>;
}
-keepclasseswithmembers,includedescriptorclasses class * {
    @org.webrtc.CalledByNativeUnchecked <methods>;
}
