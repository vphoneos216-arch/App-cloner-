package com.akash.appcloner

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Profile owner receiver for the managed-profile backend.
 *
 * Provisioning is what grants the ability to install an existing package into the work profile.
 * No other policy is requested, and nothing here touches the primary profile.
 */
class CloneAdminReceiver : DeviceAdminReceiver() {

    override fun onProfileProvisioningComplete(context: Context, intent: Intent) {
        super.onProfileProvisioningComplete(context, intent)
        Log.i(TAG, "work profile provisioned; this app is now profile owner")
    }

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        Log.i(TAG, "device admin enabled")
    }

    private companion object {
        const val TAG = "AppCloner/Admin"
    }
}
