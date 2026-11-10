package app.gamecloner;

import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.IntentFilter;
import android.os.UserManager;
import android.util.Log;

/** Profile-owner side configuration of the clone space. Every step is idempotent. */
final class ProfileSetup {
    private static final String TAG = "GameCloner";

    /** Google packages games depend on; each is enabled in the clone space if the device has it. */
    private static final String[] STORE_PACKAGES = {
            "com.google.android.gms",
            "com.google.android.gsf",
            Bridge.PLAY_STORE,
            "com.google.android.play.games",
    };

    private ProfileSetup() {
    }

    static ComponentName admin(Context context) {
        return new ComponentName(context.getPackageName(), AdminReceiver.class.getName());
    }

    /** True only inside the work profile this app created and owns. */
    static boolean isOwner(Context context) {
        DevicePolicyManager dpm = context.getSystemService(DevicePolicyManager.class);
        return dpm != null && dpm.isProfileOwnerApp(context.getPackageName());
    }

    /**
     * Brings the clone space into its working state. Safe to call on every start.
     * Steps are isolated so that a refusal of one (OEM policy) never keeps the
     * profile from being enabled, which must be the last step.
     */
    static void apply(Context context) {
        if (!isOwner(context)) {
            return;
        }
        final DevicePolicyManager dpm = context.getSystemService(DevicePolicyManager.class);
        final ComponentName admin = admin(context);

        step("set profile name", () -> dpm.setProfileName(admin, "Game Cloner"));

        // A fresh work profile forbids "install unknown apps"; direct-copy clones need it.
        step("allow unknown sources", () ->
                dpm.clearUserRestriction(admin, UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES));

        // Lets the manager (clone space) open PickerActivity in the personal profile:
        // FLAG_PARENT_CAN_ACCESS_MANAGED = intents sent in the managed profile may be
        // resolved by activities in the parent profile.
        step("link picker", () -> dpm.addCrossProfileIntentFilter(
                admin,
                new IntentFilter(Bridge.ACTION_PICK_APP),
                DevicePolicyManager.FLAG_PARENT_CAN_ACCESS_MANAGED));

        for (String packageName : STORE_PACKAGES) {
            enableSystemApp(context, packageName);
        }

        step("enable profile", () -> dpm.setProfileEnabled(admin));
    }

    /**
     * Makes a preinstalled (system) app available in the clone space.
     *
     * @return false when Android refused, e.g. the package is not a system app or is absent
     */
    static boolean enableSystemApp(Context context, String packageName) {
        DevicePolicyManager dpm = context.getSystemService(DevicePolicyManager.class);
        if (dpm == null) {
            return false;
        }
        try {
            dpm.enableSystemApp(admin(context), packageName);
            return true;
        } catch (RuntimeException e) {
            Log.i(TAG, "enableSystemApp(" + packageName + ") refused: " + e);
            return false;
        }
    }

    private static void step(String what, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException e) {
            Log.w(TAG, "Profile setup step failed: " + what, e);
        }
    }
}
