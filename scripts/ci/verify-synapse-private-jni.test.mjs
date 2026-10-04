import assert from "node:assert/strict";
import test from "node:test";
import {assertNativeCallbackDescriptor, webRtcCallbackContracts} from "./verify-synapse-private-jni.mjs";

const className = "org.signal.libsignal.protocol.state.internal.SessionStore";
const descriptor = "loadSession(J)Lorg/signal/libsignal/internal/NativeHandleGuard$Owner;";

test("accepts the literal callback descriptor used by libsignal JNI", () => {
  assert.doesNotThrow(() => assertNativeCallbackDescriptor(
    className, descriptor, `.method public abstract ${descriptor}\n.end method\n`,
  ));
});

test("rejects the obfuscated callback found in published version 2043", () => {
  assert.throws(() => assertNativeCallbackDescriptor(
    className, descriptor, ".method public abstract loadSession(J)Lj7/o;\n.end method\n",
  ), /JNI contract is broken/);
});

test("rejects comments or unrelated methods containing the expected descriptor", () => {
  assert.throws(() => assertNativeCallbackDescriptor(
    className, descriptor, `# .method ${descriptor}\n.method public abstract another${descriptor}`,
  ), /JNI contract is broken/);
});

test("rejects absent or unreadable bytecode", () => {
  for (const disassembly of ["", undefined, null]) {
    assert.throws(() => assertNativeCallbackDescriptor(className, descriptor, disassembly), /JNI contract is broken/);
  }
});

test("retains WebRTC SDP, ICE, peer-state and encrypted-channel callbacks", () => {
  assert.equal(webRtcCallbackContracts.length, 16);
  for (const [callbackClass, callbackDescriptor] of webRtcCallbackContracts) {
    assert.doesNotThrow(() => assertNativeCallbackDescriptor(
      callbackClass, callbackDescriptor, `.method public ${callbackDescriptor}\n.end method\n`,
    ));
  }
});

test("rejects a renamed WebRTC SDP callback parameter even if the method name survived", () => {
  assert.throws(() => assertNativeCallbackDescriptor(
    "org.webrtc.SdpObserver", "onCreateSuccess(Lorg/webrtc/SessionDescription;)V",
    ".method public abstract onCreateSuccess(La/b;)V\n.end method\n",
  ), /Packaged WebRTC JNI contract is broken/);
});

test("requires the full native ICE constructor including the adapter type", () => {
  const iceContract = webRtcCallbackContracts.find(([callbackClass]) => callbackClass === "org.webrtc.IceCandidate");
  assert.ok(iceContract);
  assert.throws(() => assertNativeCallbackDescriptor(
    iceContract[0], iceContract[1], ".method public constructor <init>(Ljava/lang/String;ILjava/lang/String;)V\n.end method\n",
  ), /Packaged WebRTC JNI contract is broken/);
});
