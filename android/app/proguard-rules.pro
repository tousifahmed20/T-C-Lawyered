# kotlinx.serialization keeps generated serializers.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class dev.tclawyered.app.** {
    *** Companion;
}
-keepclasseswithmembers class dev.tclawyered.app.** {
    kotlinx.serialization.KSerializer serializer(...);
}
