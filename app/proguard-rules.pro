# Don't obfuscate but strip out unused code.
-dontobfuscate
-optimizations !code/allocation/variable

# Keep some fields of BuildConfig for Sentry
-keep class pw.thedrhax.mosmetro.BuildConfig { 
    java.lang.String BRANCH_NAME;
    java.lang.Integer BUILD_NUMBER;
}

# jsoup
-keeppackagenames org.jsoup.nodes

# Okio
-dontwarn okio.**
-dontwarn javax.annotation.Nullable
-dontwarn javax.annotation.ParametersAreNonnullByDefault

# json-path: optional slf4j binding is not needed on Android
-dontwarn org.slf4j.impl.StaticLoggerBinder

# Sentry: SDK initializes parts of itself via reflection
-keep class io.sentry.** { *; }
-dontwarn io.sentry.**

# dnsjava 3.x optional platform providers (not available on Android)
-dontwarn com.sun.jna.**
-dontwarn javax.naming.**
-dontwarn lombok.Generated
-dontwarn sun.net.spi.nameservice.NameServiceDescriptor
