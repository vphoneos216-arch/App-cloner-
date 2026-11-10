package app.gamecloner;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.CrossProfileApps;
import android.content.pm.ResolveInfo;
import android.os.UserHandle;

import java.util.List;

/** Everything the personal-profile side and the clone-space side need to reach each other. */
final class Bridge {
    /** Sent from the clone space, answered by PickerActivity in the personal profile. */
    static final String ACTION_PICK_APP = "app.gamecloner.action.PICK_APP";

    /** Request extra: packages already present in the clone space (ArrayList of String). */
    static final String EXTRA_ALREADY_CLONED = "already_cloned";

    /** Result extras describing the app the user picked. */
    static final String EXTRA_PACKAGE = "pkg";
    static final String EXTRA_LABEL = "label";
    static final String EXTRA_IS_SYSTEM = "is_system";
    static final String EXTRA_FROM_PLAY = "from_play";
    static final String EXTRA_APK_PATHS = "apk_paths";

    static final String PLAY_STORE = "com.android.vending";

    /** Package that hosts Android's IntentForwarderActivity. */
    private static final String FRAMEWORK_PACKAGE = "android";

    private Bridge() {
    }

    /**
     * Finds the framework activity that forwards {@code intent} to the other profile.
     * It only shows up while a matching cross-profile intent filter is registered
     * (see ProfileSetup) and the other profile has a handler for the intent.
     *
     * @return the forwarder component, or null when the profiles are not linked for this intent
     */
    static ComponentName findForwarder(Context context, Intent intent) {
        List<ResolveInfo> candidates = context.getPackageManager().queryIntentActivities(intent, 0);
        for (ResolveInfo candidate : candidates) {
            if (candidate.activityInfo == null) {
                continue;
            }
            if (FRAMEWORK_PACKAGE.equals(candidate.activityInfo.packageName)) {
                return new ComponentName(candidate.activityInfo.packageName, candidate.activityInfo.name);
            }
        }
        return null;
    }

    /**
     * The other profile that also has this app installed, as seen from the calling profile.
     * From the personal profile that is the clone space once it is provisioned and enabled.
     *
     * @return the profile, or null when there is none yet
     */
    static UserHandle otherProfile(Context context) {
        CrossProfileApps apps = context.getSystemService(CrossProfileApps.class);
        if (apps == null) {
            return null;
        }
        List<UserHandle> targets = apps.getTargetUserProfiles();
        return targets.isEmpty() ? null : targets.get(0);
    }
}
