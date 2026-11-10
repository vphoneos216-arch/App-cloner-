package app.gamecloner;

import android.app.Activity;
import android.app.admin.DevicePolicyManager;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.CrossProfileApps;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.UserManager;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Launcher entry in both profiles.
 * Inside the clone space (this app is its profile owner) it opens the manager.
 * In the personal profile it creates the clone space, or hands over to it once it exists.
 */
public class MainActivity extends Activity {
    private static final int REQUEST_PROVISION = 1;
    private static final long POLL_INTERVAL_MS = 500;
    private static final long POLL_TIMEOUT_MS = 30_000;

    /** Minimum gap between automatic hand-overs; breaks any ping-pong between profiles. */
    private static final long HANDOFF_GUARD_MS = 2_000;
    private static final String PREFS = "router";
    private static final String KEY_LAST_HANDOFF = "last_handoff";

    private static final String INTRO =
            "Game Cloner creates one clone space on this phone (an Android work profile). "
                    + "A game you clone is installed there by Android itself, so it runs natively "
                    + "next to the original, with its own data and its own accounts.\n\n"
                    + "Tap the button and accept Android's work-profile setup screens.";

    private static final String NOT_SUPPORTED =
            "\n\nThis device reports no work-profile support, so a clone space cannot be created on it.";

    private static final String REFUSED =
            "\n\nAndroid is currently refusing a new work profile. The usual reasons are that a work "
                    + "profile or an OEM dual-app space already exists, or that the phone's user limit "
                    + "is reached. Remove the existing one in Settings, then try again. You can still "
                    + "tap the button to see Android's own explanation.";

    private static final String STUCK =
            "Android reported that the work profile was created, but it has not come up. Restart the "
                    + "phone once and open Game Cloner again. If it still is not found, remove the work "
                    + "profile in Settings and create it again.";

    private static final String FOREIGN_PROFILE =
            "This copy of Game Cloner is inside a work profile that another app manages, so it cannot "
                    + "manage clones here. Open Game Cloner from your personal apps.";

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable poll = this::refresh;
    private TextView status;
    private Button action;

    /** elapsedRealtime until which a just-provisioned profile is awaited; 0 when not waiting. */
    private long pollDeadline;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (ProfileSetup.isOwner(this)) {
            ProfileSetup.apply(this);
            startActivity(new Intent(this, ManagerActivity.class));
            finish();
            return;
        }
        buildUi();
        if (state == null && !inForeignWorkProfile() && handOffGuardExpired()) {
            UserHandle space = Bridge.otherProfile(this);
            if (space != null) {
                handOff(space);
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (isFinishing() || status == null) {
            return;
        }
        refresh();
    }

    @Override
    protected void onPause() {
        super.onPause();
        main.removeCallbacks(poll);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_PROVISION) {
            return;
        }
        if (resultCode == RESULT_OK) {
            pollDeadline = SystemClock.elapsedRealtime() + POLL_TIMEOUT_MS;
        } else {
            Ui.toast(this, "Clone space setup was cancelled or refused by Android.");
        }
    }

    private void buildUi() {
        int pad = Ui.dp(this, 24);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        Ui.padForSystemBars(root);

        status = new TextView(this);
        status.setTextSize(16f);
        root.addView(status, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        action = new Button(this);
        LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        buttonParams.setMargins(0, pad, 0, 0);
        root.addView(action, buttonParams);

        setContentView(root);
    }

    /** Re-reads the profile state and shows the matching text and button. */
    private void refresh() {
        main.removeCallbacks(poll);

        if (inForeignWorkProfile()) {
            show(FOREIGN_PROFILE, null, null);
            return;
        }

        final UserHandle space = Bridge.otherProfile(this);
        if (space != null) {
            boolean justCreated = pollDeadline != 0;
            pollDeadline = 0;
            if (justCreated && handOff(space)) {
                return;
            }
            show("The clone space is ready.", "Open clone manager", v -> handOff(space));
            return;
        }

        if (pollDeadline != 0) {
            if (SystemClock.elapsedRealtime() < pollDeadline) {
                show("Finishing clone space setup...", null, null);
                main.postDelayed(poll, POLL_INTERVAL_MS);
                return;
            }
            pollDeadline = 0;
            show(STUCK, "Create clone space", v -> startProvisioning());
            return;
        }

        show(INTRO + provisioningHint(), "Create clone space", v -> startProvisioning());
    }

    private void show(String text, String buttonLabel, View.OnClickListener onClick) {
        status.setText(text);
        if (buttonLabel == null) {
            action.setVisibility(View.GONE);
            action.setOnClickListener(null);
        } else {
            action.setVisibility(View.VISIBLE);
            action.setText(buttonLabel);
            action.setOnClickListener(onClick);
        }
    }

    private String provisioningHint() {
        if (!getPackageManager().hasSystemFeature(PackageManager.FEATURE_MANAGED_USERS)) {
            return NOT_SUPPORTED;
        }
        DevicePolicyManager dpm = getSystemService(DevicePolicyManager.class);
        if (dpm != null
                && !dpm.isProvisioningAllowed(DevicePolicyManager.ACTION_PROVISION_MANAGED_PROFILE)) {
            return REFUSED;
        }
        return "";
    }

    /** Not the owner (checked by the caller) but inside a managed profile: someone else's. */
    private boolean inForeignWorkProfile() {
        UserManager users = getSystemService(UserManager.class);
        return users != null && users.isManagedProfile();
    }

    private void startProvisioning() {
        Intent provision = new Intent(DevicePolicyManager.ACTION_PROVISION_MANAGED_PROFILE);
        provision.putExtra(
                DevicePolicyManager.EXTRA_PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME,
                ProfileSetup.admin(this));
        provision.putExtra(DevicePolicyManager.EXTRA_PROVISIONING_SKIP_ENCRYPTION, true);
        if (Build.VERSION.SDK_INT >= 33) {
            // Android 13+: without this, setup may insist on downloading a management app first.
            provision.putExtra(DevicePolicyManager.EXTRA_PROVISIONING_ALLOW_OFFLINE, true);
        }
        try {
            startActivityForResult(provision, REQUEST_PROVISION);
        } catch (ActivityNotFoundException e) {
            show("This device has no work-profile setup component, so a clone space cannot be "
                    + "created on it.", null, null);
        }
    }

    private boolean handOffGuardExpired() {
        long last = getSharedPreferences(PREFS, MODE_PRIVATE).getLong(KEY_LAST_HANDOFF, 0);
        long since = SystemClock.elapsedRealtime() - last;
        // "since" is negative after a reboot, because elapsedRealtime restarts from zero.
        return last == 0 || since < 0 || since > HANDOFF_GUARD_MS;
    }

    /**
     * Opens this app's launcher activity in the given profile and closes this one.
     *
     * @return false when Android refused; the reason is shown and this activity stays open
     */
    private boolean handOff(UserHandle space) {
        CrossProfileApps apps = getSystemService(CrossProfileApps.class);
        try {
            apps.startMainActivity(new ComponentName(this, MainActivity.class), space);
        } catch (RuntimeException e) {
            status.setText("Could not open the clone space: " + e.getMessage());
            return false;
        }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putLong(KEY_LAST_HANDOFF, SystemClock.elapsedRealtime())
                .apply();
        finish();
        return true;
    }
}
