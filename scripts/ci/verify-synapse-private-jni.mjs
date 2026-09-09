import {spawnSync} from "node:child_process";
import {resolve} from "node:path";
import {pathToFileURL} from "node:url";

const ownerDescriptor = "Lorg/signal/libsignal/internal/NativeHandleGuard$Owner;";
const callbackContracts = [
  ["org.signal.libsignal.internal.NativeHandleGuard$Owner", "unsafeNativeHandleWithoutGuard()J"],
  ...[
    ["SessionStore", "loadSession(J)"],
    ["IdentityKeyStore", "getIdentityKey(J)"],
    ["PreKeyStore", "loadPreKey(I)"],
    ["SignedPreKeyStore", "loadSignedPreKey(I)"],
    ["KyberPreKeyStore", "loadKyberPreKey(I)"],
  ].map(([storeName, callback]) => [
    `org.signal.libsignal.protocol.state.internal.${storeName}`,
    callback + ownerDescriptor,
  ]),
];

export function assertNativeCallbackDescriptor(className, descriptor, disassembly) {
  if (typeof disassembly !== "string" || !disassembly.split(/\r?\n/).some((line) => {
    const declaration = line.trim();
    return declaration.startsWith(".method ") && declaration.endsWith(` ${descriptor}`);
  })) {
    throw new Error(`Packaged libsignal JNI contract is broken: ${className}.${descriptor}`);
  }
}

export function verifyPackagedSignalCallbacks(apkPath, apkAnalyzerPath) {
  for (const [className, descriptor] of callbackContracts) {
    const inspection = spawnSync(apkAnalyzerPath, ["dex", "code", "--class", className, apkPath], {
      encoding: "utf8",
      timeout: 60_000,
      maxBuffer: 1_048_576,
    });
    if (inspection.error || inspection.status !== 0) {
      throw new Error(`Could not inspect packaged JNI class ${className}`, {cause: inspection.error});
    }
    assertNativeCallbackDescriptor(className, descriptor, inspection.stdout);
  }
  return {verifiedNativeCallbackDescriptors: callbackContracts.length};
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  const [, , apkPath, apkAnalyzerPath] = process.argv;
  if (!apkPath || !apkAnalyzerPath || process.argv.length !== 4) {
    throw new Error("Usage: node verify-synapse-private-jni.mjs APK_PATH APKANALYZER_PATH");
  }
  console.log(JSON.stringify(verifyPackagedSignalCallbacks(apkPath, apkAnalyzerPath)));
}
