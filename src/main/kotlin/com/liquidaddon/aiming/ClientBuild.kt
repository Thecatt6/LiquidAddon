package com.liquidaddon.aiming

/**
 * One line that says which client this jar ended up inside, and whether the door to its rotation
 * manager is open.
 *
 * An add-on is compiled against one build of the client and loaded by another, and the two do not
 * have to agree: the crash that made this line necessary was a `NoSuchMethodError` on a method
 * whose provider parameter was widened between releases. Nothing in the add-on can see that coming,
 * so the answer is to say out loud which build is loaded — the version the launcher installed, not
 * the one this jar was compiled against, which nobody can read back out of a class file.
 *
 * Pure: strings in, a string out, so the wording and the "unknown" cases are testable without a
 * client (see `ClientBuildTest`).
 */
internal object ClientBuild {

    /** Whether the rotation manager of the loaded client could be bound. */
    enum class Door {
        /** Bound: silent rotations reach the client. */
        OPEN,

        /** An attempt was made and no usable overload exists in this build. */
        SHUT,

        /** Nothing has asked for a rotation yet, so nothing is known. */
        UNTRIED,
    }

    /**
     * The line for [clientVersion] and [door], with [detail] — the bound descriptor, or the reason
     * the binding failed — in parentheses. A version that is missing, blank, or an unsubstituted
     * build placeholder reads as `unknown`: saying "0.42.0" when the metadata says `${version}`
     * would be exactly the invented number this add-on is not allowed to produce.
     */
    fun describe(clientVersion: String?, door: Door, detail: String): String {
        val version = readable(clientVersion) ?: "unknown"
        return when (door) {
            Door.OPEN -> "client $version | rotation door open ($detail)"
            Door.SHUT -> "client $version | ROTATION DOOR SHUT ($detail)"
            Door.UNTRIED -> "client $version | rotation door not tried yet"
        }
    }

    /**
     * The version if it is a real one, else null: blank does not count, and neither does an
     * unsubstituted placeholder — a client whose metadata still carries the build template's `$`
     * variable tells us nothing, and guessing a number from it would be inventing one.
     */
    fun readable(clientVersion: String?): String? {
        val trimmed = clientVersion?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        if (trimmed.indexOf('$') >= 0) return null
        return trimmed
    }
}
