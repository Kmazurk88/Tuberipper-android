# The web page calls these by name through window.TRNative (addJavascriptInterface).
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keepattributes JavascriptInterface
# Personal sideloaded app, open source: readable stack traces matter more than obfuscation.
-dontobfuscate
-keepattributes SourceFile,LineNumberTable
