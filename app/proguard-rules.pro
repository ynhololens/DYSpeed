# libxposed API 102 官方规则：
# 保留入口类及其无参构造，并在混淆时同步改写 java_init.list
-dontwarn io.github.libxposed.annotation.**
-adaptresourcefilecontents META-INF/xposed/java_init.list
-keep,allowoptimization,allowobfuscation public class * extends io.github.libxposed.api.XposedModule {
    public <init>();
}

# 保留行号便于排查 hook 问题
-keepattributes SourceFile,LineNumberTable
