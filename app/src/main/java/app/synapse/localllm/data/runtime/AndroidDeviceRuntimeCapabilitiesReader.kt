package app.synapse.localllm.data.runtime

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import app.synapse.localllm.domain.runtime.DeviceRuntimeCapabilities
import app.synapse.localllm.domain.runtime.DeviceRuntimeCapabilitiesReader

class AndroidDeviceRuntimeCapabilitiesReader(context: Context) : DeviceRuntimeCapabilitiesReader {
    private val activityManager = context.applicationContext.getSystemService(ActivityManager::class.java)

    override fun readDeviceRuntimeCapabilities(): DeviceRuntimeCapabilities {
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)
        val appMemoryClassBytes = activityManager.memoryClass.toLong() * BYTES_PER_MEBIBYTE
        val totalMemoryBytes = memoryInfo.totalMem.takeIf { totalBytes -> totalBytes > 0L }
            ?: appMemoryClassBytes
        return DeviceRuntimeCapabilities(
            androidApiLevel = Build.VERSION.SDK_INT,
            totalMemoryBytes = totalMemoryBytes,
            availableMemoryBytes = memoryInfo.availMem.coerceIn(0L, totalMemoryBytes),
            appMemoryClassBytes = appMemoryClassBytes,
            isLowMemory = memoryInfo.lowMemory,
            supportedAbis = Build.SUPPORTED_ABIS.toList(),
        )
    }

    private companion object {
        const val BYTES_PER_MEBIBYTE = 1024L * 1024L
    }
}
