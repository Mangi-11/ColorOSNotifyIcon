-dontwarn io.github.libxposed.annotation.**
-adaptresourcefilecontents META-INF/xposed/java_init.list
-keep,allowoptimization,allowobfuscation public class * extends io.github.libxposed.api.XposedModule {
    public <init>();
}

# Debug diagnostics are useful during development but must not enter Release artifacts.
-assumenosideeffects class com.fankes.coloros.notify.diagnostics.Diagnostics {
    public final void debug(...);
}
