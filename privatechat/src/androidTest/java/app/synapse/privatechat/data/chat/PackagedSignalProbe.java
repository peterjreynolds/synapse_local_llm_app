package app.synapse.privatechat.data.chat;

import android.app.Instrumentation;
import android.os.Bundle;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/** Independent of the target APK's obfuscated Kotlin and AndroidX runtime. */
public final class PackagedSignalProbe extends Instrumentation {
    @Override
    public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        start();
    }

    @Override
    public void onStart() {
        Bundle receipt = new Bundle();
        try {
            verifyNativeEncryptionCallback();
            receipt.putString("result", "PASS: packaged JNI reached loadSession and rejected the absent session");
            finish(-1, receipt);
        } catch (Throwable failure) {
            receipt.putString("result", "FAIL: " + failure.toString());
            finish(1, receipt);
        }
    }

    private void verifyNativeEncryptionCallback() throws Exception {
        ClassLoader applicationLoader = getTargetContext().getClassLoader();
        Class<?> nativeClass = applicationLoader.loadClass("org.signal.libsignal.internal.Native");
        Class<?> sessionStoreClass = applicationLoader.loadClass("org.signal.libsignal.protocol.state.internal.SessionStore");
        Class<?> identityStoreClass = applicationLoader.loadClass("org.signal.libsignal.protocol.state.internal.IdentityKeyStore");
        boolean[] sessionLoaded = {false};
        Object sessionStore = Proxy.newProxyInstance(applicationLoader, new Class<?>[]{sessionStoreClass}, (proxy, method, arguments) -> {
            if (!method.getName().equals("loadSession")) {
                throw new AssertionError("Unexpected native session callback " + method.getName());
            }
            sessionLoaded[0] = true;
            return null;
        });
        Object identityStore = Proxy.newProxyInstance(applicationLoader, new Class<?>[]{identityStoreClass}, (proxy, method, arguments) -> {
            throw new AssertionError("An absent session must fail before " + method.getName());
        });
        Method createAddress = nativeClass.getMethod("ProtocolAddress_New", String.class, int.class);
        Method destroyAddress = nativeClass.getMethod("ProtocolAddress_Destroy", long.class);
        Object localAddress = createAddress.invoke(null, "10000000-0000-4000-8000-000000000001", 1);
        Object peerAddress = createAddress.invoke(null, "10000000-0000-4000-8000-000000000001", 2);
        try {
            Method encrypt = nativeClass.getMethod("SessionCipher_EncryptMessage", byte[].class, long.class, long.class,
                    sessionStoreClass, identityStoreClass, long.class);
            Throwable failure = null;
            try {
                encrypt.invoke(null, new byte[]{1}, localAddress, peerAddress, sessionStore, identityStore, 0L);
            } catch (InvocationTargetException invocation) {
                failure = invocation.getCause();
            }
            if (!sessionLoaded[0]) {
                throw new AssertionError("JNI never reached loadSession: " + failure);
            }
            if (failure == null || !failure.getClass().getName().equals("org.signal.libsignal.protocol.NoSessionException")) {
                throw new AssertionError("Expected the cryptographic absent-session rejection: " + failure);
            }
        } finally {
            destroyAddress.invoke(null, peerAddress);
            destroyAddress.invoke(null, localAddress);
        }
    }
}
