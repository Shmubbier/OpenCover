package dev.opencover.phone

import android.content.Context
import dev.opencover.quick.ShizukuShell

/** The device state that keeps the cover on and the inner screen off: TENT, which the phone's sensors never report. */
const val HELD_STATE = "1"

/**
 * The device state to leave the phone in. With the hinge and hall sensors unreliable, opening the phone partway makes
 * the system think it is unfolded and it turns the cover off. Holding TENT keeps the cover on whatever the sensors
 * say; "reset" gives the choice back to them.
 */
fun foldStateArgs(keepCoverOn: Boolean): List<String> =
    listOf("cmd", "device_state", "state", if (keepCoverOn) HELD_STATE else "reset")

/** Marks the watcher below so it can be found and stopped (`pkill -f`). */
const val FOLD_WATCH_TAG = "opencover-fold-watch"

/**
 * Shell script that keeps the hold. Android drops every device-state override whenever the phone's own state changes
 * (opening or closing), so the hold is requested again the moment the system commits the CLOSED or OPENED state.
 * Runs detached as the shell user, so it keeps working when OpenCover is not in memory.
 */
val FOLD_WATCH_SCRIPT: String =
    """nohup sh -c 'logcat -T 1 -v brief DeviceStateManagerService:D "*:S" | while read -r l; do """ +
        """case "${'$'}l" in *"Committing state"*"identifier=0"*|*"Committing state"*"identifier=3"*) cmd device_state state $HELD_STATE;; esac; done' """ +
        """$FOLD_WATCH_TAG >/dev/null 2>&1 &"""

/** Also stops FreeFlip's watcher, which did the same job before this was ported. */
const val FOLD_WATCH_STOP: String = "pkill -f $FOLD_WATCH_TAG; pkill -f freeflip-fold-watch"

/** Keeps the cover on when the phone is opened, by holding TENT through Shizuku (setting: keep cover on). */
object Fold {
    /** Makes the device state match the saved setting; re-run whenever the accessibility service connects. */
    fun apply(ctx: Context) {
        val keep = PhonePrefs(ctx).keepCoverOn
        Thread {
            ShizukuShell.run("sh", "-c", FOLD_WATCH_STOP)
            if (keep) ShizukuShell.run("sh", "-c", FOLD_WATCH_SCRIPT)
            ShizukuShell.run(*foldStateArgs(keep).toTypedArray())
        }.start()
    }
}
