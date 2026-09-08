package app.synapse.privatechat.data.update

import org.junit.Assert.assertThrows
import org.junit.Test
import java.net.URI

class UrlConnectionPrivateUpdateSourceTest {
    @Test
    fun `accepts only the exact initial GitHub URL and repository release asset redirects`() {
        val initial = URI.create(SynapsePrivateUpdateTrust.METADATA_URL)
        assertTrustedUpdateRequestUri(initial, initial, redirectCount = 0)
        assertTrustedUpdateRequestUri(
            initial,
            URI.create(
                "https://release-assets.githubusercontent.com/" +
                    "github-production-release-asset/1271086817/asset-id?signature=opaque",
            ),
            redirectCount = 1,
        )

        assertThrows(IllegalArgumentException::class.java) {
            assertTrustedUpdateRequestUri(
                initial,
                URI.create("https://example.com/github-production-release-asset/1271086817/asset-id"),
                redirectCount = 1,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            assertTrustedUpdateRequestUri(
                initial,
                URI.create(
                    "https://release-assets.githubusercontent.com/" +
                        "github-production-release-asset/999/asset-id",
                ),
                redirectCount = 1,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            assertTrustedUpdateRequestUri(
                initial,
                URI.create("http://release-assets.githubusercontent.com/asset-id"),
                redirectCount = 1,
            )
        }
    }
}
