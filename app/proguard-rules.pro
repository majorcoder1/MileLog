# Room entities are read reflectively by the generated DAOs.
-keep class com.milelog.data.** { *; }

# WorkManager builds workers by reflection.
-keep class * extends androidx.work.ListenableWorker { public <init>(...); }
-keep class com.milelog.work.** { *; }

# osmdroid loads tile sources and overlays by name.
-keep class org.osmdroid.** { *; }
-dontwarn org.osmdroid.**

# Play services location transition API.
-keep class com.google.android.gms.location.** { *; }
-dontwarn com.google.android.gms.**

-dontwarn javax.annotation.**
