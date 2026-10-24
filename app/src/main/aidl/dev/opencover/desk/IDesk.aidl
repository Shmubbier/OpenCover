package dev.opencover.desk;

import android.view.Surface;

/** OpenCover's desk helper, running as the shell user through Shizuku (see DeskService). */
interface IDesk {
    /** Shizuku calls this to end the helper. */
    void destroy() = 16777114;

    /** A trusted display drawing into [surface]; returns its id, or -1. */
    int create(in Surface surface, int width, int height, int dpi) = 1;

    /** Releases the display. */
    void release() = 2;
}
