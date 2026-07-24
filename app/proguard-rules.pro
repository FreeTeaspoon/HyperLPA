# lpac-jni publishes its name-based JNI keeps through its AAR consumer rules.

# Keep useful Java/Kotlin locations in crash reports while allowing code and
# resource shrinking for distribution builds.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
