package app.gamecloner;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * "Direct copy" clone: streams the APK files of an app that is installed in the personal
 * profile into a PackageInstaller session of the clone space and commits it. Android then
 * asks the user to confirm and installs the app for the clone space. Runs inside the clone space.
 *
 * One job at a time. {@code listener} and {@code running} are touched on the main thread only,
 * and every Listener callback is delivered there. The copy thread shares only the volatile fields.
 */
final class ApkCloner {
    interface Listener {
        void onProgress(long copiedBytes, long totalBytes);

        /** Android wants the user to confirm; start this intent from a resumed activity. */
        void onConfirmationNeeded(Intent confirm);

        void onFinished(boolean success, String message);
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final int BUFFER_BYTES = 1 << 20;
    private static final long PROGRESS_INTERVAL_MS = 100;
    private static final int NO_SESSION = -1;

    /** Message passed to onFinished when the job ended because cancel() was called. */
    static final String CANCELLED = "Clone cancelled.";

    private static Listener listener;
    private static boolean running;

    private static volatile int activeSessionId = NO_SESSION;
    private static volatile boolean awaitingInstaller;
    private static volatile boolean cancelRequested;

    private ApkCloner() {
    }

    static void setListener(Listener newListener) {
        listener = newListener;
    }

    static void clearListener(Listener oldListener) {
        if (listener == oldListener) {
            listener = null;
        }
    }

    static boolean isRunning() {
        return running;
    }

    /**
     * Starts copying. Call on the main thread.
     *
     * @param apkPaths absolute paths, base APK first, then all splits
     * @return false when another clone is still in progress
     */
    static boolean start(Context appContext, String packageName, List<String> apkPaths) {
        if (running) {
            return false;
        }
        running = true;
        cancelRequested = false;
        awaitingInstaller = false;
        activeSessionId = NO_SESSION;
        final List<String> paths = new ArrayList<>(apkPaths);
        new Thread(() -> copyAndCommit(appContext, packageName, paths), "apk-clone").start();
        return true;
    }

    /**
     * Stops the current job in whichever phase it is. Call on the main thread.
     * While copying, the copy thread notices the flag, drops its session and reports back.
     * While Android waits for the user's confirmation, the session is abandoned here.
     */
    static void cancel(Context appContext) {
        if (!running) {
            return;
        }
        cancelRequested = true;
        if (awaitingInstaller) {
            abandonQuietly(appContext.getPackageManager().getPackageInstaller(), activeSessionId);
            finish(false, CANCELLED);
        }
    }

    /** Name of the i-th file inside the session. Android reads split names from the APKs. */
    static String sessionFileName(int index) {
        return index == 0 ? "base.apk" : "split_" + index + ".apk";
    }

    private static void copyAndCommit(Context context, String packageName, List<String> paths) {
        PackageInstaller installer = context.getPackageManager().getPackageInstaller();
        int sessionId = NO_SESSION;
        boolean committed = false;
        try {
            List<File> files = new ArrayList<>();
            long total = 0;
            for (String path : paths) {
                File file = new File(path);
                if (!file.isFile() || !file.canRead()) {
                    throw new IOException("Cannot read " + path);
                }
                files.add(file);
                total += file.length();
            }
            if (files.isEmpty()) {
                throw new IOException("No APK files were given for " + packageName);
            }

            PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(
                    PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            params.setAppPackageName(packageName);
            params.setSize(total);
            params.setInstallReason(PackageManager.INSTALL_REASON_USER);
            sessionId = installer.createSession(params);
            activeSessionId = sessionId;

            try (PackageInstaller.Session session = installer.openSession(sessionId)) {
                byte[] buffer = new byte[BUFFER_BYTES];
                long copied = 0;
                long lastReport = 0;
                for (int i = 0; i < files.size(); i++) {
                    File file = files.get(i);
                    try (InputStream in = new FileInputStream(file);
                         OutputStream out = session.openWrite(sessionFileName(i), 0, file.length())) {
                        int count;
                        while ((count = in.read(buffer)) != -1) {
                            if (cancelRequested) {
                                throw new IOException(CANCELLED);
                            }
                            out.write(buffer, 0, count);
                            copied += count;
                            long now = SystemClock.uptimeMillis();
                            if (now - lastReport >= PROGRESS_INTERVAL_MS) {
                                lastReport = now;
                                postProgress(copied, total);
                            }
                        }
                        session.fsync(out);
                    }
                }
                postProgress(total, total);
                if (cancelRequested) {
                    throw new IOException(CANCELLED);
                }

                // Explicit component + FLAG_MUTABLE: the system fills in the status extras,
                // and Android 14+ only accepts a mutable PendingIntent for an explicit intent.
                Intent callback = new Intent(context, InstallResultReceiver.class);
                PendingIntent pending = PendingIntent.getBroadcast(
                        context, sessionId, callback,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
                session.commit(pending.getIntentSender());
                committed = true;
                awaitingInstaller = true;
            }
        } catch (IOException | RuntimeException e) {
            if (!committed) {
                abandonQuietly(installer, sessionId);
                final String reason = describe(e);
                MAIN.post(() -> finish(false, reason));
            }
            // Once committed the session belongs to the installer; its broadcast reports the result.
            return;
        }

        if (cancelRequested) {
            // Cancel arrived between the last check and the commit.
            abandonQuietly(installer, sessionId);
            MAIN.post(() -> finish(false, CANCELLED));
        }
    }

    /** Entry point for InstallResultReceiver; runs on the main thread. */
    static void onInstallerStatus(Context appContext, int status, int sessionId, String message,
            Intent confirm) {
        boolean current = running && sessionId == activeSessionId;

        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            if (current && confirm != null && listener != null) {
                listener.onConfirmationNeeded(confirm);
                return;
            }
            // No screen can show the confirmation, so the session would wait forever.
            abandonQuietly(appContext.getPackageManager().getPackageInstaller(), sessionId);
            if (current) {
                finish(false, "The install confirmation could not be shown. Start the clone again.");
            }
            return;
        }

        boolean success = status == PackageInstaller.STATUS_SUCCESS;
        if (current) {
            if (!finish(success, message)) {
                Ui.toast(appContext, success ? "Clone installed." : "Clone failed: " + message);
            }
        } else if (success) {
            // Result of a session from before this process was restarted.
            Ui.toast(appContext, "Clone installed.");
        }
    }

    /**
     * Ends the current job exactly once; later calls for the same job are ignored.
     *
     * @return true when a listener received the result
     */
    private static boolean finish(boolean success, String message) {
        if (!running) {
            return false;
        }
        running = false;
        awaitingInstaller = false;
        activeSessionId = NO_SESSION;
        if (listener == null) {
            return false;
        }
        listener.onFinished(success, message);
        return true;
    }

    private static void postProgress(final long copied, final long total) {
        MAIN.post(() -> {
            if (listener != null) {
                listener.onProgress(copied, total);
            }
        });
    }

    private static void abandonQuietly(PackageInstaller installer, int sessionId) {
        if (sessionId == NO_SESSION) {
            return;
        }
        try {
            installer.abandonSession(sessionId);
        } catch (RuntimeException ignored) {
            // Already gone or already installing; nothing left to clean up.
        }
    }

    private static String describe(Exception e) {
        String message = e.getMessage();
        return message == null || message.isEmpty() ? e.getClass().getSimpleName() : message;
    }
}
