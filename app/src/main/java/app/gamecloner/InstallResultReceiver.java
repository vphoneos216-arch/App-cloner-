package app.gamecloner;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.os.Build;

/** Receives the status of the PackageInstaller session that ApkCloner committed. */
public class InstallResultReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        int sessionId = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1);
        String message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
        Intent confirm = status == PackageInstaller.STATUS_PENDING_USER_ACTION
                ? readConfirmIntent(intent)
                : null;
        ApkCloner.onInstallerStatus(
                context.getApplicationContext(), status, sessionId, message, confirm);
    }

    @SuppressWarnings("deprecation") // the untyped getter is the only one before API 33
    private static Intent readConfirmIntent(Intent intent) {
        if (Build.VERSION.SDK_INT >= 33) {
            return intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent.class);
        }
        return intent.getParcelableExtra(Intent.EXTRA_INTENT);
    }
}
