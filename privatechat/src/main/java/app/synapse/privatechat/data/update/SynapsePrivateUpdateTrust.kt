package app.synapse.privatechat.data.update

internal object SynapsePrivateUpdateTrust {
    const val APPLICATION_ID = "app.synapse.privatechat"
    const val METADATA_URL =
        "https://github.com/peterjreynolds/synapse_local_llm_app/releases/download/" +
            "synapse-private/Synapse-Private-update.json"
    const val APK_URL =
        "https://github.com/peterjreynolds/synapse_local_llm_app/releases/download/" +
            "synapse-private/Synapse-Private.apk"
    const val APK_NAME = "Synapse-Private.apk"
    const val REPOSITORY = "peterjreynolds/synapse_local_llm_app"
    const val RELEASE_TAG = "synapse-private"
    const val SIGNER_SHA256 = "6f762970e8c29b2c810cb790c1e08dbebf80e40f60a03516b7ca665964a14e7b"
    const val MAXIMUM_APK_BYTES = 64L * 1_024L * 1_024L
    const val MAXIMUM_METADATA_BYTES = 64 * 1_024
    const val UPDATE_CACHE_DIRECTORY = "app-updates"
    const val FILE_PROVIDER_AUTHORITY_SUFFIX = ".updateprovider"

    val supportedReleaseAbis = setOf("arm64-v8a", "armeabi-v7a", "x86_64")
}
