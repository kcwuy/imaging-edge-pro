package com.sony.playmemories.mobile.redesign;

import android.app.Dialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Presentation-only styling for Sony's existing Bluetooth performance caution. */
public final class V3RemoteDialog {
    private static final String TAG = "V3RemoteDialog";
    private static final String HEADING_TAG = "v4_remote_bluetooth_heading";
    private static final int TEXT = 0xfff5f5f7;
    private static final int SECONDARY = 0xffb5b5bb;

    private V3RemoteDialog() { }

    /** Called only after the two native WifiPerformanceNoticeDialogController show calls. */
    public static void apply(Dialog dialog) {
        if (dialog == null || !dialog.isShowing()) return;
        try {
            Window window = dialog.getWindow();
            if (window == null) return;
            Context context = dialog.getContext();
            View decor = window.getDecorView();
            CheckBox checkbox = checkbox(decor);
            if (checkbox == null || !(checkbox.getParent() instanceof LinearLayout)) return;
            LinearLayout body = (LinearLayout) checkbox.getParent();

            // Keep the actual native checkbox and positive button: their existing callbacks
            // update DoNotShowAgain_RemoteControllPerformanceCaution and dismiss the dialog.
            for (String panel : new String[] {"parentPanel", "customPanel", "custom", "contentPanel", "buttonPanel"}) {
                int id = context.getResources().getIdentifier(panel, "id", context.getPackageName());
                View view = id == 0 ? null : decor.findViewById(id);
                if (view != null) {
                    view.setBackgroundColor(Color.TRANSPARENT);
                    view.setPadding(0, 0, 0, 0);
                }
            }
            body.setPadding(dp(context, 24), dp(context, 24), dp(context, 24), dp(context, 8));
            body.setBackgroundColor(Color.TRANSPARENT);
            body.setAccessibilityPaneTitle("取景流畅度提示");

            TextView message = null;
            for (int index = 0; index < body.getChildCount(); index++) {
                View child = body.getChildAt(index);
                if (child instanceof TextView && !(child instanceof Button)
                        && !HEADING_TAG.equals(child.getTag())) {
                    message = (TextView) child;
                    break;
                }
            }
            if (body.findViewWithTag(HEADING_TAG) == null) {
                TextView heading = new TextView(context);
                heading.setTag(HEADING_TAG);
                heading.setText("取景流畅度提示");
                heading.setTextSize(TypedValue.COMPLEX_UNIT_SP, 19);
                heading.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
                heading.setTextColor(TEXT);
                heading.setIncludeFontPadding(false);
                LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(-1, -2);
                titleParams.bottomMargin = dp(context, 12);
                body.addView(heading, 0, titleParams);
            }
            if (message != null) {
                message.setText("如果取景画面卡顿，可尝试在手机设置中关闭蓝牙，再继续拍摄。");
                message.setTextColor(SECONDARY);
                message.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
                message.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
                message.setIncludeFontPadding(false);
                message.setLineSpacing(dp(context, 4), 1f);
                message.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));
            }
            checkbox.setText("不再提示");
            checkbox.setTextColor(SECONDARY);
            checkbox.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            checkbox.setButtonTintList(new ColorStateList(
                    new int[][] {new int[] {android.R.attr.state_checked}, new int[] {}},
                    new int[] {TEXT, 0xff78787f}));
            checkbox.setMinHeight(dp(context, 48));
            checkbox.setPadding(0, 0, 0, 0);
            LinearLayout.LayoutParams checkboxParams = new LinearLayout.LayoutParams(-1, -2);
            checkboxParams.topMargin = dp(context, 8);
            checkbox.setLayoutParams(checkboxParams);

            View positiveView = decor.findViewById(android.R.id.button1);
            if (positiveView instanceof Button) {
                Button positive = (Button) positiveView;
                positive.setText("知道了");
                positive.setTextColor(TEXT);
                positive.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
                positive.setAllCaps(false);
                positive.setGravity(Gravity.CENTER);
                positive.setMinHeight(0);
                positive.setMinimumHeight(0);
                positive.setMinWidth(0);
                positive.setMinimumWidth(0);
                positive.setPadding(dp(context, 16), 0, dp(context, 16), 0);
                positive.setBackgroundTintList(null);
                positive.setBackground(shape(context, 0xff353539, 14));
                if (positive.getParent() instanceof LinearLayout) {
                    LinearLayout buttons = (LinearLayout) positive.getParent();
                    buttons.setPadding(dp(context, 24), 0, dp(context, 24), dp(context, 20));
                    for (int index = 0; index < buttons.getChildCount(); index++) {
                        View sibling = buttons.getChildAt(index);
                        if (sibling != positive && !(sibling instanceof Button)) sibling.setVisibility(View.GONE);
                    }
                    LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(-1, dp(context, 48));
                    positive.setLayoutParams(buttonParams);
                }
            }
            decor.setPadding(0, 0, 0, 0);
            window.setBackgroundDrawable(shape(context, 0xff222225, 24));
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            WindowManager.LayoutParams attributes = window.getAttributes();
            attributes.dimAmount = 0.55f;
            window.setAttributes(attributes);
            int available = Math.max(1, context.getResources().getDisplayMetrics().widthPixels - dp(context, 40));
            window.setLayout(Math.min(dp(context, 360), available), ViewGroup.LayoutParams.WRAP_CONTENT);
        } catch (RuntimeException failure) {
            Log.w(TAG, "Native caution presentation could not be restyled", failure);
        }
    }

    private static CheckBox checkbox(View view) {
        if (view instanceof CheckBox) return (CheckBox) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                CheckBox found = checkbox(group.getChildAt(index));
                if (found != null) return found;
            }
        }
        return null;
    }

    private static GradientDrawable shape(Context context, int color, int radius) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(color);
        background.setCornerRadius(dp(context, radius));
        return background;
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
