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

export const webRtcCallbackContracts = Object.freeze([
  ["org.webrtc.PeerConnectionFactory", "onNetworkThreadReady()V"],
  ["org.webrtc.PeerConnectionFactory", "onWorkerThreadReady()V"],
  ["org.webrtc.PeerConnectionFactory", "onSignalingThreadReady()V"],
  ["org.webrtc.SdpObserver", "onCreateSuccess(Lorg/webrtc/SessionDescription;)V"],
  ["org.webrtc.SdpObserver", "onCreateFailure(Ljava/lang/String;)V"],
  ["org.webrtc.SdpObserver", "onSetSuccess()V"],
  ["org.webrtc.SdpObserver", "onSetFailure(Ljava/lang/String;)V"],
  ["org.webrtc.PeerConnection$Observer", "onIceCandidate(Lorg/webrtc/IceCandidate;)V"],
  ["org.webrtc.PeerConnection$Observer", "onConnectionChange(Lorg/webrtc/PeerConnection$PeerConnectionState;)V"],
  ["org.webrtc.PeerConnection$Observer", "onTrack(Lorg/webrtc/RtpTransceiver;)V"],
  ["org.webrtc.PeerConnection$Observer", "onDataChannel(Lorg/webrtc/DataChannel;)V"],
  ["org.webrtc.SessionDescription", "<init>(Lorg/webrtc/SessionDescription$Type;Ljava/lang/String;)V"],
  ["org.webrtc.SessionDescription", "getDescription()Ljava/lang/String;"],
  ["org.webrtc.IceCandidate", "<init>(Ljava/lang/String;ILjava/lang/String;Ljava/lang/String;Lorg/webrtc/PeerConnection$AdapterType;)V"],
  ["org.webrtc.DataChannel$Observer", "onStateChange()V"],
  ["org.webrtc.DataChannel$Observer", "onMessage(Lorg/webrtc/DataChannel$Buffer;)V"],
]);

export function assertNativeCallbackDescriptor(className, descriptor, disassembly) {
  if (typeof disassembly !== "string" || !disassembly.split(/\r?\n/).some((line) => {
    const declaration = line.trim();
    return declaration.startsWith(".method ") && declaration.endsWith(` ${descriptor}`);
  })) {
    const library = className.startsWith("org.webrtc.") ? "WebRTC" : "libsignal";
    throw new Error(`Packaged ${library} JNI contract is broken: ${className}.${descriptor}`);
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

export function verifyPackagedWebRtcCallbacks(apkPath, apkAnalyzerPath) {
  const inspectedClasses = new Map();
  for (const [className, descriptor] of webRtcCallbackContracts) {
    if (!inspectedClasses.has(className)) {
      const inspection = spawnSync(apkAnalyzerPath, ["dex", "code", "--class", className, apkPath], {
        encoding: "utf8",
        timeout: 60_000,
        maxBuffer: 1_048_576,
      });
      if (inspection.error || inspection.status !== 0) {
        throw new Error(`Could not inspect packaged WebRTC JNI class ${className}`, {cause: inspection.error});
      }
      inspectedClasses.set(className, inspection.stdout);
    }
    assertNativeCallbackDescriptor(className, descriptor, inspectedClasses.get(className));
  }
  return {verifiedWebRtcCallbackDescriptors: webRtcCallbackContracts.length};
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  const [, , apkPath, apkAnalyzerPath] = process.argv;
  if (!apkPath || !apkAnalyzerPath || process.argv.length !== 4) {
    throw new Error("Usage: node verify-synapse-private-jni.mjs APK_PATH APKANALYZER_PATH");
  }
  console.log(JSON.stringify({
    ...verifyPackagedSignalCallbacks(apkPath, apkAnalyzerPath),
    ...verifyPackagedWebRtcCallbacks(apkPath, apkAnalyzerPath),
  }));
}
