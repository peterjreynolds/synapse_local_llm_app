package app.synapse.privatechat.data.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SynapsePrivateUpdateMetadataParserTest {
    private val parser = SynapsePrivateUpdateMetadataParser()

    @Test
    fun `parses the complete trusted rolling release contract`() {
        val update = parser.parse(validMetadata())

        assertEquals(2038, update.versionCode)
        assertEquals("0.1.2038", update.versionName)
        assertEquals(25, update.minimumAndroidApi)
        assertEquals(setOf("arm64-v8a", "armeabi-v7a", "x86_64"), update.supportedAbis)
        assertEquals(SynapsePrivateUpdateTrust.APK_URL, update.apkDownloadUrl)
        assertEquals(SynapsePrivateUpdateTrust.SIGNER_SHA256, update.signerSha256)
        assertEquals("c".repeat(40), update.sourceCommit)
    }

    @Test
    fun `rejects metadata that redirects the downloader to another APK`() {
        val failure =
            assertThrows(IllegalArgumentException::class.java) {
                parser.parse(
                    validMetadata().replace(
                        SynapsePrivateUpdateTrust.APK_URL,
                        "https://example.com/Synapse-Private.apk",
                    ),
                )
            }

        assertEquals("Update APK URL is untrusted.", failure.message)
    }

    @Test
    fun `rejects metadata for another application or signer`() {
        assertThrows(IllegalArgumentException::class.java) {
            parser.parse(validMetadata().replace(SynapsePrivateUpdateTrust.APPLICATION_ID, "example.other.app"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            parser.parse(validMetadata().replace(SynapsePrivateUpdateTrust.SIGNER_SHA256, "f".repeat(64)))
        }
    }

    @Test
    fun `rejects unknown fields instead of accepting a wider contract`() {
        assertThrows(Exception::class.java) {
            parser.parse(validMetadata().replace("\"schemaVersion\": 1,", "\"schemaVersion\": 1,\"extra\": true,"))
        }
    }
}

internal fun validMetadata(
    versionCode: Int = 2038,
    minimumAndroidApi: Int = 25,
    supportedAbis: String = "\"arm64-v8a\",\"armeabi-v7a\",\"x86_64\"",
    apkByteCount: Long = 8_192L,
    apkSha256: String = "a".repeat(64),
): String =
    """
    {
      "schemaVersion": 1,
      "applicationId": "${SynapsePrivateUpdateTrust.APPLICATION_ID}",
      "versionCode": $versionCode,
      "versionName": "0.1.$versionCode",
      "minimumAndroidApi": $minimumAndroidApi,
      "supportedAbis": [$supportedAbis],
      "apk": {
        "name": "${SynapsePrivateUpdateTrust.APK_NAME}",
        "byteCount": $apkByteCount,
        "sha256": "$apkSha256",
        "signerSha256": "${SynapsePrivateUpdateTrust.SIGNER_SHA256}",
        "downloadUrl": "${SynapsePrivateUpdateTrust.APK_URL}"
      },
      "source": {
        "repository": "${SynapsePrivateUpdateTrust.REPOSITORY}",
        "commit": "${"c".repeat(40)}",
        "releaseTag": "${SynapsePrivateUpdateTrust.RELEASE_TAG}"
      },
      "publishedAt": "2026-08-27T20:15:30Z"
    }
    """.trimIndent()
