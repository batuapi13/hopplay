# sshj / smbj / BouncyCastle rely on reflection and optional desktop-only classes.
-dontwarn javax.**
-dontwarn java.beans.**
-dontwarn org.ietf.jgss.**
-dontwarn org.slf4j.**
-dontwarn sun.security.**
-dontwarn com.sun.**
-dontwarn org.bouncycastle.**
-dontwarn net.i2p.crypto.**
-dontwarn com.jcraft.**
-keep class net.schmizz.** { *; }
-keep class com.hierynomus.** { *; }
-keep class net.engio.mbassy.** { *; }
-keep class org.bouncycastle.jcajce.provider.** { *; }
-keep class org.bouncycastle.jce.provider.** { *; }
-keep class net.i2p.crypto.eddsa.** { *; }
# Media3 loads decoder extension renderers reflectively.
-keep class androidx.media3.decoder.** { *; }
