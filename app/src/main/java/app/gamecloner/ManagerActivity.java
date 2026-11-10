package app.gamecloner;

import android.app.ActionBar;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.admin.DevicePolicyManager;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Menu;
import android.view.MenuItem;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * The clone manager. Runs inside the clone space, where this app is profile owner:
 * lists the apps installed there, starts them, removes them and adds new clones.
 */
public class ManagerActivity extends Activity implements ApkCloner.Listener {
    private static final int REQUEST_PICK = 1;
    private static final int REQUEST_UNKNOWN_SOURCES = 2;
    private static final int MENU_DELETE_SPACE = 1;
    private static final int PROGRESS_MAX = 1000;

    private static final String STATE_PACKAGE = "pending_package";
    private static final String STATE_LABEL = "pending_label";
    private static final String STATE_APKS = "pending_apks";

    private static final String EMPTY_TEXT =
            "No clones yet.\n\nTap \"Clone an installed app\" to pick one of your installed games, "
                    + "or open the Play Store here and install a game with a Google account that "
                    + "you add to the clone space.";

    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<AppEntry> loaded = new ArrayList<>();
    private AppListAdapter adapter;
    private CheckBox showSystem;

    /** A direct-copy request that waits for the "install unknown apps" permission. */
    private String pendingPackage;
    private String pendingLabel;
    private ArrayList<String> pendingApks;

    /** Label of the app whose direct copy is running, for the result message. */
    private String activeLabel;

    private AlertDialog progressDialog;
    private ProgressBar progressBar;
    private TextView progressText;

    /** Install confirmation that arrived while this activity was not resumed. */
    private Intent pendingConfirm;
    private boolean resumed;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (!ProfileSetup.isOwner(this)) {
            finish();
            return;
        }
        setTitle("Game Cloner");
        ActionBar bar = getActionBar();
        if (bar != null) {
            bar.setSubtitle("Clone space");
        }
        buildUi();
        if (state != null) {
            pendingPackage = state.getString(STATE_PACKAGE);
            pendingLabel = state.getString(STATE_LABEL);
            pendingApks = state.getStringArrayList(STATE_APKS);
        }
        ApkCloner.setListener(this);
        if (ApkCloner.isRunning()) {
            showProgress("Cloning");
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putString(STATE_PACKAGE, pendingPackage);
        out.putString(STATE_LABEL, pendingLabel);
        out.putStringArrayList(STATE_APKS, pendingApks);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (isFinishing() || adapter == null) {
            return;
        }
        resumed = true;
        reload();
        if (pendingConfirm != null) {
            Intent confirm = pendingConfirm;
            pendingConfirm = null;
            launchConfirmation(confirm);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
    }

    @Override
    protected void onDestroy() {
        ApkCloner.clearListener(this);
        dismissProgress();
        super.onDestroy();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(Menu.NONE, MENU_DELETE_SPACE, Menu.NONE, "Delete clone space");
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == MENU_DELETE_SPACE) {
            confirmDeleteSpace();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_PICK) {
            if (resultCode == RESULT_OK && data != null) {
                onAppPicked(data);
            }
        } else if (requestCode == REQUEST_UNKNOWN_SOURCES) {
            if (pendingPackage == null) {
                return;
            }
            if (getPackageManager().canRequestPackageInstalls()) {
                startCopy();
            } else {
                clearPending();
                Ui.toast(this, "Direct copy needs the install permission. Nothing was cloned.");
            }
        }
    }

    // ---------------------------------------------------------------- screen

    private void buildUi() {
        int pad = Ui.dp(this, 12);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        Ui.padForSystemBars(root);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setPadding(pad, pad, pad, 0);

        Button clone = new Button(this);
        clone.setText("Clone an installed app");
        clone.setOnClickListener(v -> pickApp());
        buttons.addView(clone, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button store = new Button(this);
        store.setText("Play Store");
        store.setOnClickListener(v -> openPlayStore());
        buttons.addView(store, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        root.addView(buttons, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        showSystem = new CheckBox(this);
        showSystem.setText("Show system apps");
        showSystem.setOnCheckedChangeListener((button, checked) -> applyFilter());
        LinearLayout.LayoutParams boxParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        boxParams.setMargins(pad, 0, pad, 0);
        root.addView(showSystem, boxParams);

        ListView list = new ListView(this);
        adapter = new AppListAdapter(this, "Remove", this::removeClone);
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) -> launch(adapter.getItem(position)));
        root.addView(list, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        TextView empty = new TextView(this);
        empty.setPadding(pad * 2, pad * 2, pad * 2, pad * 2);
        empty.setText(EMPTY_TEXT);
        root.addView(empty, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        list.setEmptyView(empty);

        setContentView(root);
    }

    private void reload() {
        final PackageManager pm = getPackageManager();
        final String self = getPackageName();
        new Thread(() -> {
            final List<AppEntry> entries = AppEntry.loadLaunchable(pm, self, null);
            main.post(() -> {
                if (isDestroyed()) {
                    return;
                }
                loaded.clear();
                loaded.addAll(entries);
                applyFilter();
            });
        }, "load-clones").start();
    }

    private void applyFilter() {
        boolean includeSystem = showSystem.isChecked();
        List<AppEntry> visible = new ArrayList<>();
        for (AppEntry entry : loaded) {
            if (includeSystem || !entry.system) {
                visible.add(entry);
            }
        }
        adapter.setItems(visible);
    }

    private void showMessage(String title, String text) {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(text)
                .setPositiveButton("OK", null)
                .show();
    }

    // ---------------------------------------------------------------- existing clones

    private void launch(AppEntry entry) {
        Intent launch = getPackageManager().getLaunchIntentForPackage(entry.packageName);
        if (launch == null) {
            Ui.toast(this, entry.label + " has no launcher screen.");
            return;
        }
        try {
            startActivity(launch);
        } catch (RuntimeException e) {
            Ui.toast(this, "Could not start " + entry.label + ": " + e.getMessage());
        }
    }

    /** Opens Android's uninstall dialog. It runs in the clone space, so only the clone goes. */
    private void removeClone(AppEntry entry) {
        Intent delete = new Intent(
                Intent.ACTION_DELETE, Uri.fromParts("package", entry.packageName, null));
        try {
            startActivity(delete);
        } catch (ActivityNotFoundException e) {
            Ui.toast(this, "This device has no uninstall screen.");
        }
    }

    private void openPlayStore() {
        Intent launch = getPackageManager().getLaunchIntentForPackage(Bridge.PLAY_STORE);
        if (launch == null) {
            ProfileSetup.enableSystemApp(this, Bridge.PLAY_STORE);
            launch = getPackageManager().getLaunchIntentForPackage(Bridge.PLAY_STORE);
        }
        if (launch == null) {
            showMessage("Play Store unavailable",
                    "The Play Store is not available in the clone space on this device. "
                            + "Use \"Clone an installed app\" with Direct copy instead.");
            return;
        }
        startActivity(launch);
    }

    // ---------------------------------------------------------------- adding a clone

    /** Asks PickerActivity in the personal profile which installed app to clone. */
    private void pickApp() {
        Intent pick = new Intent(Bridge.ACTION_PICK_APP);
        pick.putStringArrayListExtra(Bridge.EXTRA_ALREADY_CLONED, installedPackages());
        ComponentName forwarder = Bridge.findForwarder(this, pick);
        if (forwarder == null) {
            showMessage("Personal apps not reachable",
                    "Android did not offer a link from the clone space to your personal apps. "
                            + "Close Game Cloner, open it again from your personal apps and retry. "
                            + "Until then you can install games with the Play Store button.");
            return;
        }
        pick.setComponent(forwarder);
        try {
            startActivityForResult(pick, REQUEST_PICK);
        } catch (RuntimeException e) {
            showMessage("Personal apps not reachable", String.valueOf(e.getMessage()));
        }
    }

    private ArrayList<String> installedPackages() {
        ArrayList<String> names = new ArrayList<>();
        for (ApplicationInfo info : getPackageManager().getInstalledApplications(0)) {
            names.add(info.packageName);
        }
        return names;
    }

    private void onAppPicked(Intent data) {
        final String packageName = data.getStringExtra(Bridge.EXTRA_PACKAGE);
        final String rawLabel = data.getStringExtra(Bridge.EXTRA_LABEL);
        final ArrayList<String> apks = data.getStringArrayListExtra(Bridge.EXTRA_APK_PATHS);
        if (packageName == null || apks == null || apks.isEmpty()) {
            showMessage("Clone failed", "The picker returned no app. Try again.");
            return;
        }
        final String label = rawLabel != null ? rawLabel : packageName;
        if (data.getBooleanExtra(Bridge.EXTRA_IS_SYSTEM, false)) {
            cloneSystemApp(packageName, label);
        } else {
            chooseMethod(packageName, label, data.getBooleanExtra(Bridge.EXTRA_FROM_PLAY, false), apks);
        }
    }

    /** Preinstalled apps need no copy: the profile owner may switch them on directly. */
    private void cloneSystemApp(String packageName, String label) {
        ProfileSetup.enableSystemApp(this, packageName);
        if (isInstalledHere(packageName)) {
            Ui.toast(this, label + " is now available in the clone space.");
        } else {
            showMessage("Clone failed", "Android refused to enable " + label + " in the clone space.");
        }
        reload();
    }

    private boolean isInstalledHere(String packageName) {
        try {
            getPackageManager().getApplicationInfo(packageName, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private void chooseMethod(final String packageName, final String label, boolean fromPlay,
            final ArrayList<String> apks) {
        String advice = fromPlay
                ? "This app was installed from Google Play, so Play Store is the better choice."
                : "This app was not installed from Google Play, so use Direct copy.";
        new AlertDialog.Builder(this)
                .setTitle("Clone " + label)
                .setMessage("Play Store: installs the clone from Google Play inside the clone space. "
                        + "It needs a Google account added there, and it keeps Play licensing, "
                        + "updates and in-game downloads working.\n\n"
                        + "Direct copy: copies the installed APK files into the clone space. It needs "
                        + "no account and also works for games installed from an APK file. Android "
                        + "then records Game Cloner as the installer of this app for both copies.\n\n"
                        + advice)
                .setPositiveButton("Play Store", (dialog, which) -> cloneViaPlay(packageName))
                .setNeutralButton("Direct copy", (dialog, which) -> cloneViaCopy(packageName, label, apks))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void cloneViaPlay(String packageName) {
        ProfileSetup.enableSystemApp(this, Bridge.PLAY_STORE);
        Intent market = new Intent(
                Intent.ACTION_VIEW, Uri.parse("market://details?id=" + packageName));
        market.setPackage(Bridge.PLAY_STORE);
        try {
            startActivity(market);
        } catch (ActivityNotFoundException e) {
            showMessage("Play Store unavailable",
                    "The Play Store is not available in the clone space on this device. "
                            + "Use Direct copy instead.");
        }
    }

    private void cloneViaCopy(String packageName, String label, ArrayList<String> apks) {
        pendingPackage = packageName;
        pendingLabel = label;
        pendingApks = apks;
        if (getPackageManager().canRequestPackageInstalls()) {
            startCopy();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Allow installs")
                .setMessage("Android needs your permission before Game Cloner may install the copy. "
                        + "Turn on \"Allow from this source\" on the next screen, then go back.")
                .setPositiveButton("Open setting", (dialog, which) -> openUnknownSourcesSetting())
                .setNegativeButton("Cancel", (dialog, which) -> clearPending())
                .setCancelable(false)
                .show();
    }

    private void openUnknownSourcesSetting() {
        Intent setting = new Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName()));
        try {
            startActivityForResult(setting, REQUEST_UNKNOWN_SOURCES);
        } catch (ActivityNotFoundException e) {
            clearPending();
            showMessage("Setting not found",
                    "Open Settings, find \"Install unknown apps\" for Game Cloner in the work profile, "
                            + "allow it, then start the clone again.");
        }
    }

    private void clearPending() {
        pendingPackage = null;
        pendingLabel = null;
        pendingApks = null;
    }

    private void startCopy() {
        if (pendingPackage == null || pendingApks == null) {
            return;
        }
        String packageName = pendingPackage;
        String label = pendingLabel != null ? pendingLabel : pendingPackage;
        ArrayList<String> apks = pendingApks;
        clearPending();
        if (!ApkCloner.start(getApplicationContext(), packageName, apks)) {
            showMessage("Clone in progress",
                    "Another clone is still running or waiting for your confirmation. "
                            + "Finish or cancel it first.");
            return;
        }
        activeLabel = label;
        showProgress("Cloning " + label);
    }

    // ---------------------------------------------------------------- direct-copy progress

    private void showProgress(String title) {
        dismissProgress();
        int pad = Ui.dp(this, 24);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, pad, pad, 0);

        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(PROGRESS_MAX);
        box.addView(progressBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        progressText = new TextView(this);
        progressText.setPadding(0, Ui.dp(this, 12), 0, 0);
        progressText.setText("Preparing...");
        box.addView(progressText, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        progressDialog = new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(box)
                .setCancelable(false)
                .setNegativeButton("Cancel", (dialog, which) -> ApkCloner.cancel(getApplicationContext()))
                .create();
        progressDialog.show();
    }

    private void dismissProgress() {
        if (progressDialog != null) {
            progressDialog.dismiss();
            progressDialog = null;
            progressBar = null;
            progressText = null;
        }
    }

    private void launchConfirmation(Intent confirm) {
        try {
            startActivity(confirm);
        } catch (RuntimeException e) {
            ApkCloner.cancel(getApplicationContext());
            showMessage("Clone failed",
                    "Android's install confirmation could not be opened: " + e.getMessage());
        }
    }

    @Override
    public void onProgress(long copiedBytes, long totalBytes) {
        if (progressDialog == null) {
            return;
        }
        progressBar.setProgress(totalBytes > 0 ? (int) (copiedBytes * PROGRESS_MAX / totalBytes) : 0);
        progressText.setText(
                Ui.megabytes(copiedBytes) + " / " + Ui.megabytes(totalBytes) + " MB copied");
    }

    @Override
    public void onConfirmationNeeded(Intent confirm) {
        if (progressText != null) {
            progressText.setText("Copy finished. Confirm the install in Android's dialog.");
        }
        if (resumed) {
            launchConfirmation(confirm);
        } else {
            pendingConfirm = confirm;
        }
    }

    @Override
    public void onFinished(boolean success, String message) {
        dismissProgress();
        pendingConfirm = null;
        String label = activeLabel != null ? activeLabel : "The app";
        activeLabel = null;
        if (success) {
            Ui.toast(this, label + " was cloned.");
        } else if (ApkCloner.CANCELLED.equals(message)) {
            Ui.toast(this, message);
        } else {
            showMessage("Clone failed",
                    message == null || message.isEmpty() ? "Android gave no reason." : message);
        }
        reload();
    }

    // ---------------------------------------------------------------- deleting the space

    private void confirmDeleteSpace() {
        new AlertDialog.Builder(this)
                .setTitle("Delete clone space?")
                .setMessage("This removes the clone space together with every cloned app and all of "
                        + "its data. Your original apps are not touched. This cannot be undone.")
                .setPositiveButton("Delete", (dialog, which) -> deleteSpace())
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** A profile owner wiping "its" data removes the whole work profile, nothing else. */
    private void deleteSpace() {
        DevicePolicyManager dpm = getSystemService(DevicePolicyManager.class);
        try {
            dpm.wipeData(DevicePolicyManager.WIPE_SILENTLY);
        } catch (RuntimeException e) {
            showMessage("Could not delete",
                    "Android refused: " + e.getMessage()
                            + "\n\nRemove the work profile from the Accounts page in Settings instead.");
        }
    }
}
