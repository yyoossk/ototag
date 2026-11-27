# OtoTag Android shrink rules: tree-shake unused code (in practice
# the Kotlin standard library), but never rename anything — JNI
# method names, manifest components and string-based lookups must
# stay exact.
#
# The app itself, jaudiotagger and kotlinx-io are kept whole: R8's
# full mode dropped reachable classes from them when it could not
# parse the Kotlin 2.4 metadata (verified 2026-11-28: TagService and
# AudioTagger vanished from the dex). With these keeps, a descriptor
# diff against the input jars shows every class survives.
-dontobfuscate
# Compile-only annotations (org.jetbrains) are not shipped in the APK.
-dontwarn org.jetbrains.annotations.**
-keep class com.yyoo.ototag.** { *; }
-keep class org.jaudiotagger.** { *; }
-keep class kotlinx.io.** { *; }
