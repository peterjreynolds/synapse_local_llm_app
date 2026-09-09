import assert from "node:assert/strict";
import test from "node:test";
import {assertNativeCallbackDescriptor} from "./verify-synapse-private-jni.mjs";

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
