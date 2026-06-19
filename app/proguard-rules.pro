# 剥离 Kotlin 在几乎每个方法/参数处注入的 null 检查调用。这些 Intrinsics.checkNotNull*
# 在已全 Kotlin、参数来源可控的本应用里是纯冗余，移除可观地缩小 dex。
-assumenosideeffects class kotlin.jvm.internal.Intrinsics {
    public static void checkNotNull(...);
    public static void checkNotNullParameter(...);
    public static void checkNotNullExpressionValue(...);
    public static void checkExpressionValueIsNotNull(...);
    public static void checkParameterIsNotNull(...);
    public static void checkFieldIsNotNull(...);
    public static void checkReturnedValueIsNotNull(...);
    public static void throwUninitializedPropertyAccessException(...);
}

# 剥离所有日志调用（android.util.Log），release 包不需要。
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
}
