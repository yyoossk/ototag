# OtoTag desktop shrink rules: tree-shake unused code (mostly the
# Kotlin standard library), but never rename anything.
# -injars / -outjars / -libraryjars are passed by build.sh.
-dontobfuscate
-dontoptimize
-dontwarn **
-dontnote **
-keepattributes RuntimeVisibleAnnotations,RuntimeInvisibleAnnotations,Signature,InnerClasses,EnclosingMethod,Exceptions
-keep class kotlin.Metadata { *; }
-keep class com.yyoo.ototag.desktop.MainKt {
    public static void main(java.lang.String[]);
}
# Our own code is kept whole (it is small, and the core doubles as a
# library with test seams like the clients' fetcher hooks). The shrink
# targets the bundled libraries — above all the Kotlin stdlib — whose
# unused parts are the actual weight.
-keep class com.yyoo.ototag.** { *; }
