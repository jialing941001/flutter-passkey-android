package com.corbado.passkeys_android;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GoogleApiAvailability;

public class PasskeysEligibility {

    private static final long MIN_PLAY_VERSION = 230815045L;

    /**
     * Check if passkeys are supported on the device. In order, we verify that:
     * 1. The API Version >= P
     * 2. Ensure GMS is enabled, to avoid any disabled related errors.
     * 3. Google Play Services >= 230815045, which is a version matching one of the first stable passkey releases.
     * This check is added to the library here: https://developer.android.com/jetpack/androidx/releases/credentials#1.3.0-alpha01
     * 4. The device is secured with some lock.
     */
    public static boolean isPasskeySupported(Context context) {

        // Check if device is running on Android P or higher

        // Check if Google Play Services disabled
        if (isGooglePlayServicesDisabled(context)) {
            return false;
        }

        // Check if Google Play Services version meets minimum requirement
        long yourPlayVersion = determineDeviceGMSVersionCode(context);
        if (yourPlayVersion < MIN_PLAY_VERSION) {
            return false;
        }

        // Check if device is secured with a lock screen
        KeyguardManager keyguardManager = (KeyguardManager) context.getSystemService(Context.KEYGUARD_SERVICE);
        boolean isDeviceSecured = keyguardManager.isDeviceSecure();

        if (!isDeviceSecured) {
            return false;
        }

        // All checks passed, device should support passkeys
        return true;
    }

    /**
     * Recovers the current GMS version code running on the device. This is needed because
     * even if a dependency knows the methods and functions of a newer code, the device may
     * only contain the older module, which can cause exceptions due to the discrepancy.
     */
    private static long determineDeviceGMSVersionCode(Context context) {
        PackageManager packageManager = context.getPackageManager();
        String packageName = GoogleApiAvailability.GOOGLE_PLAY_SERVICES_PACKAGE;
        try {
            return packageManager.getPackageInfo(packageName, 0).getLongVersionCode();
        } catch (PackageManager.NameNotFoundException e) {
            e.printStackTrace();
            return 0;
        }
    }

    /**
     * Determines if Google Play Services is disabled on the device.
     */
    private static boolean isGooglePlayServicesDisabled(Context context) {
        GoogleApiAvailability googleApiAvailability = GoogleApiAvailability.getInstance();
        int connectionResult = googleApiAvailability.isGooglePlayServicesAvailable(context);
        return connectionResult != ConnectionResult.SUCCESS;
    }
}
