package app.gamecloner;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/** One launchable app, as shown in the manager and picker lists. */
final class AppEntry {
    /** User apps before system apps, games before the rest, then by label. */
    static final Comparator<AppEntry> ORDER = (a, b) -> {
        if (a.system != b.system) {
            return a.system ? 1 : -1;
        }
        if (a.game != b.game) {
            return a.game ? -1 : 1;
        }
        int byLabel = a.label.compareToIgnoreCase(b.label);
        return byLabel != 0 ? byLabel : a.packageName.compareTo(b.packageName);
    };

    final String packageName;
    final String label;
    final Drawable icon;
    final boolean system;
    final boolean game;

    AppEntry(String packageName, String label, Drawable icon, boolean system, boolean game) {
        this.packageName = packageName;
        this.label = label;
        this.icon = icon;
        this.system = system;
        this.game = game;
    }

    String describe() {
        StringBuilder text = new StringBuilder();
        if (game) {
            text.append("Game - ");
        }
        if (system) {
            text.append("System app - ");
        }
        return text.append(packageName).toString();
    }

    /**
     * Enabled apps with a launcher activity that are installed for the calling user, sorted
     * with {@link #ORDER}. Does one binder call per package, so call it off the main thread.
     *
     * @param selfPackage this app's package, always left out
     * @param exclude     further packages to leave out, or null
     */
    @SuppressWarnings("deprecation") // FLAG_IS_GAME: still the only game marker on older apps
    static List<AppEntry> loadLaunchable(PackageManager pm, String selfPackage, Set<String> exclude) {
        List<AppEntry> entries = new ArrayList<>();
        for (ApplicationInfo info : pm.getInstalledApplications(0)) {
            if (info.packageName.equals(selfPackage)) {
                continue;
            }
            if (exclude != null && exclude.contains(info.packageName)) {
                continue;
            }
            if (!info.enabled || pm.getLaunchIntentForPackage(info.packageName) == null) {
                continue;
            }
            boolean system = (info.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
            boolean game = info.category == ApplicationInfo.CATEGORY_GAME
                    || (info.flags & ApplicationInfo.FLAG_IS_GAME) != 0;
            entries.add(new AppEntry(
                    info.packageName,
                    String.valueOf(pm.getApplicationLabel(info)),
                    pm.getApplicationIcon(info),
                    system,
                    game));
        }
        Collections.sort(entries, ORDER);
        return entries;
    }
}
