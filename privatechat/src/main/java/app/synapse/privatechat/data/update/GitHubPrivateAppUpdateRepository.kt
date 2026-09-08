package app.synapse.privatechat.data.update

import app.synapse.privatechat.domain.update.PrivateAppUpdateCheckOutcome
import app.synapse.privatechat.domain.update.PrivateAppUpdateRepository
import kotlinx.coroutines.CancellationException
import java.io.IOException

internal class GitHubPrivateAppUpdateRepository(
    private val transferSource: PrivateUpdateTransferSource,
    private val currentVersionCode: Int,
    private val deviceAndroidApi: Int,
    private val deviceSupportedAbis: Set<String>,
    private val metadataParser: SynapsePrivateUpdateMetadataParser = SynapsePrivateUpdateMetadataParser(),
) : PrivateAppUpdateRepository {
    init {
        require(currentVersionCode in 1..2_100_000_000) { "Current version code is invalid." }
        require(deviceAndroidApi > 0) { "Device Android API is invalid." }
        require(deviceSupportedAbis.isNotEmpty()) { "Device ABI set must not be empty." }
    }

    override suspend fun checkForNewerCompatibleUpdate(): PrivateAppUpdateCheckOutcome =
        try {
            val rawMetadata = transferSource.readMetadata(SynapsePrivateUpdateTrust.METADATA_URL)
            val update = metadataParser.parse(rawMetadata)
            when {
                update.versionCode <= currentVersionCode -> PrivateAppUpdateCheckOutcome.NoCompatibleUpdate
                deviceAndroidApi < update.minimumAndroidApi -> PrivateAppUpdateCheckOutcome.NoCompatibleUpdate
                update.supportedAbis.intersect(deviceSupportedAbis).isEmpty() ->
                    PrivateAppUpdateCheckOutcome.NoCompatibleUpdate

                else -> PrivateAppUpdateCheckOutcome.Available(update)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: IOException) {
            PrivateAppUpdateCheckOutcome.Failed("The update server could not be reached.")
        } catch (_: IllegalArgumentException) {
            PrivateAppUpdateCheckOutcome.Failed("The update information could not be verified.")
        } catch (_: Exception) {
            PrivateAppUpdateCheckOutcome.Failed("The update check could not be completed.")
        }
}
