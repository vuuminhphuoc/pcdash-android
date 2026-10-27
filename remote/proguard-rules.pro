# PCDash's own classes stay as written: libpcnet_jni finds classes and methods by name, and some code
# and tests use reflection. R8 still shrinks and optimizes the libraries (Compose, Kotlin, zxing).
-keep class vn.vuumi.pcdash.** { *; }
-dontobfuscate
