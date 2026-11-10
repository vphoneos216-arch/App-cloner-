package app.gamecloner;

import android.app.Activity;
import android.os.Bundle;

/**
 * Second delivery path for "provisioning finished" (the first is AdminReceiver).
 * Invisible: applies the profile setup and finishes before it is ever shown, which is
 * what Theme.NoDisplay requires.
 */
public class ProvisionedActivity extends Activity {
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        ProfileSetup.apply(this);
        finish();
    }
}
