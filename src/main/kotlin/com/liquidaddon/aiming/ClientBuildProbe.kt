package com.liquidaddon.aiming

import net.fabricmc.loader.api.FabricLoader

/**
 * Reads the build of the client this jar was loaded into, once.
 *
 * The mod metadata is the only honest source: it is what the launcher installed, and it is the
 * same place the client itself reads its version from when it installs add-ons. A `NoSuchMethodError`
 * is the symptom of the two builds disagreeing, and the version string is what turns that symptom
 * into a fact someone can act on.
 *
 * Never throws: a probe that cannot answer says `null` and [ClientBuild] prints `unknown`. The
 * whole point of this class is to survive a client that is not the one we were compiled against.
 */
internal object ClientBuildProbe {

    /** The mod id of LiquidBounce, in its own `fabric.mod.json`. */
    private const val CLIENT_MOD_ID = "liquidbounce"

    /** The version string, or null; resolved once, whatever the answer. */
    var version: String? = null
        private set

    private var asked = false

    /** The version of the loaded client, asking the loader at most once. */
    fun read(): String? {
        if (!asked) {
            asked = true
            version = try {
                FabricLoader.getInstance()
                    .getModContainer(CLIENT_MOD_ID)
                    .map { it.metadata.version.friendlyString }
                    .orElse(null)
            } catch (e: Throwable) {
                null
            }
        }
        return version
    }
}
