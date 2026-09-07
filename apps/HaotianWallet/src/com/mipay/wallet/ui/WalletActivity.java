package com.mipay.wallet.ui;

import android.accounts.AccountManager;
import android.app.Activity;
import android.hardware.biometrics.BiometricManager;
import android.hardware.biometrics.BiometricPrompt;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;
import android.widget.FrameLayout;
import android.widget.CompoundButton;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Space;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.mipay.wallet.R;

/**
 * Privacy-focused launcher for Xiaomi Smart Cards.
 *
 * This activity contains no analytics or advertising integration. The wallet's only direct
 * network operation is a user-confirmed download of the optional scan-payment rule snapshot;
 * all card operations are delegated to the separately installed com.miui.tsmclient backend
 * after an explicit tap.
 */
public final class WalletActivity extends Activity {
    private static final long AUTHENTICATION_GRACE_PERIOD_MS = 3000L;
    private static final int PAGE_HOME = 0;
    private static final int PAGE_MINE = 1;
    private static final int PAGE_WALLET_SETTINGS = 2;
    private static final int PAGE_PRIVACY = 3;
    private static final String SECURITY_PREFERENCES = "wallet_security";
    private static final String REQUIRE_USER_AUTHENTICATION =
            "require_user_authentication";
    private static final String TSM_PACKAGE = "com.miui.tsmclient";
    private static final String ACTION_TRAFFIC =
            "com.miui.tsmclient.action.INTRODUCTION";
    private static final String ACTION_CARD_EMULATION_SETTINGS =
            "android.settings.MANAGE_OTHER_NFC_SERVICES_SETTINGS";
    private static final Uri URI_SMART_CARD_SETTINGS = Uri.parse(
            "https://tsmclient.miui.com?action=uni_settings&type=0"
                    + "&source_channel=wallet");
    private static final Uri URI_DOOR = Uri.parse(
            "https://tsmclient.miui.com?action=issue&type=MIFARE_ENTRANCE"
                    + "&source_channel=mipay");
    private static final Uri URI_MIPAY = Uri.parse(
            "tsmclient://card?type=BANKCARD&action=mipay_list"
                    + "&source_channel=miwallet");

    private FrameLayout content;
    private LinearLayout bottomBar;
    private View walletRoot;
    private int currentPage = PAGE_HOME;
    private boolean authenticated;
    private boolean authenticationInProgress;
    private boolean startedOnce;
    private long backgroundedAt = -1L;
    private CancellationSignal authenticationCancellation;
    private final OnBackInvokedCallback backCallback = this::handleBackInvocation;

    private int background;
    private int surface;
    private int primary;
    private int secondary;
    private int accent;
    private int accentSoft;
    private int divider;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        loadColors();
        configureWindow();
        getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT, backCallback);

        // Keep card and account details out of the window until the system has
        // authenticated the current user.
        FrameLayout authenticationGate = new FrameLayout(this);
        authenticationGate.setBackgroundColor(background);
        setContentView(authenticationGate);
        configureSystemBarsAppearance();
        if (isUserAuthenticationRequired()) {
            requestUserAuthentication();
        } else {
            showAuthenticatedContent();
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (startedOnce && authenticated && isUserAuthenticationRequired()
                && backgroundedAt >= 0L
                && SystemClock.elapsedRealtime() - backgroundedAt
                        > AUTHENTICATION_GRACE_PERIOD_MS) {
            lockAuthenticatedContent();
            requestUserAuthentication();
        }
        startedOnce = true;
        backgroundedAt = -1L;
    }

    @Override
    protected void onStop() {
        if (!isChangingConfigurations() && authenticated
                && isUserAuthenticationRequired()) {
            backgroundedAt = SystemClock.elapsedRealtime();
        }
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        if (authenticationInProgress && authenticationCancellation != null) {
            authenticationCancellation.cancel();
        }
        authenticationCancellation = null;
        getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(backCallback);
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        handleBackInvocation();
    }

    private void handleBackInvocation() {
        if (!authenticated) {
            finish();
            return;
        }
        navigateBack();
    }

    private void requestUserAuthentication() {
        if (authenticationInProgress || isFinishing() || isDestroyed()) {
            return;
        }
        final int authenticators = allowedAuthenticators();
        BiometricManager manager = getSystemService(BiometricManager.class);
        if (manager == null || manager.canAuthenticate(authenticators)
                != BiometricManager.BIOMETRIC_SUCCESS) {
            Toast.makeText(this, R.string.wallet_auth_unavailable, Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        authenticationInProgress = true;
        authenticationCancellation = new CancellationSignal();
        BiometricPrompt prompt = new BiometricPrompt.Builder(this)
                .setTitle(getString(R.string.wallet_auth_title))
                .setSubtitle(getString(R.string.wallet_auth_subtitle))
                .setAllowedAuthenticators(authenticators)
                .build();
        prompt.authenticate(authenticationCancellation, getMainExecutor(),
                new BiometricPrompt.AuthenticationCallback() {
                    @Override
                    public void onAuthenticationSucceeded(
                            BiometricPrompt.AuthenticationResult result) {
                        showAuthenticatedContent();
                    }

                    @Override
                    public void onAuthenticationError(int errorCode,
                            CharSequence errorString) {
                        authenticationInProgress = false;
                        authenticationCancellation = null;
                        if (!authenticated && !isFinishing()) {
                            finish();
                        }
                    }

                    @Override
                    public void onAuthenticationFailed() {
                        // The system prompt remains open so another biometric or
                        // the device credential fallback can still be used.
                    }
                });
    }

    private int allowedAuthenticators() {
        return BiometricManager.Authenticators.BIOMETRIC_WEAK
                | BiometricManager.Authenticators.DEVICE_CREDENTIAL;
    }

    private boolean canUseUserAuthentication() {
        BiometricManager manager = getSystemService(BiometricManager.class);
        return manager != null
                && manager.canAuthenticate(allowedAuthenticators())
                == BiometricManager.BIOMETRIC_SUCCESS;
    }

    private boolean isUserAuthenticationRequired() {
        return getSharedPreferences(SECURITY_PREFERENCES, MODE_PRIVATE)
                .getBoolean(REQUIRE_USER_AUTHENTICATION, false);
    }

    private void setUserAuthenticationRequired(boolean required) {
        SharedPreferences preferences = getSharedPreferences(
                SECURITY_PREFERENCES, MODE_PRIVATE);
        preferences.edit().putBoolean(REQUIRE_USER_AUTHENTICATION, required).apply();
    }

    private void showAuthenticatedContent() {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        authenticated = true;
        authenticationInProgress = false;
        authenticationCancellation = null;
        if (walletRoot == null) {
            walletRoot = buildShell();
            setContentView(walletRoot);
            configureSystemBarsAppearance();
            showHome();
        } else {
            walletRoot.setVisibility(View.VISIBLE);
        }
    }

    private void lockAuthenticatedContent() {
        authenticated = false;
        if (walletRoot != null) {
            walletRoot.setVisibility(View.INVISIBLE);
        }
    }

    private void loadColors() {
        background = getColor(R.color.wallet_background);
        surface = getColor(R.color.wallet_surface);
        primary = getColor(R.color.wallet_text_primary);
        secondary = getColor(R.color.wallet_text_secondary);
        accent = getColor(R.color.wallet_blue);
        accentSoft = getColor(R.color.wallet_blue_soft);
        divider = getColor(R.color.wallet_divider);
    }

    private void configureWindow() {
        Window window = getWindow();
        window.setDecorFitsSystemWindows(false);
        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(Color.TRANSPARENT);
    }

    private void configureSystemBarsAppearance() {
        // PhoneWindow has no DecorView before setContentView(). Asking it for an insets
        // controller earlier crashes on BP4A, so configure icon contrast only afterwards.
        WindowInsetsController controller = getWindow().getDecorView().getWindowInsetsController();
        if (controller != null) {
            int lightBars = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                    | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
            controller.setSystemBarsAppearance(
                    Color.luminance(background) > 0.5f ? lightBars : 0, lightBars);
        }
    }

    private View buildShell() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(background);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            android.graphics.Insets bars = insets.getInsets(
                    WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });

        content = new FrameLayout(this);
        root.addView(content, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        bottomBar = new LinearLayout(this);
        bottomBar.setOrientation(LinearLayout.HORIZONTAL);
        bottomBar.setGravity(Gravity.CENTER);
        bottomBar.setPadding(dp(28), dp(6), dp(28), dp(6));
        bottomBar.setBackgroundColor(surface);
        bottomBar.setElevation(dp(8));
        root.addView(bottomBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(72)));
        root.requestApplyInsets();
        return root;
    }

    private void showHome() {
        currentPage = PAGE_HOME;
        bottomBar.setVisibility(View.VISIBLE);
        content.removeAllViews();
        content.addView(buildHomePage());
        rebuildTabs(true);
    }

    private void showMine() {
        currentPage = PAGE_MINE;
        bottomBar.setVisibility(View.VISIBLE);
        content.removeAllViews();
        content.addView(buildMinePage());
        rebuildTabs(false);
    }

    private View buildHomePage() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);

        LinearLayout body = vertical();
        body.setPadding(dp(20), dp(28), dp(20), dp(32));
        scroll.addView(body, matchWrap());

        body.addView(title(R.string.wallet_title, 30));
        TextView subtitle = text(R.string.wallet_subtitle, 15, secondary);
        subtitle.setPadding(0, dp(6), 0, dp(26));
        body.addView(subtitle);

        LinearLayout features = new LinearLayout(this);
        features.setOrientation(LinearLayout.HORIZONTAL);
        features.setGravity(Gravity.TOP);
        body.addView(features, matchWrap());

        addFeature(features, R.drawable.ic_train, R.string.traffic_card,
                R.string.traffic_card_desc, v -> openTraffic());
        addFeature(features, R.drawable.ic_key, R.string.door_key,
                R.string.door_key_desc, v -> openUri(URI_DOOR));
        addFeature(features, R.drawable.ic_card, R.string.mi_pay,
                R.string.mi_pay_desc, v -> openUri(URI_MIPAY));

        Space paymentGap = new Space(this);
        body.addView(paymentGap, new LinearLayout.LayoutParams(1, dp(18)));
        LinearLayout paymentGroup = vertical();
        paymentGroup.setBackground(rounded(surface, 20));
        paymentGroup.setElevation(dp(1));
        body.addView(paymentGroup, matchWrap());
        addSettingsRow(paymentGroup, R.drawable.ic_qr_scan, R.string.qr_scan_pay,
                R.string.qr_scan_pay_desc, v -> openQrScanner(), false);

        return scroll;
    }

    private View buildMinePage() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout body = vertical();
        body.setPadding(dp(20), dp(28), dp(20), dp(32));
        scroll.addView(body, matchWrap());

        body.addView(title(R.string.mine_title, 30));
        Space gap = new Space(this);
        body.addView(gap, new LinearLayout.LayoutParams(1, dp(24)));

        LinearLayout group = vertical();
        group.setBackground(rounded(surface, 20));
        group.setElevation(dp(1));
        body.addView(group, matchWrap());

        addSettingsRow(group, R.drawable.ic_account, R.string.xiaomi_account,
                R.string.xiaomi_account_desc, v -> openAccountSettings(), false);
        addSettingsRow(group, R.drawable.ic_settings, R.string.wallet_settings,
                R.string.wallet_settings_desc, v -> showWalletSettings(), true);
        addSettingsRow(group, R.drawable.ic_nfc, R.string.smart_card_settings,
                R.string.smart_card_settings_desc, v -> openSmartCardSettings(), true);
        addSettingsRow(group, R.drawable.ic_privacy, R.string.privacy,
                R.string.privacy_desc, v -> showPrivacy(), true);

        String version = "6.113.1.5728.2741-haotian6";
        try {
            version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception ignored) {
        }
        TextView versionView = new TextView(this);
        versionView.setText(getString(R.string.version_format, version));
        versionView.setTextSize(13);
        versionView.setTextColor(secondary);
        versionView.setGravity(Gravity.CENTER);
        versionView.setPadding(0, dp(28), 0, 0);
        body.addView(versionView, matchWrap());
        return scroll;
    }

    private void showWalletSettings() {
        currentPage = PAGE_WALLET_SETTINGS;
        bottomBar.setVisibility(View.GONE);
        content.removeAllViews();

        LinearLayout page = vertical();
        page.setPadding(dp(20), dp(12), dp(20), dp(24));
        page.addView(toolbar(R.string.wallet_settings));

        TextView description = text(R.string.wallet_settings_page_desc, 15, secondary);
        description.setPadding(dp(4), dp(12), dp(4), dp(20));
        page.addView(description, matchWrap());

        LinearLayout group = vertical();
        group.setBackground(rounded(surface, 20));
        page.addView(group, matchWrap());
        addSwitchSettingsRow(group, R.drawable.ic_key, R.string.require_authentication,
                R.string.require_authentication_desc, isUserAuthenticationRequired(),
                (button, checked) -> {
                    if (checked && !canUseUserAuthentication()) {
                        button.setChecked(false);
                        Toast.makeText(this, R.string.wallet_auth_unavailable,
                                Toast.LENGTH_LONG).show();
                        return;
                    }
                    setUserAuthenticationRequired(checked);
                }, false);
        addSettingsRow(group, R.drawable.ic_card, R.string.default_card_service,
                R.string.default_card_service_desc, v -> openCardEmulationSettings(), true);
        addSettingsRow(group, R.drawable.ic_nfc, R.string.system_nfc_settings,
                R.string.system_nfc_settings_desc, v -> openNfcSettings(), true);
        addSettingsRow(group, R.drawable.ic_settings, R.string.smart_card_settings,
                R.string.smart_card_settings_desc, v -> openSmartCardSettings(), true);
        content.addView(page);
    }

    private void showPrivacy() {
        currentPage = PAGE_PRIVACY;
        bottomBar.setVisibility(View.GONE);
        content.removeAllViews();

        LinearLayout page = vertical();
        page.setPadding(dp(20), dp(12), dp(20), dp(24));
        page.addView(toolbar(R.string.privacy_title));

        LinearLayout card = vertical();
        card.setPadding(dp(22), dp(22), dp(22), dp(22));
        card.setBackground(rounded(surface, 20));
        LinearLayout.LayoutParams params = matchWrap();
        params.setMargins(0, dp(16), 0, 0);
        page.addView(card, params);

        ImageView icon = icon(R.drawable.ic_privacy, dp(40));
        card.addView(icon, new LinearLayout.LayoutParams(dp(40), dp(40)));
        TextView body = text(R.string.privacy_body, 16, primary);
        body.setLineSpacing(0f, 1.25f);
        body.setPadding(0, dp(18), 0, 0);
        card.addView(body, matchWrap());
        content.addView(page);
    }

    private View toolbar(int titleRes) {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);

        ImageButton back = new ImageButton(this);
        back.setImageResource(R.drawable.ic_back);
        back.setImageTintList(ColorStateList.valueOf(primary));
        back.setContentDescription(getString(R.string.back));
        back.setBackground(ripple(Color.TRANSPARENT, 24));
        back.setOnClickListener(v -> navigateBack());
        bar.addView(back, new LinearLayout.LayoutParams(dp(48), dp(48)));

        TextView label = title(titleRes, 24);
        label.setPadding(dp(6), 0, 0, 0);
        bar.addView(label, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return bar;
    }

    private void addFeature(LinearLayout parent, int iconRes, int titleRes,
            int descriptionRes, View.OnClickListener listener) {
        LinearLayout card = vertical();
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setPadding(dp(10), dp(18), dp(10), dp(16));
        card.setBackground(ripple(surface, 20));
        card.setElevation(dp(1));
        card.setClickable(true);
        card.setFocusable(true);
        card.setOnClickListener(listener);
        card.setContentDescription(getString(titleRes));

        LinearLayout iconBox = vertical();
        iconBox.setGravity(Gravity.CENTER);
        iconBox.setBackground(rounded(accentSoft, 18));
        iconBox.addView(icon(iconRes, dp(34)),
                new LinearLayout.LayoutParams(dp(34), dp(34)));
        card.addView(iconBox, new LinearLayout.LayoutParams(dp(64), dp(64)));

        TextView title = text(titleRes, 16, primary);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, dp(13), 0, 0);
        card.addView(title, matchWrap());

        TextView description = text(descriptionRes, 12, secondary);
        description.setGravity(Gravity.CENTER);
        description.setMaxLines(2);
        description.setPadding(0, dp(5), 0, 0);
        card.addView(description, matchWrap());

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(190), 1f);
        int margin = dp(5);
        params.setMargins(margin, 0, margin, 0);
        parent.addView(card, params);
    }

    private void addSettingsRow(LinearLayout parent, int iconRes, int titleRes,
            int descriptionRes, View.OnClickListener listener, boolean dividerAbove) {
        if (dividerAbove) {
            View line = new View(this);
            line.setBackgroundColor(divider);
            LinearLayout.LayoutParams lineParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
            lineParams.setMargins(dp(76), 0, dp(18), 0);
            parent.addView(line, lineParams);
        }

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(18), dp(14), dp(14), dp(14));
        row.setBackground(ripple(Color.TRANSPARENT, 20));
        row.setClickable(true);
        row.setFocusable(true);
        row.setOnClickListener(listener);
        row.setContentDescription(getString(titleRes));

        LinearLayout iconBox = vertical();
        iconBox.setGravity(Gravity.CENTER);
        iconBox.setBackground(rounded(accentSoft, 14));
        iconBox.addView(icon(iconRes, dp(27)),
                new LinearLayout.LayoutParams(dp(27), dp(27)));
        row.addView(iconBox, new LinearLayout.LayoutParams(dp(48), dp(48)));

        LinearLayout labels = vertical();
        labels.setPadding(dp(14), 0, dp(8), 0);
        TextView title = text(titleRes, 17, primary);
        labels.addView(title);
        TextView description = text(descriptionRes, 13, secondary);
        description.setPadding(0, dp(3), 0, 0);
        labels.addView(description);
        row.addView(labels, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        ImageView chevron = icon(R.drawable.ic_chevron, dp(20));
        row.addView(chevron, new LinearLayout.LayoutParams(dp(28), dp(28)));
        parent.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(82)));
    }

    private void addSwitchSettingsRow(LinearLayout parent, int iconRes, int titleRes,
            int descriptionRes, boolean checked,
            CompoundButton.OnCheckedChangeListener listener, boolean dividerAbove) {
        if (dividerAbove) {
            View line = new View(this);
            line.setBackgroundColor(divider);
            LinearLayout.LayoutParams lineParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
            lineParams.setMargins(dp(76), 0, dp(18), 0);
            parent.addView(line, lineParams);
        }

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(18), dp(14), dp(14), dp(14));
        row.setBackground(ripple(Color.TRANSPARENT, 20));
        row.setClickable(true);
        row.setFocusable(true);
        row.setContentDescription(getString(titleRes));

        LinearLayout iconBox = vertical();
        iconBox.setGravity(Gravity.CENTER);
        iconBox.setBackground(rounded(accentSoft, 14));
        iconBox.addView(icon(iconRes, dp(27)),
                new LinearLayout.LayoutParams(dp(27), dp(27)));
        row.addView(iconBox, new LinearLayout.LayoutParams(dp(48), dp(48)));

        LinearLayout labels = vertical();
        labels.setPadding(dp(14), 0, dp(8), 0);
        labels.addView(text(titleRes, 17, primary));
        TextView description = text(descriptionRes, 13, secondary);
        description.setPadding(0, dp(3), 0, 0);
        labels.addView(description);
        row.addView(labels, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Switch switchView = new Switch(this);
        switchView.setChecked(checked);
        switchView.setOnCheckedChangeListener(listener);
        row.setOnClickListener(view -> switchView.toggle());
        row.addView(switchView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        parent.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(82)));
    }

    private void rebuildTabs(boolean homeSelected) {
        bottomBar.removeAllViews();
        bottomBar.addView(tab(R.drawable.ic_home, R.string.home, homeSelected,
                        v -> showHome()),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        bottomBar.addView(tab(R.drawable.ic_person, R.string.mine, !homeSelected,
                        v -> showMine()),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
    }

    private View tab(int iconRes, int labelRes, boolean selected,
            View.OnClickListener listener) {
        LinearLayout tab = vertical();
        tab.setGravity(Gravity.CENTER);
        tab.setClickable(true);
        tab.setFocusable(true);
        tab.setBackground(ripple(Color.TRANSPARENT, 24));
        tab.setOnClickListener(listener);
        tab.setContentDescription(getString(labelRes));

        ImageView image = icon(iconRes, dp(25));
        image.setImageTintList(ColorStateList.valueOf(selected ? accent : secondary));
        tab.addView(image, new LinearLayout.LayoutParams(dp(25), dp(25)));
        TextView label = text(labelRes, 12, selected ? accent : secondary);
        label.setGravity(Gravity.CENTER);
        label.setPadding(0, dp(3), 0, 0);
        tab.addView(label);
        return tab;
    }

    private void openTraffic() {
        Intent intent = new Intent(ACTION_TRAFFIC);
        intent.setPackage(TSM_PACKAGE);
        launch(intent);
    }

    private void openUri(Uri uri) {
        Intent intent = new Intent(Intent.ACTION_VIEW, uri);
        intent.setPackage(TSM_PACKAGE);
        launch(intent);
    }

    private void openNfcSettings() {
        launch(new Intent(Settings.ACTION_NFC_SETTINGS));
    }

    private void openCardEmulationSettings() {
        launch(new Intent(ACTION_CARD_EMULATION_SETTINGS));
    }

    private void openSmartCardSettings() {
        openUri(URI_SMART_CARD_SETTINGS);
    }

    private void openQrScanner() {
        launch(new Intent(this, QrScannerActivity.class));
    }

    private void openAccountSettings() {
        boolean loggedIn = AccountManager.get(this)
                .getAccountsByType("com.xiaomi").length > 0;
        Intent intent = new Intent(loggedIn
                ? "android.settings.XIAOMI_ACCOUNT_SYNC_SETTINGS"
                : "com.xiaomi.account.action.XIAOMI_ACCOUNT_LOGIN");
        intent.setPackage("com.xiaomi.account");
        launch(intent);
    }

    private void navigateBack() {
        if (currentPage == PAGE_WALLET_SETTINGS || currentPage == PAGE_PRIVACY) {
            showMine();
        } else if (currentPage == PAGE_MINE) {
            showHome();
        } else {
            finish();
        }
    }

    private void launch(Intent intent) {
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, R.string.component_missing, Toast.LENGTH_SHORT).show();
        }
    }

    private LinearLayout vertical() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private TextView title(int stringRes, int sp) {
        TextView view = text(stringRes, sp, primary);
        view.setTypeface(null, android.graphics.Typeface.BOLD);
        return view;
    }

    private TextView text(int stringRes, int sp, int color) {
        TextView view = new TextView(this);
        view.setText(stringRes);
        view.setTextSize(sp);
        view.setTextColor(color);
        return view;
    }

    private ImageView icon(int drawableRes, int size) {
        ImageView view = new ImageView(this);
        view.setImageResource(drawableRes);
        view.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        view.setAdjustViewBounds(true);
        view.setMinimumWidth(size);
        view.setMinimumHeight(size);
        return view;
    }

    private GradientDrawable rounded(int color, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radiusDp));
        return drawable;
    }

    private RippleDrawable ripple(int color, int radiusDp) {
        int rippleColor = (accent & 0x00ffffff) | 0x1f000000;
        return new RippleDrawable(ColorStateList.valueOf(rippleColor),
                rounded(color, radiusDp), null);
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
