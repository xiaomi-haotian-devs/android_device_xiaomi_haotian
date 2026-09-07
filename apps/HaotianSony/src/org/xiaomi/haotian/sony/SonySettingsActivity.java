// SPDX-License-Identifier: Apache-2.0
package org.xiaomi.haotian.sony;
import android.os.Bundle;
import com.android.settingslib.collapsingtoolbar.CollapsingToolbarBaseActivity;

public final class SonySettingsActivity extends CollapsingToolbarBaseActivity {
    public static final String EXTRA_ADDRESS = "org.xiaomi.haotian.sony.extra.DEVICE_ADDRESS";
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setTitle(R.string.app_name);
        if (state == null) {
            SonySettingsFragment fragment = new SonySettingsFragment();
            Bundle args = new Bundle();
            args.putString(EXTRA_ADDRESS, getIntent().getStringExtra(EXTRA_ADDRESS));
            fragment.setArguments(args);
            getSupportFragmentManager().beginTransaction()
                    .replace(com.android.settingslib.collapsingtoolbar.R.id.content_frame, fragment)
                    .commit();
        }
    }
}
