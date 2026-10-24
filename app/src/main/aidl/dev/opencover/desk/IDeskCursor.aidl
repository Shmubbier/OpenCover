package dev.opencover.desk;

/** Where the desk's mouse cursor is, for OpenCover to draw it on the monitor. */
oneway interface IDeskCursor {
    void moved(float x, float y);
}
