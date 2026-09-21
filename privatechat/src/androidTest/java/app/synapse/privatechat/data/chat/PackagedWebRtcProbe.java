package app.synapse.privatechat.data.chat;

import android.app.Instrumentation;
import android.content.Context;
import android.os.Bundle;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;

/** Uses the installed APK's native library and callbacks without opening a microphone or camera. */
public final class PackagedWebRtcProbe extends Instrumentation {
    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); start(); }

    @Override public void onStart() {
        Bundle receipt = new Bundle();
        try {
            verifyPackagedNativeOffer();
            receipt.putString("result", "PASS: packaged WebRTC SHA-256 DTLS-SRTP offer and two-peer encrypted round trip without capture");
            finish(-1, receipt);
        } catch (Throwable failure) {
            // Native failures may contain routing/SDP material; report the class, never its message.
            receipt.putString("result", "FAIL: packaged WebRTC probe " + failure.getClass().getSimpleName());
            finish(1, receipt);
        }
    }

    private void verifyPackagedNativeOffer() throws Exception {
        ClassLoader loader = getTargetContext().getClassLoader();
        Class<?> factoryClass = loader.loadClass("org.webrtc.PeerConnectionFactory");
        Class<?> optionsClass = loader.loadClass("org.webrtc.PeerConnectionFactory$InitializationOptions");
        Object optionsBuilder = optionsClass.getMethod("builder", Context.class).invoke(null, getTargetContext());
        Class<?> loggableClass = loader.loadClass("org.webrtc.Loggable");
        Class<?> severityClass = loader.loadClass("org.webrtc.Logging$Severity");
        Object discardLogs = Proxy.newProxyInstance(loader, new Class<?>[]{loggableClass}, (proxy, method, args) -> null);
        optionsBuilder.getClass().getMethod("setInjectableLogger", loggableClass, severityClass)
                .invoke(optionsBuilder, discardLogs, severityClass.getField("LS_NONE").get(null));
        Object options = optionsBuilder.getClass().getMethod("createInitializationOptions").invoke(optionsBuilder);
        factoryClass.getMethod("initialize", optionsClass).invoke(null, options);
        Object builder = factoryClass.getMethod("builder").invoke(null);
        Object factory = builder.getClass().getMethod("createPeerConnectionFactory").invoke(builder);
        Object peer = null;
        Class<?> peerClass = loader.loadClass("org.webrtc.PeerConnection");
        try {
            Class<?> configClass = loader.loadClass("org.webrtc.PeerConnection$RTCConfiguration");
            Class<?> observerClass = loader.loadClass("org.webrtc.PeerConnection$Observer");
            Object config = configClass.getConstructor(List.class).newInstance(Collections.emptyList());
            Class<?> semanticsClass = loader.loadClass("org.webrtc.PeerConnection$SdpSemantics");
            configClass.getField("sdpSemantics").set(config, semanticsClass.getField("UNIFIED_PLAN").get(null));
            Object observer = Proxy.newProxyInstance(loader, new Class<?>[]{observerClass}, (proxy, method, args) -> null);
            peer = factoryClass.getMethod("createPeerConnection", configClass, observerClass).invoke(factory, config, observer);
            if (peer == null) throw new AssertionError("Native peer creation failed");
            Class<?> mediaTypeClass = loader.loadClass("org.webrtc.MediaStreamTrack$MediaType");
            Class<?> initClass = loader.loadClass("org.webrtc.RtpTransceiver$RtpTransceiverInit");
            Class<?> directionClass = loader.loadClass("org.webrtc.RtpTransceiver$RtpTransceiverDirection");
            Object init = initClass.getConstructor(directionClass).newInstance(directionClass.getField("RECV_ONLY").get(null));
            peerClass.getMethod("addTransceiver", mediaTypeClass, initClass)
                    .invoke(peer, mediaTypeClass.getField("MEDIA_TYPE_AUDIO").get(null), init);
            Class<?> sdpObserverClass = loader.loadClass("org.webrtc.SdpObserver");
            Class<?> constraintsClass = loader.loadClass("org.webrtc.MediaConstraints");
            CountDownLatch completed = new CountDownLatch(1);
            AtomicReference<String> description = new AtomicReference<>();
            Object sdpObserver = Proxy.newProxyInstance(loader, new Class<?>[]{sdpObserverClass}, (proxy, method, args) -> {
                if (method.getName().equals("onCreateSuccess")) {
                    description.set((String) args[0].getClass().getField("description").get(args[0]));
                    completed.countDown();
                } else if (method.getName().equals("onCreateFailure")) {
                    completed.countDown();
                }
                return null;
            });
            peerClass.getMethod("createOffer", sdpObserverClass, constraintsClass)
                    .invoke(peer, sdpObserver, constraintsClass.getConstructor().newInstance());
            if (!completed.await(15, TimeUnit.SECONDS)) throw new AssertionError("Native offer callback timed out");
            String sdp = description.get();
            if (sdp == null || !sdp.contains("UDP/TLS/RTP/SAVPF") ||
                    !sdp.matches("(?s).*a=fingerprint:sha-256 [0-9A-Fa-f]{2}(?::[0-9A-Fa-f]{2}){31}\\r?\\n.*")) {
                throw new AssertionError("Authenticated media was not offered");
            }
            verifyEncryptedRoundTrip(loader, factory);
        } finally {
            if (peer != null) peerClass.getMethod("dispose").invoke(peer);
            factoryClass.getMethod("dispose").invoke(factory);
        }
    }

    private void verifyEncryptedRoundTrip(ClassLoader loader, Object factory) throws Exception {
        CountDownLatch connected = new CountDownLatch(2);
        CountDownLatch exchanged = new CountDownLatch(2);
        NativePeer first = new NativePeer(loader, factory, connected, exchanged);
        NativePeer second = new NativePeer(loader, factory, connected, exchanged);
        first.other = second;
        second.other = first;
        try {
            Class<?> channelInitClass = loader.loadClass("org.webrtc.DataChannel$Init");
            Object channel = first.peerClass.getMethod("createDataChannel", String.class, channelInitClass)
                    .invoke(first.peer, "encrypted-native-probe", channelInitClass.getConstructor().newInstance());
            first.observeChannel(channel);
            Object offer = first.createDescription(true);
            first.setDescription(offer, false);
            second.setDescription(offer, true);
            Object answer = second.createDescription(false);
            second.setDescription(answer, false);
            first.setDescription(answer, true);
            if (!connected.await(30, TimeUnit.SECONDS) || !exchanged.await(10, TimeUnit.SECONDS)) {
                throw new AssertionError("Encrypted native peer exchange timed out");
            }
            if (first.failure.get() != null || second.failure.get() != null) {
                throw new AssertionError("Encrypted native callback failed");
            }
        } finally {
            first.closed = true;
            second.closed = true;
            first.peerClass.getMethod("dispose").invoke(first.peer);
            second.peerClass.getMethod("dispose").invoke(second.peer);
        }
    }

    private static final class NativePeer {
        final ClassLoader loader;
        final Class<?> peerClass;
        final Object peer;
        final CountDownLatch exchanged;
        final List<Object> pendingCandidates = new ArrayList<>();
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final AtomicBoolean received = new AtomicBoolean();
        final AtomicBoolean sent = new AtomicBoolean();
        volatile NativePeer other;
        volatile boolean closed;
        boolean remoteDescriptionSet;

        NativePeer(ClassLoader loader, Object factory, CountDownLatch connected, CountDownLatch exchanged) throws Exception {
            this.loader = loader;
            this.exchanged = exchanged;
            peerClass = loader.loadClass("org.webrtc.PeerConnection");
            Class<?> configClass = loader.loadClass("org.webrtc.PeerConnection$RTCConfiguration");
            Class<?> observerClass = loader.loadClass("org.webrtc.PeerConnection$Observer");
            Object config = configClass.getConstructor(List.class).newInstance(Collections.emptyList());
            AtomicBoolean connectedOnce = new AtomicBoolean();
            Object observer = Proxy.newProxyInstance(loader, new Class<?>[]{observerClass}, (proxy, method, args) -> {
                if (closed) return null;
                try {
                    if (method.getName().equals("onIceCandidate") && other != null) other.addCandidate(args[0]);
                    if (method.getName().equals("onDataChannel")) observeChannel(args[0]);
                    if (method.getName().equals("onConnectionChange") && args[0].toString().equals("CONNECTED") &&
                            connectedOnce.compareAndSet(false, true)) connected.countDown();
                } catch (Throwable callbackFailure) { failure.compareAndSet(null, callbackFailure); }
                return null;
            });
            peer = factory.getClass().getMethod("createPeerConnection", configClass, observerClass).invoke(factory, config, observer);
            if (peer == null) throw new AssertionError("Native loopback peer missing");
        }

        synchronized void addCandidate(Object candidate) throws Exception {
            if (closed) return;
            if (remoteDescriptionSet) {
                Object accepted = peerClass.getMethod("addIceCandidate", loader.loadClass("org.webrtc.IceCandidate")).invoke(peer, candidate);
                if (!Boolean.TRUE.equals(accepted)) throw new AssertionError("Native route rejected");
            } else {
                if (pendingCandidates.size() >= 64) throw new AssertionError("Too many native routes");
                pendingCandidates.add(candidate);
            }
        }

        Object createDescription(boolean offer) throws Exception {
            CountDownLatch done = new CountDownLatch(1);
            AtomicReference<Object> description = new AtomicReference<>();
            Class<?> observerClass = loader.loadClass("org.webrtc.SdpObserver");
            Object observer = Proxy.newProxyInstance(loader, new Class<?>[]{observerClass}, (proxy, method, args) -> {
                if (method.getName().equals("onCreateSuccess")) { description.set(args[0]); done.countDown(); }
                else if (method.getName().equals("onCreateFailure")) done.countDown();
                return null;
            });
            Class<?> constraints = loader.loadClass("org.webrtc.MediaConstraints");
            peerClass.getMethod(offer ? "createOffer" : "createAnswer", observerClass, constraints)
                    .invoke(peer, observer, constraints.getConstructor().newInstance());
            if (!done.await(15, TimeUnit.SECONDS) || description.get() == null) throw new AssertionError("Native description missing");
            return description.get();
        }

        void setDescription(Object description, boolean remote) throws Exception {
            CountDownLatch done = new CountDownLatch(1);
            AtomicBoolean accepted = new AtomicBoolean();
            Class<?> observerClass = loader.loadClass("org.webrtc.SdpObserver");
            Object observer = Proxy.newProxyInstance(loader, new Class<?>[]{observerClass}, (proxy, method, args) -> {
                if (method.getName().equals("onSetSuccess")) { accepted.set(true); done.countDown(); }
                else if (method.getName().equals("onSetFailure")) done.countDown();
                return null;
            });
            peerClass.getMethod(remote ? "setRemoteDescription" : "setLocalDescription", observerClass,
                    loader.loadClass("org.webrtc.SessionDescription")).invoke(peer, observer, description);
            if (!done.await(15, TimeUnit.SECONDS) || !accepted.get()) throw new AssertionError("Native description rejected");
            if (remote) synchronized (this) {
                remoteDescriptionSet = true;
                for (Object candidate : pendingCandidates) addCandidate(candidate);
                pendingCandidates.clear();
            }
        }

        void observeChannel(Object channel) throws Exception {
            Class<?> observerClass = loader.loadClass("org.webrtc.DataChannel$Observer");
            Object observer = Proxy.newProxyInstance(loader, new Class<?>[]{observerClass}, (proxy, method, args) -> {
                try {
                    if (method.getName().equals("onStateChange")) sendProof(channel);
                    if (method.getName().equals("onMessage")) {
                        ByteBuffer bytes = ((ByteBuffer) args[0].getClass().getField("data").get(args[0])).duplicate();
                        if (bytes.remaining() > 64) throw new AssertionError("Native proof is oversized");
                        byte[] payload = new byte[bytes.remaining()];
                        bytes.get(payload);
                        if (!new String(payload, StandardCharsets.UTF_8).equals("synapse-native-proof")) throw new AssertionError("Native proof mismatch");
                        if (received.compareAndSet(false, true)) exchanged.countDown();
                    }
                } catch (Throwable callbackFailure) { failure.compareAndSet(null, callbackFailure); }
                return null;
            });
            channel.getClass().getMethod("registerObserver", observerClass).invoke(channel, observer);
            sendProof(channel);
        }

        void sendProof(Object channel) throws Exception {
            if (!channel.getClass().getMethod("state").invoke(channel).toString().equals("OPEN") || !sent.compareAndSet(false, true)) return;
            Class<?> bufferClass = loader.loadClass("org.webrtc.DataChannel$Buffer");
            Object buffer = bufferClass.getConstructor(ByteBuffer.class, boolean.class)
                    .newInstance(ByteBuffer.wrap("synapse-native-proof".getBytes(StandardCharsets.UTF_8)), false);
            if (!Boolean.TRUE.equals(channel.getClass().getMethod("send", bufferClass).invoke(channel, buffer))) {
                throw new AssertionError("Native proof send failed");
            }
        }
    }
}
