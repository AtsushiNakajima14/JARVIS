-keep class com.sun.jna.** { *; }
-keep class org.vosk.** { *; }
-dontwarn javax.annotation.**
# JNA's desktop-only AWT bridge is never used on Android.
-dontwarn java.awt.**

