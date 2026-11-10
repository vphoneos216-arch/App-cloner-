package app.gamecloner;

import android.app.admin.DeviceAdminReceiver;
import android.content.Context;
import android.content.Intent;

/** Profile owner of the clone space. Receives the provisioning-complete broadcast inside it. */
public class AdminReceiver extends DeviceAdminReceiver {
    @Override
    public void onProfileProvisioningComplete(Context context, Intent intent) {
        ProfileSetup.apply(context);
    }
}
