# Only AndroidX and Kotlin are shrunk and optimized. Everything else keeps its classes and members as they are:
# OpenCover and Shizuku reach hidden APIs and user services by reflection, and the keyboard's dictionary uses JNI.
-keep class !androidx.**,!kotlin.**,!kotlinx.**,** { *; }
-dontobfuscate
