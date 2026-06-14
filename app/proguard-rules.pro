-keep class kotlinx.serialization.** { *; }
-keepclassmembers class app.synapse.localllm.** {
    @kotlinx.serialization.Serializable *;
}

