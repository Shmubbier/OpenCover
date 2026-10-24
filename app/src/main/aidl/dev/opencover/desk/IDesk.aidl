package dev.opencover.desk;

import android.view.Surface;
import dev.opencover.desk.IDeskCursor;

/** OpenCover's desk helper, running as the shell user through Shizuku (see DeskService). */
interface IDesk {
    /** Shizuku calls this to end the helper. */
    void destroy() = 16777114;

    /** A trusted display drawing into [surface]; returns its id, or -1. */
    int create(in Surface surface, int width, int height, int dpi) = 1;

    /** Releases the display. */
    void release() = 2;

    /**
     * Takes the mouse over (the system stops seeing it) and plays it on display [displayId], [width] x [height];
     * [cursor] hears where the pointer is. False if there's no mouse.
     */
    boolean startMouse(int displayId, int width, int height, IDeskCursor cursor) = 3;

    /** Gives the mouse back to the system. */
    void stopMouse() = 4;
}
