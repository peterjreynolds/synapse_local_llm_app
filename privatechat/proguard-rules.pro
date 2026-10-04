# libsignal 0.101.0 resolves store callbacks through JNI using the literal
# NativeHandleGuard$Owner return descriptor. Its consumer rules keep the callback
# methods but allow this descriptor type to be renamed, breaking the first peer send.
# Scope: this one JNI descriptor, not the complete library. Owner: Synapse Private.
# Remove when the pinned library preserves it and the packaged-APK regression passes.
-keep interface org.signal.libsignal.internal.NativeHandleGuard$Owner { *; }

# WebRTC 144.7559.09 ships JNI callbacks but no consumer ProGuard rules.
# Scope: the pinned third-party JNI API, never application classes. Owner: Private calls.
# Remove when upstream supplies verified consumer rules and minified native call probes pass.
-keep class org.webrtc.** { *; }
