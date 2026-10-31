/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime;

import android.app.Activity;
import android.content.Intent;
import android.content.Context;
import android.os.BadParcelableException;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;

/** Runs in the test APK UID and launches the real exported settings activity. */
public final class ExternalSettingsRouteFixtureActivity extends Activity {
    public static final class BrokenRoute implements Parcelable {
        public static final Creator<BrokenRoute> CREATOR = new Creator<BrokenRoute>() {
            @Override public BrokenRoute createFromParcel(Parcel input) { throw new BadParcelableException("synthetic broken route"); }
            @Override public BrokenRoute[] newArray(int size) { return new BrokenRoute[size]; }
        };
        @Override public int describeContents() { return 0; }
        @Override public void writeToParcel(Parcel output, int flags) { output.writeInt(1); }
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Intent target = new Intent(Intent.ACTION_RUN)
                .setClassName("com.x2m7.xrime", "com.osfans.trime.ui.main.MainActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        String mode = getIntent().getStringExtra("mode");
        String key = "com.x2m7.xrime.EXTRA_SETTINGS_ROUTE";
        if ("wrong".equals(mode)) target.putExtra(key, "not a route");
        if ("broken".equals(mode)) target.putExtra(key, new BrokenRoute());
        if ("other".equals(mode)) target.putExtra(key, new Bundle());
        if ("valid".equals(mode)) {
            try {
                Context targetCode = createPackageContext("com.x2m7.xrime", Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY);
                Class<?> route = targetCode.getClassLoader().loadClass("com.osfans.trime.ui.main.NavigationRoute$General");
                target.putExtra(key, (Parcelable) route.getField("INSTANCE").get(null));
            } catch (ReflectiveOperationException | android.content.pm.PackageManager.NameNotFoundException e) {
                throw new IllegalStateException("Cannot construct fixture route", e);
            }
        }
        startActivity(target);
        finish();
    }
}
