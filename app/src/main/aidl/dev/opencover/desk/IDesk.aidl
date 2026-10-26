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

    /** The app windows on display [displayId], front first, each as "taskId package visible starting left top right bottom". */
    String[] tasks(int displayId) = 5;

    /** Brings a window to the front on [displayId], restoring it if it was minimized. */
    void showTask(int taskId, int displayId) = 6;

    /** Closes a window (removes its task). */
    void closeTask(int taskId) = 7;

    /** Makes [component] (a live wallpaper, "pkg/class") the wallpaper for [which] (WallpaperManager flags, with Samsung's). */
    void setWallpaper(String component, int which) = 8;

    /** The wallpaper for [which] ("pkg/class"), or null if it can't be read. */
    String wallpaper(int which) = 9;

    /** The phone locked or unlocked: while locked the desk takes no clicks or typing. */
    void setLocked(boolean locked) = 10;

    /** Makes [displayId] the focused display (typing and new launches go there). */
    void focusDisplay(int displayId) = 13;
}
