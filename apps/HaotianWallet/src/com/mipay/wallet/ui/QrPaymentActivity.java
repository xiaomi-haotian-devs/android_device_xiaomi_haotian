package com.mipay.wallet.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.hardware.biometrics.BiometricManager;
import android.hardware.biometrics.BiometricPrompt;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.mipay.wallet.R;
import com.mipay.wallet.qr.QrRouting;

/** User-visible confirmation boundary before a QR payload enters MINextpay. */
public final class QrPaymentActivity extends Activity {
    public static final String EXTRA_PAYLOAD = "payString";

    private static final String NEXT_PAY_PACKAGE = "com.miui.nextpay";
    private static final String NEXT_PAY_ACTION = "com.miui.nextpay.action.SCAN_PAY";
    private static final String SECURITY_PREFERENCES = "wallet_security";
    private static final String REQUIRE_USER_AUTHENTICATION = "require_user_authentication";
    private static final String LINK_ALIAS =
            "com.mipay.wallet.ui.QrPaymentLinkActivity";

    private QrRouting.Match match;
    private boolean validPaymentEntry;
    private CancellationSignal authenticationCancellation;
    private int background;
    private int surface;
    private int primary;
    private int secondary;
    private int accent;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);
        getWindow().setDecorFitsSystemWindows(false);

        background = getColor(R.color.wallet_background);
        surface = getColor(R.color.wallet_surface);
        primary = getColor(R.color.wallet_text_primary);
        secondary = getColor(R.color.wallet_text_secondary);
        accent = getColor(R.color.wallet_blue);

        String payload = getIntent().getDataString();
        if (payload == null) {
            payload = getIntent().getStringExtra(EXTRA_PAYLOAD);
        }
        if (payload == null) {
            payload = getIntent().getStringExtra("result");
        }
        match = QrRouting.classify(this, payload);
        String component = getIntent().getComponent() == null ? ""
                : getIntent().getComponent().getClassName();
        boolean webLink = Intent.ACTION_VIEW.equals(getIntent().getAction());
        validPaymentEntry = (webLink && match.type == QrRouting.MatchType.FIXED)
                || (!webLink && QrPaymentActivity.class.getName().equals(component)
                        && match.isPayment());
        setContentView(buildContent());
        configureSystemBars();
    }

    @Override
    protected void onDestroy() {
        if (authenticationCancellation != null) {
            authenticationCancellation.cancel();
            authenticationCancellation = null;
        }
        super.onDestroy();
    }

    private LinearLayout buildContent() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_VERTICAL);
        root.setPadding(dp(24), dp(24), dp(24), dp(24));
        root.setBackgroundColor(background);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            android.graphics.Insets bars = insets.getInsets(
                    WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            view.setPadding(dp(24) + bars.left, dp(24) + bars.top,
                    dp(24) + bars.right, dp(24) + bars.bottom);
            return insets;
        });

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(24), dp(26), dp(24), dp(22));
        card.setBackground(rounded(surface, 22));
        card.setElevation(dp(2));
        root.addView(card, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = text(validPaymentEntry
                ? R.string.qr_payment_title : R.string.qr_unsupported_title, 25, primary);
        title.setTypeface(null, Typeface.BOLD);
        card.addView(title);

        TextView source = new TextView(this);
        source.setText(validPaymentEntry ? sourceLabel()
                : getString(R.string.qr_unsupported_desc));
        source.setTextSize(15);
        source.setTextColor(secondary);
        source.setPadding(0, dp(10), 0, 0);
        card.addView(source);

        if (!match.displayHost.isEmpty()) {
            TextView host = new TextView(this);
            host.setText(match.displayHost);
            host.setTextSize(18);
            host.setTextColor(primary);
            host.setTypeface(null, Typeface.BOLD);
            host.setPadding(0, dp(24), 0, 0);
            card.addView(host);
        }

        TextView payload = new TextView(this);
        payload.setText(shortPayload(match.payload));
        payload.setTextSize(13);
        payload.setTextColor(secondary);
        payload.setMaxLines(5);
        payload.setTextIsSelectable(true);
        payload.setPadding(0, dp(12), 0, dp(22));
        card.addView(payload);

        if (validPaymentEntry) {
            Button pay = button(R.string.qr_continue_unionpay, true);
            pay.setOnClickListener(v -> authenticateAndPay());
            card.addView(pay, matchButtonParams());
        } else if (QrRouting.isSafeWebUri(match.payload)) {
            Button browser = button(R.string.qr_open_browser, true);
            browser.setOnClickListener(v -> openBrowser());
            card.addView(browser, matchButtonParams());
        }

        Button cancel = button(R.string.cancel, false);
        cancel.setOnClickListener(v -> finish());
        LinearLayout.LayoutParams cancelParams = matchButtonParams();
        cancelParams.setMargins(0, dp(10), 0, 0);
        card.addView(cancel, cancelParams);
        root.requestApplyInsets();
        return root;
    }

    private String sourceLabel() {
        switch (match.type) {
            case FIXED:
                return getString(R.string.qr_source_fixed);
            case CLOUD:
                return getString(R.string.qr_source_cloud);
            case WECHAT:
                return getString(R.string.qr_source_wechat);
            default:
                return "";
        }
    }

    private void authenticateAndPay() {
        if (authenticationCancellation != null) {
            return;
        }
        boolean required = getSharedPreferences(SECURITY_PREFERENCES, MODE_PRIVATE)
                .getBoolean(REQUIRE_USER_AUTHENTICATION, false);
        if (!required) {
            launchNextPay();
            return;
        }

        int authenticators = BiometricManager.Authenticators.BIOMETRIC_WEAK
                | BiometricManager.Authenticators.DEVICE_CREDENTIAL;
        BiometricManager manager = getSystemService(BiometricManager.class);
        if (manager == null || manager.canAuthenticate(authenticators)
                != BiometricManager.BIOMETRIC_SUCCESS) {
            Toast.makeText(this, R.string.wallet_auth_unavailable, Toast.LENGTH_LONG).show();
            return;
        }

        authenticationCancellation = new CancellationSignal();
        BiometricPrompt prompt = new BiometricPrompt.Builder(this)
                .setTitle(getString(R.string.wallet_auth_title))
                .setSubtitle(getString(R.string.qr_auth_subtitle))
                .setAllowedAuthenticators(authenticators)
                .build();
        prompt.authenticate(authenticationCancellation, getMainExecutor(),
                new BiometricPrompt.AuthenticationCallback() {
                    @Override
                    public void onAuthenticationSucceeded(
                            BiometricPrompt.AuthenticationResult result) {
                        authenticationCancellation = null;
                        launchNextPay();
                    }

                    @Override
                    public void onAuthenticationError(int errorCode, CharSequence errorString) {
                        authenticationCancellation = null;
                    }
                });
    }

    private void launchNextPay() {
        Intent intent = new Intent(NEXT_PAY_ACTION);
        intent.setPackage(NEXT_PAY_PACKAGE);
        intent.putExtra(EXTRA_PAYLOAD, match.payload);
        if (!isScanPaySupported(intent)) {
            showComponentMissing();
            return;
        }
        try {
            startActivity(intent);
            finish();
        } catch (ActivityNotFoundException | SecurityException e) {
            showComponentMissing();
        }
    }

    private boolean isScanPaySupported(Intent intent) {
        try {
            Bundle features = getContentResolver().call(
                    Uri.parse("content://com.miui.tsmclient.provider.feature"),
                    "feature", null, null);
            int featureValue = features == null ? 0 : features.getInt("feature_value", 0);
            return (featureValue & 0x200) != 0
                    && getPackageManager().resolveActivity(intent,
                            PackageManager.MATCH_DEFAULT_ONLY) != null;
        } catch (Exception e) {
            return false;
        }
    }

    private void showComponentMissing() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.qr_component_missing_title)
                .setMessage(R.string.qr_component_missing_desc)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private void openBrowser() {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(match.payload));
            intent.addCategory(Intent.CATEGORY_BROWSABLE);
            Intent chooser = Intent.createChooser(intent, getString(R.string.qr_open_browser));
            chooser.putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS,
                    new ComponentName[]{new ComponentName(getPackageName(), LINK_ALIAS)});
            startActivity(chooser);
            finish();
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, R.string.no_browser, Toast.LENGTH_SHORT).show();
        }
    }

    private Button button(int textRes, boolean prominent) {
        Button button = new Button(this);
        button.setText(textRes);
        button.setTextSize(16);
        button.setAllCaps(false);
        button.setTextColor(prominent ? Color.WHITE : primary);
        int color = prominent ? accent : getColor(R.color.wallet_blue_soft);
        int rippleColor = (accent & 0x00ffffff) | 0x33000000;
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(rippleColor),
                rounded(color, 16), null));
        return button;
    }

    private TextView text(int stringRes, int sp, int color) {
        TextView view = new TextView(this);
        view.setText(stringRes);
        view.setTextSize(sp);
        view.setTextColor(color);
        return view;
    }

    private GradientDrawable rounded(int color, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radiusDp));
        return drawable;
    }

    private LinearLayout.LayoutParams matchButtonParams() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52));
    }

    private String shortPayload(String payload) {
        if (payload.length() <= 320) {
            return payload;
        }
        return payload.substring(0, 320) + "…";
    }

    private void configureSystemBars() {
        WindowInsetsController controller = getWindow().getDecorView().getWindowInsetsController();
        if (controller != null) {
            int light = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                    | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
            controller.setSystemBarsAppearance(light, light);
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
