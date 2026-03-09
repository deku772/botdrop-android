# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in android-sdk/tools/proguard/proguard-android.txt
# You can edit the include path and order by changing the proguardFiles
# directive in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

-dontobfuscate

# Suppress warnings for hidden Android APIs used by Shizuku
-dontwarn android.app.ActivityManagerNative
-dontwarn android.app.ActivityThread
-dontwarn android.app.IActivityManager$Stub
-dontwarn android.app.IActivityManager
-dontwarn android.content.pm.ILauncherApps
-dontwarn android.content.pm.IPackageManager
-dontwarn android.content.pm.ParceledListSlice
-dontwarn android.content.pm.UserInfo
-dontwarn android.hardware.display.IDisplayManager
-dontwarn android.os.IBatteryPropertiesRegistrar
-dontwarn android.os.IDeviceIdleController
-dontwarn android.os.IUserManager
-dontwarn android.os.ServiceManager
-dontwarn android.os.SystemProperties
-dontwarn android.permission.IPermissionManager
-dontwarn android.view.IWindowManager
-dontwarn com.android.internal.app.IAppOpsService
-dontwarn com.android.org.conscrypt.Conscrypt
#-renamesourcefileattribute SourceFile
#-keepattributes SourceFile,LineNumberTable
