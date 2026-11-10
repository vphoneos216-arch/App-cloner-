package app.gamecloner;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.InstallSourceInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Personal-profile side of "Clone an installed app". The clone space cannot see apps that are
 * only installed in the personal profile, so the manager asks this activity (through Android's
 * cross-profile forwarder) and gets back the chosen package and the paths of its APK files.
 */
public class PickerActivity extends Activity {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Set<String> alreadyCloned = new HashSet<>();
    private final List<AppEntry> loaded = new ArrayList<>();
    private AppListAdapter adapter;
    private CheckBox showSystem;
    private TextView status;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setTitle("Choose an app to clone");

        ArrayList<String> known = getIntent().getStringArrayListExtra(Bridge.EXTRA_ALREADY_CLONED);
        if (known != null) {
            alreadyCloned.addAll(known);
        }

        int pad = Ui.dp(this, 12);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        Ui.padForSystemBars(root);

        status = new TextView(this);
        status.setPadding(pad, pad, pad, 0);
        status.setText("Loading apps...");
        root.addView(status, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        showSystem = new CheckBox(this);
        showSystem.setText("Show system apps");
        showSystem.setOnCheckedChangeListener((button, checked) -> applyFilter());
        LinearLayout.LayoutParams boxParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        boxParams.setMargins(pad, 0, pad, 0);
        root.addView(showSystem, boxParams);

        ListView list = new ListView(this);
        adapter = new AppListAdapter(this, null, null);
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) -> pick(adapter.getItem(position)));
        root.addView(list, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
        load();
    }

    private void load() {
        final PackageManager pm = getPackageManager();
        final String self = getPackageName();
        final Set<String> exclude = new HashSet<>(alreadyCloned);
        new Thread(() -> {
            final List<AppEntry> entries = AppEntry.loadLaunchable(pm, self, exclude);
            main.post(() -> {
                if (isDestroyed()) {
                    return;
                }
                loaded.clear();
                loaded.addAll(entries);
                applyFilter();
            });
        }, "load-apps").start();
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
        status.setText(visible.isEmpty()
                ? "No apps left to clone."
                : "Tap an app to clone it. Games are listed first.");
    }

    private void pick(AppEntry entry) {
        PackageManager pm = getPackageManager();
        ApplicationInfo info;
        try {
            info = pm.getApplicationInfo(entry.packageName, 0);
        } catch (PackageManager.NameNotFoundException e) {
            Ui.toast(this, entry.label + " is no longer installed.");
            return;
        }

        // Base APK first, then every split: a clone needs the complete set.
        ArrayList<String> apkPaths = new ArrayList<>();
        apkPaths.add(info.sourceDir);
        if (info.splitSourceDirs != null) {
            Collections.addAll(apkPaths, info.splitSourceDirs);
        }

        Intent result = new Intent();
        result.putExtra(Bridge.EXTRA_PACKAGE, entry.packageName);
        result.putExtra(Bridge.EXTRA_LABEL, entry.label);
        result.putExtra(Bridge.EXTRA_IS_SYSTEM, entry.system);
        result.putExtra(Bridge.EXTRA_FROM_PLAY, installedByPlay(pm, entry.packageName));
        result.putStringArrayListExtra(Bridge.EXTRA_APK_PATHS, apkPaths);
        setResult(RESULT_OK, result);
        finish();
    }

    private static boolean installedByPlay(PackageManager pm, String packageName) {
        try {
            InstallSourceInfo source = pm.getInstallSourceInfo(packageName);
            return Bridge.PLAY_STORE.equals(source.getInstallingPackageName());
        } catch (Exception e) {
            return false;
        }
    }
}
