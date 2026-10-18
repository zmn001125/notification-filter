# kotlinx.serialization 序列化类保留规则
-if @kotlinx.serialization.Serializable class com.local.notificationfilter.**
-keepclassmembers class com.local.notificationfilter.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclasseswithmembers class com.local.notificationfilter.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Room
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.paging.**
