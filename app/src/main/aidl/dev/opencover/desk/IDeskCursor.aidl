package dev.opencover.desk;

/** Where the desk's mouse cursor is, for OpenCover to draw it on the monitor. */
oneway interface IDeskCursor {
    /** [down]: a button is held. */
    void moved(float x, float y, boolean down);
}
