/*
 * Custom self-hosted downloader integration.
 */
package app.morphe.patches.youtube.layout.flyout

import app.morphe.patcher.patch.patch
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import app.morphe.util.setExtensionIsPatchIncluded

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/youtube/videoplayer/ServerDownloadButton;"

val serverDownloadPatch = patch(
    name = "Send to my server",
    description = "Adds a button to video menus to send the video to your self-hosted downloader.",
    default = true
) {
    dependsOn(
        flyoutPatch,
        sharedExtensionPatch
    )

    compatibleWith(COMPATIBILITY_YOUTUBE)

    execute {
        setExtensionIsPatchIncluded(EXTENSION_CLASS)
    }
}
