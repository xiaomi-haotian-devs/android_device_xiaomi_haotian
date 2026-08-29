package com.xiaomi.haotian.calibrator;

import android.app.Activity;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.Bundle;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;

import java.util.Locale;

public final class MainActivity extends Activity {
    private static final String TAG = "HaotianButtonCalibrator";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Window window = getWindow();
        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(Color.TRANSPARENT);
        window.setDecorFitsSystemWindows(false);

        setContentView(new CalibratorView());

        WindowInsetsController controller = window.getInsetsController();
        if (controller != null) {
            controller.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
            controller.setSystemBarsBehavior(
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        }

    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                controller.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
            }
        }
    }

    private final class CalibratorView extends View {
        private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float lineY = -1.0f;

        CalibratorView() {
            super(MainActivity.this);
            setBackgroundColor(Color.BLACK);

            linePaint.setColor(Color.rgb(0, 230, 255));
            linePaint.setStrokeWidth(5.0f);

            textPaint.setColor(Color.WHITE);
            textPaint.setTextSize(42.0f);
            textPaint.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        }

        @Override
        protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
            super.onSizeChanged(width, height, oldWidth, oldHeight);
            if (lineY < 0.0f) {
                lineY = height / 2.0f;
            }
            Log.i(TAG, "DISPLAY width=" + width + " height=" + height
                    + " density=" + getResources().getDisplayMetrics().densityDpi);
            logPosition("INITIAL");
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            canvas.drawLine(0.0f, lineY, getWidth(), lineY, linePaint);

            String coordinate = String.format(Locale.US,
                    "Y = %.0f px   (%.3f H)", lineY, lineY / getHeight());
            String hint = "上下拖动横线，对齐实体按键中心";
            float labelY = lineY > 160.0f ? lineY - 34.0f : lineY + 72.0f;
            canvas.drawText(coordinate, 36.0f, labelY, textPaint);

            textPaint.setTextSize(30.0f);
            textPaint.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL));
            canvas.drawText(hint, 36.0f, 64.0f, textPaint);
            canvas.drawText("松手时会输出 MEASURED 到 logcat", 36.0f, 106.0f, textPaint);
            textPaint.setTextSize(42.0f);
            textPaint.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                case MotionEvent.ACTION_MOVE:
                    lineY = Math.max(0.0f, Math.min(event.getY(), getHeight() - 1.0f));
                    logPosition("MOVING");
                    invalidate();
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    lineY = Math.max(0.0f, Math.min(event.getY(), getHeight() - 1.0f));
                    logPosition("MEASURED");
                    invalidate();
                    return true;
                default:
                    return super.onTouchEvent(event);
            }
        }

        private void logPosition(String state) {
            float normalized = getHeight() == 0 ? 0.0f : lineY / getHeight();
            Log.i(TAG, String.format(Locale.US,
                    "%s lineY=%.0f width=%d height=%d normalizedY=%.6f",
                    state, lineY, getWidth(), getHeight(), normalized));
        }
    }
}
