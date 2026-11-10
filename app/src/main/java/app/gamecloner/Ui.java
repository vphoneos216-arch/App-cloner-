package app.gamecloner;

import android.content.Context;
import android.graphics.Insets;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Toast;

/** Small view helpers shared by the activities. */
final class Ui {
    private Ui() {
    }

    static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    /**
     * Adds the system-bar and cutout insets that still reach the view to its padding.
     * On Android 15+ (target 35) windows are edge-to-edge, so the content must do this itself;
     * on older versions the insets arrive as zero and nothing changes.
     */
    static void padForSystemBars(View view) {
        final int left = view.getPaddingLeft();
        final int top = view.getPaddingTop();
        final int right = view.getPaddingRight();
        final int bottom = view.getPaddingBottom();
        view.setOnApplyWindowInsetsListener((v, insets) -> {
            Insets bars = insets.getInsets(
                    WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            v.setPadding(left + bars.left, top + bars.top, right + bars.right, bottom + bars.bottom);
            return insets;
        });
    }

    static void toast(Context context, String text) {
        Toast.makeText(context, text, Toast.LENGTH_LONG).show();
    }

    static String megabytes(long bytes) {
        return String.valueOf(bytes / (1024L * 1024L));
    }
}
