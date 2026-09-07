/*
 * SPDX-FileCopyrightText: 2026 The Xiaomi haotian developers
 * SPDX-License-Identifier: Apache-2.0
 */

package org.xiaomi.haotian.audio;

import android.content.Context;
import android.content.Intent;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.android.settingslib.collapsingtoolbar.CollapsingToolbarBaseActivity;

import java.util.Locale;

/** Live third-person view of the normalized pose entering the head-tracker data path. */
public final class HeadTrackingDebugActivity extends CollapsingToolbarBaseActivity {
    private static final long UI_INTERVAL_MS = 33;

    private HeadOrientationView orientationView;
    private TextView connectionText;
    private TextView anglesText;
    private TextView streamText;
    private TextView latencyOverviewText;
    private TextView latencyStagesText;
    private TextView latencyOutputText;
    private HeadTrackingLatencyReporter latencyReporter;
    private NativeHeadTrackerReader nativeReader;
    private NativeHeadTrackerReader.Result nativeResult;
    private boolean updating;
    private int textUpdateDivider;

    private final Runnable updateUi = new Runnable() {
        @Override
        public void run() {
            if (!updating) return;
            HeadPoseDebugState.Snapshot snapshot = HeadPoseDebugState.snapshot();
            nativeResult = nativeReader.latest();
            if (nativeResult != null) snapshot = nativeResult.pose;
            orientationView.setSnapshot(snapshot,
                    nativeResult != null && nativeResult.sonyYawCorrected);
            if (++textUpdateDivider >= 3) {
                textUpdateDivider = 0;
                updateText(snapshot);
            }
            orientationView.postDelayed(this, UI_INTERVAL_MS);
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setTitle(R.string.head_tracking_debug_title);

        FrameLayout content = findViewById(
                com.android.settingslib.collapsingtoolbar.R.id.content_frame);
        content.removeAllViews();
        content.addView(createContent(), new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        latencyReporter = new HeadTrackingLatencyReporter(this);
        nativeReader = new NativeHeadTrackerReader(this);
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (orientationView == null) return;
        updating = true;
        latencyReporter.start();
        nativeReader.start();
        textUpdateDivider = 3;
        orientationView.removeCallbacks(updateUi);
        orientationView.post(updateUi);
    }

    @Override
    protected void onStop() {
        updating = false;
        if (orientationView != null) orientationView.removeCallbacks(updateUi);
        if (latencyReporter != null) latencyReporter.stop();
        if (nativeReader != null) nativeReader.stop();
        super.onStop();
    }

    private View createContent() {
        Context context = this;
        int horizontalPadding = dp(24);

        ScrollView scroll = new ScrollView(context);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);

        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(horizontalPadding, dp(16), horizontalPadding, dp(32));
        scroll.addView(column, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView intro = bodyText(R.string.head_tracking_debug_intro);
        column.addView(intro, matchWrap());

        orientationView = new HeadOrientationView(context);
        LinearLayout.LayoutParams visualizationParams = match(dp(390));
        visualizationParams.topMargin = dp(16);
        column.addView(orientationView, visualizationParams);

        TextView hint = bodyText(R.string.head_tracking_debug_hint);
        hint.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout.LayoutParams hintParams = matchWrap();
        hintParams.topMargin = dp(8);
        column.addView(hint, hintParams);

        Button recenter = new Button(context);
        recenter.setText(R.string.head_tracking_debug_recenter);
        recenter.setOnClickListener(view -> {
            orientationView.recenterNative();
            startService(new Intent(this, HaotianAudioService.class)
                    .setAction(HaotianAudioService.ACTION_RECENTER_HEAD_TRACKER));
            Toast.makeText(this, R.string.head_tracking_recenter_requested,
                    Toast.LENGTH_SHORT).show();
        });
        LinearLayout.LayoutParams buttonParams = wrapWrap();
        buttonParams.gravity = Gravity.CENTER_HORIZONTAL;
        buttonParams.topMargin = dp(8);
        column.addView(recenter, buttonParams);

        connectionText = detailCard();
        anglesText = detailCard();
        streamText = detailCard();
        addCard(column, connectionText);
        addCard(column, anglesText);
        addCard(column, streamText);

        TextView latencyTitle = sectionTitle(R.string.head_tracking_latency_title);
        LinearLayout.LayoutParams latencyTitleParams = matchWrap();
        latencyTitleParams.topMargin = dp(24);
        column.addView(latencyTitle, latencyTitleParams);
        TextView latencyIntro = bodyText(R.string.head_tracking_latency_intro);
        LinearLayout.LayoutParams latencyIntroParams = matchWrap();
        latencyIntroParams.topMargin = dp(4);
        column.addView(latencyIntro, latencyIntroParams);

        latencyOverviewText = detailCard();
        latencyStagesText = detailCard();
        latencyOutputText = detailCard();
        addCard(column, latencyOverviewText);
        addCard(column, latencyStagesText);
        addCard(column, latencyOutputText);
        return scroll;
    }

    private void updateText(HeadPoseDebugState.Snapshot snapshot) {
        if (!snapshot.hasOutput()) {
            connectionText.setText(R.string.head_tracking_debug_no_output);
        } else if (!snapshot.hasFrame()) {
            connectionText.setText(getString(R.string.head_tracking_debug_waiting,
                    snapshot.outputName));
        } else {
            String state = getString(snapshot.isStale()
                    ? R.string.head_tracking_debug_stale : R.string.head_tracking_debug_live);
            connectionText.setText(getString(R.string.head_tracking_debug_connection_format,
                    state, snapshot.outputName, snapshot.providerId));
        }
        if (!snapshot.error.isEmpty()) {
            connectionText.append("\n" + getString(
                    R.string.head_tracking_debug_error_format, snapshot.error));
        }

        float[] euler = orientationView.getEulerDegrees();
        anglesText.setText(getString(R.string.head_tracking_debug_angles_format,
                euler[0], euler[1], euler[2],
                snapshot.qx, snapshot.qy, snapshot.qz, snapshot.qw));

        String age = snapshot.ageNanos == Long.MAX_VALUE
                ? "—" : String.format(Locale.getDefault(), "%.1f ms",
                        snapshot.ageNanos / 1_000_000f);
        String transport = snapshot.transportNanos < 0
                ? "—" : String.format(Locale.getDefault(), "%.2f ms",
                        snapshot.transportNanos / 1_000_000f);
        streamText.setText(getString(R.string.head_tracking_debug_stream_format,
                snapshot.sampleRateHz, age, transport, snapshot.confidence,
                snapshot.vx, snapshot.vy, snapshot.vz,
                snapshot.discontinuityCount, snapshot.sequence));

        if (nativeResult != null) {
            streamText.append("\n" + nativeResult.details);
            latencyOverviewText.setText(R.string.head_tracking_native_latency_note);
            latencyStagesText.setVisibility(View.GONE);
            latencyOutputText.setVisibility(View.GONE);
        } else {
            latencyStagesText.setVisibility(View.VISIBLE);
            latencyOutputText.setVisibility(View.VISIBLE);
            updateLatencyText(latencyReporter.capture(snapshot));
        }
    }

    private void updateLatencyText(HeadTrackingLatencyReporter.Snapshot snapshot) {
        String estimate = formatMillis(snapshot.estimatedTotalMillis, 1);
        String effect = formatNanos(snapshot.poseToEffectNanos, 1);
        String output = snapshot.outputLatencyMillis < 0
                ? "—" : snapshot.outputLatencyMillis + " ms";
        String residual = formatSignedMillis(snapshot.residualMillis);
        String note = getString(snapshot.effectTapAvailable
                ? R.string.head_tracking_latency_estimate_note
                : R.string.head_tracking_latency_effect_tap_unavailable);
        latencyOverviewText.setText(getString(R.string.head_tracking_latency_overview_format,
                estimate, effect, output, snapshot.predictionMillis, residual, note));

        latencyStagesText.setText(getString(R.string.head_tracking_latency_stages_format,
                formatNanos(snapshot.decodeNanos, 2),
                formatNanos(snapshot.appDispatchNanos, 2),
                formatNanos(snapshot.sharedWriteNanos, 3),
                formatNanos(snapshot.halAcquireNanos, 2),
                formatNanos(snapshot.halPostNanos, 2), effect,
                snapshot.halSequence, snapshot.producerSequence));

        String mixer = snapshot.sampleRate > 0 && snapshot.framesPerBuffer > 0
                ? String.format(Locale.getDefault(), "%s · %d frames · %.2f ms",
                        formatRate(snapshot.sampleRate), snapshot.framesPerBuffer,
                        snapshot.mixerBlockMillis)
                : "—";
        latencyOutputText.setText(getString(R.string.head_tracking_latency_output_format,
                snapshot.codecSummary, formatMillis(snapshot.codecFrameMillis, 2), mixer,
                output, formatNanos(snapshot.effectAgeNanos, 1)));
    }

    private TextView bodyText(int textResource) {
        TextView text = new TextView(this);
        text.setText(textResource);
        text.setTextColor(themeColor(android.R.attr.textColorPrimary));
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        text.setLineSpacing(0, 1.15f);
        return text;
    }

    private TextView sectionTitle(int textResource) {
        TextView text = new TextView(this);
        text.setText(textResource);
        text.setTextColor(themeColor(android.R.attr.textColorPrimary));
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        text.setTypeface(text.getTypeface(), android.graphics.Typeface.BOLD);
        return text;
    }

    private static String formatNanos(long nanos, int digits) {
        return nanos < 0 ? "—" : formatMillis(
                HeadTrackingLatencyReporter.toMillis(nanos), digits);
    }

    private static String formatMillis(double millis, int digits) {
        if (!Double.isFinite(millis)) return "—";
        return String.format(Locale.getDefault(), "%." + digits + "f ms", millis);
    }

    private static String formatSignedMillis(double millis) {
        if (!Double.isFinite(millis)) return "—";
        return String.format(Locale.getDefault(), "%+.1f ms", millis);
    }

    private static String formatRate(int rate) {
        return rate % 1000 == 0
                ? String.format(Locale.getDefault(), "%d kHz", rate / 1000)
                : String.format(Locale.getDefault(), "%.1f kHz", rate / 1000f);
    }

    private TextView detailCard() {
        TextView text = new TextView(this);
        text.setTextColor(themeColor(android.R.attr.textColorPrimary));
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        text.setLineSpacing(0, 1.15f);
        text.setPadding(dp(16), dp(14), dp(16), dp(14));
        GradientDrawable background = new GradientDrawable();
        background.setColor(withAlpha(themeColor(android.R.attr.colorAccent), 20));
        background.setCornerRadius(dp(18));
        background.setStroke(dp(1), withAlpha(
                themeColor(android.R.attr.colorAccent), 60));
        text.setBackground(background);
        return text;
    }

    private void addCard(LinearLayout parent, View card) {
        LinearLayout.LayoutParams params = matchWrap();
        params.topMargin = dp(12);
        parent.addView(card, params);
    }

    private int themeColor(int attribute) {
        TypedArray values = obtainStyledAttributes(new int[] {attribute});
        try {
            return values.getColor(0, Color.WHITE);
        } finally {
            values.recycle();
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static int withAlpha(int color, int alpha) {
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }

    private static LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private static LinearLayout.LayoutParams wrapWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private static LinearLayout.LayoutParams match(int height) {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height);
    }

    /** Lightweight wireframe bust. Its body is fixed while the head follows the measured pose. */
    private static final class HeadOrientationView extends View {
        private static final int CIRCLE_SEGMENTS = 36;
        private static final int ARC_SEGMENTS = 24;

        private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint headPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint facePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint bodyPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint statusPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF card = new RectF();

        private HeadPoseDebugState.Snapshot snapshot = HeadPoseDebugState.snapshot();
        private float qx;
        private float qy;
        private float qz;
        private float qw = 1f;
        private float referenceX, referenceY, referenceZ, referenceW = 1f;
        private boolean sonyYawCorrected;
        private float m00 = 1f, m01, m02;
        private float m10, m11 = 1f, m12;
        private float m20, m21, m22 = 1f;
        private float projectedX;
        private float projectedY;

        HeadOrientationView(Context context) {
            super(context);
            setMinimumHeight(Math.round(dp(context, 320)));
            setContentDescription(context.getString(R.string.head_tracking_debug_visualization));

            int accent = resolveColor(context, android.R.attr.colorAccent, 0xff8ab4f8);
            int text = resolveColor(context, android.R.attr.textColorPrimary, Color.WHITE);
            gridPaint.setStyle(Paint.Style.STROKE);
            gridPaint.setStrokeWidth(dp(context, 1));
            gridPaint.setColor(withAlpha(text, 34));
            headPaint.setStyle(Paint.Style.STROKE);
            headPaint.setStrokeWidth(dp(context, 1.6f));
            headPaint.setColor(accent);
            facePaint.setStyle(Paint.Style.STROKE);
            facePaint.setStrokeCap(Paint.Cap.ROUND);
            facePaint.setStrokeWidth(dp(context, 3));
            facePaint.setColor(lighten(accent));
            bodyPaint.setStyle(Paint.Style.STROKE);
            bodyPaint.setStrokeCap(Paint.Cap.ROUND);
            bodyPaint.setStrokeWidth(dp(context, 7));
            bodyPaint.setColor(withAlpha(text, 105));
            statusPaint.setTextAlign(Paint.Align.CENTER);
            statusPaint.setTextSize(dp(context, 14));
            statusPaint.setColor(text);
        }

        void setSnapshot(HeadPoseDebugState.Snapshot newSnapshot, boolean newSonyYawCorrected) {
            if (!snapshot.outputKey.equals(newSnapshot.outputKey)
                    || !snapshot.providerId.equals(newSnapshot.providerId)
                    || snapshot.discontinuityCount != newSnapshot.discontinuityCount
                    || sonyYawCorrected != newSonyYawCorrected) {
                referenceX = referenceY = referenceZ = 0;
                referenceW = 1;
            }
            snapshot = newSnapshot;
            sonyYawCorrected = newSonyYawCorrected;
            applyOrientation(newSnapshot.qx, newSnapshot.qy, newSnapshot.qz, newSnapshot.qw);
            invalidate();
        }

        void recenterNative() {
            if (!NativeHeadTrackerReader.PROVIDER.equals(snapshot.providerId)
                    || !snapshot.hasFrame() || snapshot.isStale()) return;
            referenceX = -snapshot.qx;
            referenceY = nativeDisplayYaw(snapshot.qz);
            referenceZ = -snapshot.qy;
            referenceW = snapshot.qw;
        }

        float[] getEulerDegrees() {
            // Y-X-Z decomposition: yaw about the vertical axis, pitch about the ear axis and
            // roll about the forward axis. These are display values only.
            float pitch = (float) Math.asin(clamp(-m12, -1f, 1f));
            float yaw = (float) Math.atan2(m02, m22);
            float roll = (float) Math.atan2(m10, m11);
            float degrees = 180f / (float) Math.PI;
            return new float[] {yaw * degrees, pitch * degrees, roll * degrees};
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float width = getWidth();
            float height = getHeight();
            float cx = width * 0.5f;
            float cy = height * 0.44f;
            float scale = Math.min(width, height) * 0.255f;

            card.set(0, 0, width, height);
            canvas.drawRoundRect(card, dp(getContext(), 24), dp(getContext(), 24), gridPaint);
            canvas.drawLine(cx, height * 0.12f, cx, height * 0.82f, gridPaint);
            canvas.drawLine(width * 0.18f, cy, width * 0.82f, cy, gridPaint);

            // A stationary torso makes rotations visually third-person rather than a floating
            // orientation gizmo.
            canvas.drawLine(cx, cy + scale * 1.02f, cx, cy + scale * 1.58f, bodyPaint);
            canvas.drawArc(cx - scale * 1.05f, cy + scale * 1.18f,
                    cx + scale * 1.05f, cy + scale * 2.1f, 205, 130, false, bodyPaint);

            if (snapshot.hasFrame()) {
                int alpha = snapshot.isStale() ? 95 : 255;
                headPaint.setAlpha(alpha);
                facePaint.setAlpha(alpha);
                drawHead(canvas, cx, cy, scale);
            }

            String status;
            if (!snapshot.hasOutput()) {
                status = getContext().getString(R.string.head_tracking_debug_no_output_short);
            } else if (!snapshot.hasFrame()) {
                status = getContext().getString(R.string.head_tracking_debug_waiting_short);
            } else if (snapshot.isStale()) {
                status = getContext().getString(R.string.head_tracking_debug_stale);
            } else {
                status = getContext().getString(R.string.head_tracking_debug_live);
            }
            canvas.drawText(status, cx, height - dp(getContext(), 20), statusPaint);
        }

        private void drawHead(Canvas canvas, float cx, float cy, float scale) {
            // Latitude rings.
            for (int latitude = -2; latitude <= 2; latitude++) {
                float phi = latitude * (float) Math.PI / 10f;
                float radius = (float) Math.cos(phi);
                float y = 0.92f * (float) Math.sin(phi);
                float previousX = 0f;
                float previousY = 0f;
                for (int segment = 0; segment <= CIRCLE_SEGMENTS; segment++) {
                    float theta = segment * 2f * (float) Math.PI / CIRCLE_SEGMENTS;
                    project(0.72f * radius * (float) Math.cos(theta), y,
                            0.62f * radius * (float) Math.sin(theta), cx, cy, scale);
                    if (segment > 0) {
                        canvas.drawLine(previousX, previousY, projectedX, projectedY, headPaint);
                    }
                    previousX = projectedX;
                    previousY = projectedY;
                }
            }

            // Longitude arcs emphasize yaw and pitch.
            for (int longitude = 0; longitude < 8; longitude++) {
                float theta = longitude * (float) Math.PI / 4f;
                float previousX = 0f;
                float previousY = 0f;
                for (int segment = 0; segment <= ARC_SEGMENTS; segment++) {
                    float phi = -(float) Math.PI / 2f
                            + segment * (float) Math.PI / ARC_SEGMENTS;
                    float radius = (float) Math.cos(phi);
                    project(0.72f * radius * (float) Math.cos(theta),
                            0.92f * (float) Math.sin(phi),
                            0.62f * radius * (float) Math.sin(theta), cx, cy, scale);
                    if (segment > 0) {
                        canvas.drawLine(previousX, previousY, projectedX, projectedY, headPaint);
                    }
                    previousX = projectedX;
                    previousY = projectedY;
                }
            }

            // Ears, eye line and a protruding nose/forward vector make front/back unambiguous.
            drawModelLine(canvas, -0.82f, 0f, 0f, -0.72f, 0f, 0f,
                    cx, cy, scale, facePaint);
            drawModelLine(canvas, 0.72f, 0f, 0f, 0.82f, 0f, 0f,
                    cx, cy, scale, facePaint);
            drawModelLine(canvas, -0.29f, 0.18f, 0.59f, 0.29f, 0.18f, 0.59f,
                    cx, cy, scale, facePaint);
            drawModelLine(canvas, 0f, 0.05f, 0.6f, 0f, 0.05f, 1.04f,
                    cx, cy, scale, facePaint);
            drawModelLine(canvas, 0f, 0f, 0f, 0f, 0f, 1.35f,
                    cx, cy, scale, facePaint);
            // Arrow head on the forward axis.
            drawModelLine(canvas, 0f, 0f, 1.35f, -0.12f, 0.08f, 1.17f,
                    cx, cy, scale, facePaint);
            drawModelLine(canvas, 0f, 0f, 1.35f, 0.12f, 0.08f, 1.17f,
                    cx, cy, scale, facePaint);
        }

        private void drawModelLine(Canvas canvas,
                float x1, float y1, float z1, float x2, float y2, float z2,
                float cx, float cy, float scale, Paint paint) {
            project(x1, y1, z1, cx, cy, scale);
            float screenX1 = projectedX;
            float screenY1 = projectedY;
            project(x2, y2, z2, cx, cy, scale);
            canvas.drawLine(screenX1, screenY1, projectedX, projectedY, paint);
        }

        private void project(float x, float y, float z, float cx, float cy, float scale) {
            float rotatedX = m00 * x + m01 * y + m02 * z;
            float rotatedY = m10 * x + m11 * y + m12 * z;
            float rotatedZ = m20 * x + m21 * y + m22 * z;
            float perspective = 1f / Math.max(0.62f, 1f - rotatedZ * 0.12f);
            projectedX = cx + rotatedX * scale * perspective;
            projectedY = cy - rotatedY * scale * perspective;
        }

        private void applyOrientation(float rawX, float rawY, float rawZ, float rawW) {
            // The renderer consumes a listener-relative transform while this view shows the head
            // in third person. Yaw and roll therefore use the opposite sign. Pitch already uses
            // the view's screen-up convention, so inverting X as part of a blanket quaternion
            // conjugate made looking up appear as looking down. This mapping is deliberately
            // local to the diagnostic view and never changes the pose sent to Sensors HAL.
            qx = rawX;
            qy = -rawY;
            qz = -rawZ;
            qw = rawW;
            if (NativeHeadTrackerReader.PROVIDER.equals(snapshot.providerId)) {
                // Native Android head coordinates: X right, Y forward, Z up. The view uses
                // X right, Y up, Z forward. Swap Y/Z (a handedness change): axial quaternion
                // components transform with the additional minus sign. AirPods stays above.
                qx = -rawX;
                qy = nativeDisplayYaw(rawZ);
                qz = -rawY;
                // Local diagnostic origin only; the service independently recenters rendering.
                float x = qx, y = qy, z = qz, w = qw;
                qx = referenceW * x - referenceX * w - referenceY * z + referenceZ * y;
                qy = referenceW * y + referenceX * z - referenceY * w - referenceZ * x;
                qz = referenceW * z - referenceX * y + referenceY * x - referenceZ * w;
                qw = referenceW * w + referenceX * x + referenceY * y + referenceZ * z;
            }
            float length = (float) Math.sqrt(qx * qx + qy * qy + qz * qz + qw * qw);
            if (length < 0.001f) {
                qx = qy = qz = 0f;
                qw = 1f;
            } else {
                qx /= length;
                qy /= length;
                qz /= length;
                qw /= length;
            }
            updateMatrix();
        }

        private float nativeDisplayYaw(float sensorZ) {
            // XM5 snapshots include the audio-only Z correction. Undo that sign only
            // for this third-person head model, not for the sensor/audio stream.
            // Recentring uses the same mapping so the visual origin stays consistent.
            return sonyYawCorrected ? sensorZ : -sensorZ;
        }

        private void updateMatrix() {
            float xx = qx * qx;
            float yy = qy * qy;
            float zz = qz * qz;
            float xy = qx * qy;
            float xz = qx * qz;
            float yz = qy * qz;
            float wx = qw * qx;
            float wy = qw * qy;
            float wz = qw * qz;
            m00 = 1f - 2f * (yy + zz);
            m01 = 2f * (xy - wz);
            m02 = 2f * (xz + wy);
            m10 = 2f * (xy + wz);
            m11 = 1f - 2f * (xx + zz);
            m12 = 2f * (yz - wx);
            m20 = 2f * (xz - wy);
            m21 = 2f * (yz + wx);
            m22 = 1f - 2f * (xx + yy);
        }

        private static int resolveColor(Context context, int attribute, int fallback) {
            TypedArray values = context.obtainStyledAttributes(new int[] {attribute});
            try {
                return values.getColor(0, fallback);
            } finally {
                values.recycle();
            }
        }

        private static float clamp(float value, float minimum, float maximum) {
            return Math.max(minimum, Math.min(maximum, value));
        }

        private static int lighten(int color) {
            return Color.rgb(
                    Math.min(255, Color.red(color) + 55),
                    Math.min(255, Color.green(color) + 55),
                    Math.min(255, Color.blue(color) + 55));
        }

        private static float dp(Context context, float value) {
            return value * context.getResources().getDisplayMetrics().density;
        }
    }
}
