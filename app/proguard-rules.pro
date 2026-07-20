-keep class kotlinx.serialization.** { *; }
-keepclassmembers class app.synapse.localllm.** {
    @kotlinx.serialization.Serializable *;
}

# embedded_llama.cpp resolves this class and its native methods by their JNI names.
-keep class app.synapse.localllm.data.runtime.embedded.EmbeddedLlamaEngine { *; }
