package com.sony.playmemories.mobile.redesign;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.lang.ref.WeakReference;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.WeakHashMap;

/** Native presentation. Liveview and capture stay native; parameter changes use the camera model. */
public final class V3Remote {
    private static final String TAG = "V3Remote";
    private static final int PAPER = 0xfff7f7f4, INK = 0xff181919, BLUE = 0xff006cdb;
    private static final int SHOOT = 0xff080808, MUTED = 0xffa0a0a0, LIGHT = 0xfff7f7f4;
    private static final WeakHashMap<Activity, WeakReference<Binding>> BINDINGS = new WeakHashMap<Activity, WeakReference<Binding>>();
    private V3Remote() { }

    /** Call on the UI thread after Sony has inflated and bound its current layout. */
    public static void apply(Activity activity) {
        if (!remote(activity) || activity.isFinishing() || activity.isDestroyed()) return;
        if (Looper.myLooper() != Looper.getMainLooper()) { Log.w(TAG, "apply requires UI thread"); return; }
        View header = find(activity, "ios_remote_header");
        View table = find(activity, "remote_control_activity_setting_table_layout");
        if (table == null) table = find(activity, "ptpip_remote_control_activity_setting_table_layout");
        View osd = find(activity, "remote_control_activity_osd_layout");
        View capture = find(activity, "act_button_panel");
        if (!(header instanceof ViewGroup) || !(table instanceof ViewGroup)
                || !(osd instanceof RelativeLayout) || !(capture instanceof RelativeLayout)) {
            Log.w(TAG, "Sony remote layout is not yet available; existing screen retained"); return;
        }
        Binding previous = binding(activity);
        if (previous != null && !previous.disposed && previous.header == header) { previous.resume(); return; }
        if (previous != null) previous.dispose(false);
        Binding next = new Binding(activity, (ViewGroup) header, (ViewGroup) table, (RelativeLayout) osd, (RelativeLayout) capture);
        try {
            next.install();
            BINDINGS.put(activity, new WeakReference<Binding>(next));
            next.resume();
        } catch (RuntimeException failure) {
            next.dispose(true);
            BINDINGS.remove(activity);
            Log.e(TAG, "Remote presentation failed; original controls restored", failure);
        }
    }

    public static void pause(Activity activity) {
        Binding state = binding(activity);
        if (state != null) { state.active = false; state.stop(); state.closePanel(); if (state.parameters != null) state.parameters.collapse(); }
    }

    public static void destroy(Activity activity) {
        Binding state = binding(activity);
        if (state != null) state.dispose(false);
        BINDINGS.remove(activity);
    }

    /** Invoke once per Back dispatch, before Sony's original controller callback. */
    public static boolean onBack(Activity activity) {
        Binding state = binding(activity);
        if (state == null || state.originalOverlayVisible()) return false;
        if (state.open) { state.closePanel(); return true; }
        return state.parameters != null && state.parameters.collapse();
    }

    private static Binding binding(Activity activity) {
        WeakReference<Binding> reference = BINDINGS.get(activity);
        return reference == null ? null : reference.get();
    }

    private static boolean remote(Activity activity) {
        if (activity == null) return false;
        String name = activity.getClass().getName();
        return name.equals("com.sony.playmemories.mobile.remotecontrol.RemoteControlActivity")
                || name.equals("com.sony.playmemories.mobile.ptpipremotecontrol.RemoteControlActivity");
    }

    private static View find(Activity activity, String name) {
        int id = activity.getResources().getIdentifier(name, "id", "com.sony.playmemories.mobile");
        return id == 0 ? null : activity.findViewById(id);
    }

    private static final class Move {
        final View view;
        final ViewGroup parent;
        final int index;
        final ViewGroup.LayoutParams params;
        Move(View view) {
            this.view = view; parent = (ViewGroup) view.getParent(); index = parent.indexOfChild(view);
            params = copy(view.getLayoutParams());
        }
        void restore() {
            if (view.getParent() instanceof ViewGroup) ((ViewGroup) view.getParent()).removeView(view);
            parent.addView(view, Math.min(index, parent.getChildCount()), params);
        }
    }

    private static ViewGroup.LayoutParams copy(ViewGroup.LayoutParams source) {
        if (source instanceof RelativeLayout.LayoutParams) return new RelativeLayout.LayoutParams((RelativeLayout.LayoutParams) source);
        if (source instanceof LinearLayout.LayoutParams) return new LinearLayout.LayoutParams((LinearLayout.LayoutParams) source);
        if (source instanceof FrameLayout.LayoutParams) return new FrameLayout.LayoutParams((FrameLayout.LayoutParams) source);
        if (source instanceof ViewGroup.MarginLayoutParams) return new ViewGroup.MarginLayoutParams((ViewGroup.MarginLayoutParams) source);
        return new ViewGroup.LayoutParams(source);
    }

    private static final class Slot {
        final View original;
        final View wrapper;
        Slot(View original, View wrapper) { this.original = original; this.wrapper = wrapper; }
        void update() {
            boolean visible = original.getVisibility() == View.VISIBLE;
            if (original instanceof ImageView) visible &= ((ImageView) original).getDrawable() != null;
            int visibility = visible ? View.VISIBLE : View.GONE;
            if (wrapper.getVisibility() != visibility) wrapper.setVisibility(visibility);
        }
    }

    private static final class Binding implements Runnable, View.OnAttachStateChangeListener {
        final Activity activity;
        final ViewGroup header, table;
        final RelativeLayout osd, capture;
        final Handler handler = new Handler(Looper.getMainLooper());
        final ArrayList<Move> moves = new ArrayList<Move>();
        final ArrayList<View> additions = new ArrayList<View>();
        final ArrayList<Slot> slots = new ArrayList<Slot>();
        final ArrayList<View> changedLayouts = new ArrayList<View>();
        final ArrayList<ViewGroup.LayoutParams> oldLayouts = new ArrayList<ViewGroup.LayoutParams>();
        V3ParameterBar parameters;
        final ArrayList<View> lensControls = new ArrayList<View>();
        RelativeLayout root;
        FrameLayout overlay;
        LinearLayout sheet, body, summary, transferBadge;
        RelativeLayout lens;
        TextView back, trigger, panelContext, lensHeading, modeValue, transferText;
        View transferIcon, modeControl;
        Object transferController;
        boolean active, open, disposed;
        int sheetHeight;
        final boolean ptp;

        Binding(Activity activity, ViewGroup header, ViewGroup table, RelativeLayout osd, RelativeLayout capture) {
            this.activity = activity; this.header = header; this.table = table; this.osd = osd; this.capture = capture;
            ptp = activity.getClass().getName().contains("ptpipremotecontrol");
        }

        int dp(float value) { return Math.round(value * activity.getResources().getDisplayMetrics().density); }
        TextView label(String value, float size, int color) {
            TextView text = new TextView(activity); text.setText(value); text.setTextSize(size);
            text.setTextColor(color); text.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
            text.setIncludeFontPadding(false); text.setGravity(Gravity.CENTER_VERTICAL);
            return text;
        }
        GradientDrawable background(int color, int radius) {
            GradientDrawable drawable = new GradientDrawable(); drawable.setColor(color); drawable.setCornerRadius(dp(radius)); return drawable;
        }
        void added(ViewGroup parent, View child, ViewGroup.LayoutParams params) {
            parent.addView(child, params); additions.add(child);
        }
        void move(View original, ViewGroup target, ViewGroup.LayoutParams params) {
            if (!(original.getParent() instanceof ViewGroup)) throw new IllegalStateException("Detached Sony control");
            moves.add(new Move(original)); ((ViewGroup) original.getParent()).removeView(original); target.addView(original, params);
        }
        void layout(View original, ViewGroup.LayoutParams params) {
            changedLayouts.add(original); oldLayouts.add(copy(original.getLayoutParams())); original.setLayoutParams(params);
        }

        void install() {
            for (View current = osd; current != null; ) {
                if (current instanceof RelativeLayout && ((ViewGroup) current).findViewById(header.getId()) == header
                        && ((ViewGroup) current).findViewById(capture.getId()) == capture) { root = (RelativeLayout) current; break; }
                ViewParent parent = current.getParent(); current = parent instanceof View ? (View) parent : null;
            }
            if (root == null || !(table.getParent() instanceof ViewGroup)) throw new IllegalStateException("Unsupported Sony root");
            root.setBackgroundColor(SHOOT); header.setBackgroundColor(SHOOT); capture.setBackgroundColor(SHOOT);
            buildSheet();
            preserveNativeMenuPriority();
            buildSummary();
            osd.setBackgroundColor(Color.BLACK);
            // Keep Sony's text/state bindings findable without showing the old parameter cards.
            FrameLayout parkedTable = new FrameLayout(activity); parkedTable.setVisibility(View.GONE);
            added(root, parkedTable, new RelativeLayout.LayoutParams(0, 0));
            move(table, parkedTable, new FrameLayout.LayoutParams(-1, -2));
            moveStatus();
            moveTransfer();
            labelOptionRail();
            moveProfessionalControls();
            buildModeControl();
            hideLegacyPresentation();
            TextView title = asText(find(activity, "ios_remote_title"));
            if (title != null) {
                title.setTextColor(LIGHT); title.setTextSize(17); title.setMaxLines(1);
                title.setMaxWidth(Math.max(dp(80), activity.getResources().getDisplayMetrics().widthPixels - dp(224)));
                title.setEllipsize(TextUtils.TruncateAt.END);
            }
            back = asText(find(activity, "ios_remote_back"));
            if (back != null) {
                back.setBackgroundColor(Color.TRANSPARENT); back.setTextColor(LIGHT); back.setText("‹");
                back.setContentDescription("返回");
                back.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View ignored) { if (!V3Remote.onBack(activity)) originalBack(activity); }
                });
            }
            root.addOnAttachStateChangeListener(this);
            refresh();
        }

        void buildSheet() {
            overlay = new FrameLayout(activity); overlay.setVisibility(View.GONE);
            overlay.setBackgroundColor(0x66000000); overlay.setClickable(true);
            overlay.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View ignored) { closePanel(); } });
            sheet = new LinearLayout(activity); sheet.setOrientation(LinearLayout.VERTICAL);
            sheet.setBackground(background(0xff151515, 26)); sheet.setClickable(true); sheet.setPadding(dp(16), dp(8), dp(16), dp(12));
            sheet.setElevation(dp(12));
            LinearLayout top = new LinearLayout(activity); top.setGravity(Gravity.CENTER_VERTICAL);
            TextView title = label("更多拍摄功能", 19, LIGHT); title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            top.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
            TextView close = label("完成", 15, LIGHT); close.setGravity(Gravity.CENTER); close.setContentDescription("收起更多拍摄功能");
            top.addView(close, new LinearLayout.LayoutParams(dp(64), dp(48)));
            close.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View ignored) { closePanel(); } });
            sheet.addView(top, new LinearLayout.LayoutParams(-1, -2));
            panelContext = label("常用参数可在取景下方直接调整", 12, MUTED); panelContext.setPadding(0, 0, 0, dp(12));
            sheet.addView(panelContext, new LinearLayout.LayoutParams(-1, -2));
            ScrollView scroll = new ScrollView(activity); scroll.setFillViewport(false); scroll.setClipToPadding(false);
            body = new LinearLayout(activity); body.setOrientation(LinearLayout.VERTICAL);
            scroll.addView(body, new ScrollView.LayoutParams(-1, -2));
            sheet.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
            sheetHeight = Math.min(dp(420), Math.round(activity.getResources().getDisplayMetrics().heightPixels * .55f));
            FrameLayout.LayoutParams panel = new FrameLayout.LayoutParams(-1, sheetHeight, Gravity.BOTTOM);
            overlay.addView(sheet, panel);
            RelativeLayout.LayoutParams full = new RelativeLayout.LayoutParams(-1, -1);
            int insertion = root.getChildCount();
            for (String name : new String[] {"continuous_error_layout", "remote_control_menu", "processing_screen", "restriction_status_screen", "image_download_layout"}) {
                View blocker = find(activity, name);
                if (blocker != null && blocker.getParent() == root) insertion = Math.min(insertion, root.indexOfChild(blocker));
            }
            root.addView(overlay, insertion, full); additions.add(overlay);
        }

        void preserveNativeMenuPriority() {
            // Tablet menus originally live inside the 320dp side rail. Keep them above our sheet,
            // still under Sony's top-level processing/restriction overlays and in a RelativeLayout.
            for (String name : new String[] {"remote_control_menu", "movie_rec_self_timer_menu"}) {
                View menu = find(activity, name);
                if (menu == null || menu.getParent() == root || !(menu.getParent() instanceof RelativeLayout)) continue;
                RelativeLayout.LayoutParams params = new RelativeLayout.LayoutParams(-1, -1);
                params.setMargins(dp(12), dp(62), dp(12), dp(12));
                moves.add(new Move(menu)); ((ViewGroup) menu.getParent()).removeView(menu);
                root.addView(menu, root.indexOfChild(overlay) + 1, params);
            }
        }

        void buildSummary() {
            parameters = new V3ParameterBar(activity); summary = parameters;
            ViewGroup original = (ViewGroup) table.getParent();
            if (original instanceof LinearLayout && ((LinearLayout) original).getOrientation() == LinearLayout.VERTICAL) {
                original.addView(summary, original.indexOfChild(table), new LinearLayout.LayoutParams(-1, -2)); additions.add(summary);
            } else if (original instanceof ScrollView) {
                // A ScrollView permits one child; it receives the summary after the table moves.
                final ViewGroup slot = original;
                final ViewGroup.LayoutParams summaryParams = new FrameLayout.LayoutParams(-1, -2);
                summary.setTag(new Object[] {slot, summaryParams});
                additions.add(summary);
            } else {
                RelativeLayout.LayoutParams params = new RelativeLayout.LayoutParams(-1, -2);
                params.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM); params.leftMargin = dp(12); params.rightMargin = dp(100); params.bottomMargin = dp(12);
                root.addView(summary, root.indexOfChild(overlay), params); additions.add(summary);
                // Reserve a footer inside the existing liveview ancestor; do not detach/re-add SurfaceView.
                View branch = osd;
                while (branch.getParent() != root && branch.getParent() instanceof View) branch = (View) branch.getParent();
                if (branch != root && branch.getLayoutParams() instanceof RelativeLayout.LayoutParams) {
                    final int baseMargin = ((RelativeLayout.LayoutParams) branch.getLayoutParams()).bottomMargin;
                    RelativeLayout.LayoutParams reduced = new RelativeLayout.LayoutParams((RelativeLayout.LayoutParams) branch.getLayoutParams());
                    reduced.bottomMargin = baseMargin + parameters.getReservedHeight() + dp(12); layout(branch, reduced);
                }
            }
            trigger = label("•••", 18, LIGHT); trigger.setGravity(Gravity.CENTER); trigger.setBackgroundColor(Color.TRANSPARENT);
            trigger.setContentDescription("更多拍摄功能");
            RelativeLayout.LayoutParams button = new RelativeLayout.LayoutParams(dp(56), dp(48));
            if (verticalCapture()) { button.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM); button.addRule(RelativeLayout.CENTER_HORIZONTAL); button.bottomMargin = dp(12); }
            else { button.addRule(RelativeLayout.ALIGN_PARENT_RIGHT); button.addRule(RelativeLayout.CENTER_VERTICAL); button.rightMargin = dp(12); }
            added(capture, trigger, button);
            trigger.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View ignored) { openPanel(); } });
        }

        boolean landscape() { return activity.getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE; }
        boolean verticalCapture() {
            ViewGroup.LayoutParams params = capture.getLayoutParams();
            return params.width > 0 && params.height == ViewGroup.LayoutParams.MATCH_PARENT;
        }

        void buildModeControl() {
            LinearLayout control = new LinearLayout(activity); control.setOrientation(LinearLayout.VERTICAL);
            control.setGravity(Gravity.CENTER); control.setBackground(background(0xff202020, 16));
            modeValue = new V3FitText(activity, 10, 17); modeValue.setTextColor(LIGHT); modeValue.setGravity(Gravity.CENTER);
            TextView caption = label("模式", 10, MUTED); caption.setGravity(Gravity.CENTER);
            control.addView(modeValue, new LinearLayout.LayoutParams(-1, dp(25)));
            control.addView(caption, new LinearLayout.LayoutParams(-1, dp(17)));
            control.setPadding(dp(4), dp(4), dp(4), dp(4));
            RelativeLayout.LayoutParams params = new RelativeLayout.LayoutParams(dp(60), dp(54));
            if (verticalCapture()) {
                // Landscape phone rail also contains native AE/AF/cancel actions above the shutter.
                // The leading header space keeps those controls and the shutter fully accessible.
                params.addRule(RelativeLayout.ALIGN_PARENT_LEFT); params.addRule(RelativeLayout.CENTER_VERTICAL); params.leftMargin = dp(52);
            } else {
                params.addRule(RelativeLayout.ALIGN_PARENT_LEFT); params.addRule(RelativeLayout.CENTER_VERTICAL); params.leftMargin = dp(12);
            }
            added(verticalCapture() ? header : capture, control, params); modeControl = control;
            control.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View ignored) {
                    if (!active || disposed || originalOverlayVisible()) return;
                    closePanel(); parameters.openMode();
                }
            });
        }

        void moveTransfer() {
            if (!(header instanceof RelativeLayout)) return;
            transferBadge = new LinearLayout(activity); transferBadge.setGravity(Gravity.CENTER);
            transferBadge.setPadding(dp(6), 0, dp(6), 0); transferBadge.setBackground(background(0xff1f2b23, 12));
            transferBadge.setVisibility(View.INVISIBLE);
            RelativeLayout.LayoutParams params = new RelativeLayout.LayoutParams(dp(84), dp(32));
            params.addRule(RelativeLayout.ALIGN_PARENT_RIGHT); params.addRule(RelativeLayout.CENTER_VERTICAL);
            added(header, transferBadge, params);
            transferIcon = find(activity, "ptpip_remote_control_activity_postview_download_icon");
            if (transferIcon != null && transferIcon.getParent() instanceof ViewGroup) {
                // Sony casts this BACKGROUND to AnimationDrawable on configuration changes.
                // Move the original instance without replacing its background or click handler.
                move(transferIcon, transferBadge, new LinearLayout.LayoutParams(dp(24), dp(24)));
            }
            transferText = label("传输中", 11, 0xffbde4c7); transferText.setGravity(Gravity.CENTER);
            transferBadge.addView(transferText, new LinearLayout.LayoutParams(0, -1, 1));
            transferBadge.setContentDescription("照片传输中");
            transferBadge.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View ignored) { if (transferIcon != null) transferIcon.performClick(); }
            });
        }

        void updateTransfer() {
            boolean transferring = transferIcon != null && transferIcon.getVisibility() == View.VISIBLE;
            int percentage = -1;
            try {
                if (transferController == null) {
                    Object controller = activity.getClass().getField("mController").get(activity);
                    Object children = controller.getClass().getField("mControllers").get(controller);
                    if (children instanceof java.util.List) for (Object child : (java.util.List<?>) children) {
                        String name = child.getClass().getName();
                        if (name.equals("com.sony.playmemories.mobile.ptpipremotecontrol.controller.postview.PostViewProgressBarController")
                                || name.equals("com.sony.playmemories.mobile.remotecontrol.controller.liveview.LiveviewScreenController")) {
                            transferController = child; break;
                        }
                    }
                }
                if (transferController != null) {
                    if (ptp) {
                        Object busy = transferController.getClass().getField("mIsDownloading").get(transferController);
                        transferring = ((java.util.concurrent.atomic.AtomicBoolean) busy).get();
                        percentage = transferController.getClass().getField("mPercentage").getInt(transferController);
                    } else {
                        Object circle = transferController.getClass().getField("mCircle").get(transferController);
                        if (circle != null) transferring = circle.getClass().getField("mIsPostviewDownloading").getBoolean(circle);
                    }
                }
            } catch (Exception unavailable) { /* The native icon remains the fallback for older controllers. */ }
            if (transferText != null) transferText.setText(transferIcon == null ? "↓ 传输中"
                    : percentage > 0 && percentage < 100 ? percentage + "%" : "传输中");
            if (transferBadge != null) transferBadge.setVisibility(transferring ? View.VISIBLE : View.INVISIBLE);
            if (transferBadge != null) transferBadge.setContentDescription("照片传输中" + (percentage >= 0 ? "，" + percentage + "%" : ""));
            View dot = find(activity, "ios_remote_connection");
            if (dot != null) dot.setVisibility(transferring ? View.INVISIBLE : View.VISIBLE);
        }

        void moveStatus() {
            for (String name : new String[] {"remote_control_activity_status_layout", "ptpip_remote_control_activity_status_layout"}) {
                View status = find(activity, name);
                if (status == null || !(status.getParent() instanceof ViewGroup)) continue;
                RelativeLayout.LayoutParams params = new RelativeLayout.LayoutParams(dp(112), dp(28));
                params.addRule(RelativeLayout.ALIGN_PARENT_RIGHT); params.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM);
                params.rightMargin = dp(8); params.bottomMargin = dp(6);
                move(status, osd, params); statusText(status);
            }
        }

        void statusText(View current) {
            if (current instanceof TextView) { ((TextView) current).setTextColor(0xffc7cbc1); ((TextView) current).setTextSize(11); }
            if (current instanceof ViewGroup) for (int index = 0; index < ((ViewGroup) current).getChildCount(); index++) statusText(((ViewGroup) current).getChildAt(index));
        }

        void labelOptionRail() {
            LinearLayout optionsRow = new LinearLayout(activity); optionsRow.setGravity(Gravity.CENTER_VERTICAL);
            android.widget.HorizontalScrollView scroll = new android.widget.HorizontalScrollView(activity);
            scroll.setHorizontalScrollBarEnabled(false); scroll.addView(optionsRow, new FrameLayout.LayoutParams(-2, -2));
            added(body, scroll, new LinearLayout.LayoutParams(-1, -2));
            // White balance/focus join the inline bar. The legacy icons remain parked and bound.
            String[][] options = {{"setting_table_scene1", "场景"}, {"setting_table_scene2", "场景选项"},
                    {"setting_table_shooting_mode", "拍摄方式"},
                    {"setting_table_3_2", ptp ? "驱动模式" : "连拍方式"}, {"setting_table_3_3", ptp ? "动态范围" : "连拍速度"},
                    {"setting_table_4_2", ptp ? "测光模式" : "自拍定时"}, {"setting_table_4_3", "相机回放"}};
            for (String[] option : options) {
                View item = find(activity, option[0]);
                if (!(item instanceof ImageView) || !(item.getParent() instanceof ViewGroup)) continue;
                LinearLayout cell = new LinearLayout(activity); cell.setOrientation(LinearLayout.VERTICAL); cell.setGravity(Gravity.CENTER);
                LinearLayout.LayoutParams cellParams = new LinearLayout.LayoutParams(dp(76), -2); cellParams.setMargins(0, dp(8), dp(4), dp(8));
                optionsRow.addView(cell, cellParams); additions.add(cell);
                move(item, cell, new LinearLayout.LayoutParams(dp(48), dp(48)));
                item.setBackground(background(0xff252525, 16)); ((ImageView) item).setImageTintList(ColorStateList.valueOf(LIGHT));
                item.setContentDescription(option[1]);
                TextView caption = label(option[1], 11, MUTED); caption.setGravity(Gravity.CENTER); caption.setPadding(0, dp(4), 0, 0);
                cell.addView(caption, new LinearLayout.LayoutParams(-1, -2));
                // Only the actual Sony icon consumes a tap. The caption is informational.
                slots.add(new Slot(item, cell));
            }
        }

        void moveProfessionalControls() {
            lensHeading = label("镜头操作", 14, MUTED); lensHeading.setPadding(dp(4), dp(18), 0, dp(8));
            added(body, lensHeading, new LinearLayout.LayoutParams(-1, -2));
            lens = new RelativeLayout(activity); lens.setBackground(background(0xff202020, 16));
            added(body, lens, new LinearLayout.LayoutParams(-1, dp(112)));
            boolean containsLensControl = false;
            for (String name : new String[] {"remote_control_activity_zoom", "remote_control_activity_manual_focus_layout", "zoom_mf_switch", "btn_stepzoom"}) {
                View control = find(activity, name); if (control == null || !(control.getParent() instanceof ViewGroup)) continue;
                RelativeLayout.LayoutParams params;
                if (name.equals("remote_control_activity_zoom")) {
                    params = new RelativeLayout.LayoutParams(dp(96), dp(64)); params.addRule(RelativeLayout.ALIGN_PARENT_RIGHT); params.rightMargin = dp(12); params.addRule(RelativeLayout.CENTER_VERTICAL);
                } else if (name.equals("remote_control_activity_manual_focus_layout")) {
                    params = new RelativeLayout.LayoutParams(dp(132), dp(88)); params.addRule(RelativeLayout.ALIGN_PARENT_RIGHT); params.rightMargin = dp(8); params.addRule(RelativeLayout.CENTER_VERTICAL);
                } else {
                    params = new RelativeLayout.LayoutParams(dp(48), dp(48)); params.addRule(RelativeLayout.ALIGN_PARENT_LEFT); params.addRule(RelativeLayout.CENTER_VERTICAL);
                    params.leftMargin = dp(name.equals("btn_stepzoom") ? 68 : 12);
                }
                move(control, lens, params); lensControls.add(control); containsLensControl = true;
            }
            if (!containsLensControl) lens.setVisibility(View.GONE);
            LinearLayout actions = new LinearLayout(activity); actions.setGravity(Gravity.CENTER_VERTICAL); actions.setPadding(0, dp(8), 0, 0);
            added(body, actions, new LinearLayout.LayoutParams(-1, -2));
            for (String name : new String[] {"settings_button", "remote_control_activity_clockwise_rotate_button"}) {
                View control = find(activity, name); if (!(control instanceof ImageView) || !(control.getParent() instanceof ViewGroup)) continue;
                LinearLayout cell = new LinearLayout(activity); cell.setOrientation(LinearLayout.HORIZONTAL); cell.setGravity(Gravity.CENTER_VERTICAL);
                LinearLayout.LayoutParams cellParams = new LinearLayout.LayoutParams(0, -2, 1); cellParams.setMargins(dp(3), 0, dp(3), 0);
                actions.addView(cell, cellParams); cell.setBackground(background(0xff252525, 16));
                move(control, cell, new LinearLayout.LayoutParams(dp(48), dp(48)));
                control.setBackgroundColor(Color.TRANSPARENT); ((ImageView) control).setImageTintList(ColorStateList.valueOf(LIGHT));
                String caption = name.equals("settings_button") ? "相机设置" : "旋转取景";
                control.setContentDescription(caption); TextView description = label(caption, 12, LIGHT);
                cell.addView(description, new LinearLayout.LayoutParams(0, -2, 1)); slots.add(new Slot(control, cell));
            }
        }

        void hideLegacyPresentation() {
            FrameLayout parked = new FrameLayout(activity); parked.setVisibility(View.GONE);
            added(root, parked, new RelativeLayout.LayoutParams(0, 0));
            // The old Back duplicates the header; the legacy display toggle leads to fullscreen parameters.
            for (String name : new String[] {"remote_control_activity_single_back_key", "remote_control_activity_switch_screen_button"}) {
                View old = find(activity, name); if (old != null && old.getParent() instanceof ViewGroup) move(old, parked, new FrameLayout.LayoutParams(0, 0));
            }
            View cancel = find(activity, "touch_focus_cancel");
            if (cancel != null && cancel.getParent() == osd && cancel.getLayoutParams() instanceof RelativeLayout.LayoutParams) {
                RelativeLayout.LayoutParams params = new RelativeLayout.LayoutParams(dp(48), dp(48));
                params.addRule(RelativeLayout.ALIGN_PARENT_RIGHT); params.addRule(RelativeLayout.ALIGN_PARENT_TOP);
                params.rightMargin = dp(8); params.topMargin = dp(8); layout(cancel, params);
            }
        }

        void openPanel() {
            if (!active || disposed || originalOverlayVisible()) return;
            if (parameters != null) parameters.collapse(); refresh();
            // UI visibility only: no EnumScreenMode mutation / request-to-stop-liveview event.
            open = true; overlay.setVisibility(View.VISIBLE);
            sheet.setAccessibilityPaneTitle("更多拍摄功能");
        }
        void closePanel() {
            open = false;
            if (overlay != null) overlay.setVisibility(View.GONE);
        }

        boolean originalOverlayVisible() {
            for (String name : new String[] {"remote_control_menu", "movie_rec_self_timer_menu", "processing_screen", "restriction_status_screen", "image_download_layout"}) {
                View original = find(activity, name);
                if (original != null && original.getVisibility() == View.VISIBLE && original.isShown()) return true;
            }
            return false;
        }

        void resume() {
            if (disposed) return;
            active = true; handler.removeCallbacks(this); refresh();
            if (root.isAttachedToWindow()) handler.postDelayed(this, 400);
        }
        void stop() { handler.removeCallbacks(this); }
        @Override public void run() {
            if (!active || disposed || activity.isFinishing() || activity.isDestroyed() || !root.isAttachedToWindow()) return;
            refresh(); handler.postDelayed(this, 400);
        }
        @Override public void onViewAttachedToWindow(View view) { if (active && !disposed) resume(); }
        @Override public void onViewDetachedFromWindow(View view) { stop(); }

        void refresh() {
            if (disposed) return;
            if (summary != null && summary.getParent() == null && summary.getTag() instanceof Object[]) {
                Object[] pending = (Object[]) summary.getTag();
                if (((ViewGroup) pending[0]).getChildCount() == 0) {
                    ((ViewGroup) pending[0]).addView(summary, (ViewGroup.LayoutParams) pending[1]); summary.setTag(null);
                }
            }
            Boolean connected = cameraConnected(activity);
            if (parameters != null) parameters.refresh(connected, open || originalOverlayVisible());
            if (modeValue != null) {
                modeValue.setText(parameters.modeLabel());
                modeValue.setTextColor(parameters.isModeExpanded() ? 0xffff4b43 : LIGHT);
            }
            if (modeControl != null) {
                modeControl.setEnabled(parameters.canOpenMode());
                modeControl.setAlpha(parameters.canOpenMode() ? 1f : .5f);
                modeControl.setContentDescription("拍摄模式 " + parameters.modeLabel());
            }
            updateTransfer();
            for (Slot slot : slots) slot.update();
            boolean hasLens = false;
            for (View control : lensControls) hasLens |= control.getVisibility() == View.VISIBLE;
            if (lens != null) lens.setVisibility(hasLens ? View.VISIBLE : View.GONE);
            if (lensHeading != null) lensHeading.setVisibility(hasLens ? View.VISIBLE : View.GONE);
            if (panelContext != null) {
                String context = Boolean.FALSE.equals(connected) ? "等待相机会话" : "常用参数可在取景下方直接调整";
                if (!context.contentEquals(panelContext.getText())) panelContext.setText(context);
            }
            TextView title = asText(find(activity, "ios_remote_title"));
            String cameraName = cameraName(activity);
            if (title != null && !TextUtils.isEmpty(cameraName) && !cameraName.contentEquals(title.getText())) title.setText(cameraName);
            TextView subtitle = asText(find(activity, "ios_remote_subtitle"));
            String status = connected == null ? "遥控拍摄" : connected ? "相机连接可用" : "等待相机会话";
            if (subtitle != null) { subtitle.setText(status); subtitle.setTextColor(0xffa6aaa2); }
            TextView dot = asText(find(activity, "ios_remote_connection"));
            if (dot != null) { dot.setTextColor(Boolean.TRUE.equals(connected) ? 0xff7cb58a : 0xff899080); dot.setContentDescription(status); }
        }

        void dispose(boolean restore) {
            active = false; disposed = true; stop(); closePanel();
            if (parameters != null) parameters.dispose();
            if (root != null) root.removeOnAttachStateChangeListener(this);
            if (trigger != null) trigger.setOnClickListener(null);
            if (back != null) back.setOnClickListener(null);
            if (restore) {
                for (int index = changedLayouts.size() - 1; index >= 0; index--) try { changedLayouts.get(index).setLayoutParams(oldLayouts.get(index)); } catch (RuntimeException ignored) { }
                for (int index = moves.size() - 1; index >= 0; index--) try { moves.get(index).restore(); } catch (RuntimeException ignored) { }
                for (int index = additions.size() - 1; index >= 0; index--) {
                    View child = additions.get(index); if (child.getParent() instanceof ViewGroup) ((ViewGroup) child.getParent()).removeView(child);
                }
            }
            moves.clear(); additions.clear(); slots.clear(); changedLayouts.clear(); oldLayouts.clear();
            transferController = null;
        }
    }

    private static TextView asText(View view) { return view instanceof TextView ? (TextView) view : null; }

    private static Boolean cameraConnected(Activity activity) {
        try {
            Object result = Class.forName("com.sony.playmemories.mobile.redesign.ConnectionKeeper")
                    .getMethod("hasCameraConnection", android.content.Context.class).invoke(null, activity);
            return result instanceof Boolean ? (Boolean) result : null;
        } catch (Exception unavailable) { return null; }
    }

    private static String cameraName(Activity activity) {
        try {
            Object controller = activity.getClass().getField("mController").get(activity);
            Object camera = controller.getClass().getField("mCamera").get(controller);
            Object description = camera.getClass().getField("mDdXml").get(camera);
            Object name = description.getClass().getField("mFriendlyName").get(description);
            return name instanceof String ? (String) name : null;
        } catch (Exception unavailable) { return null; }
    }

    private static void originalBack(Activity activity) {
        try {
            Object dispatcher = activity.getClass().getMethod("getOnBackPressedDispatcher").invoke(activity);
            dispatcher.getClass().getMethod("onBackPressed").invoke(dispatcher);
        } catch (InvocationTargetException originalFailure) {
            Log.e(TAG, "Original Back handler failed", originalFailure.getCause());
        } catch (Exception noDispatcher) {
            try {
                Object controller = activity.getClass().getField("mController").get(activity);
                controller.getClass().getMethod("onBackPressed").invoke(controller);
            } catch (Exception originalFailure) { Log.e(TAG, "Original controller Back unavailable", originalFailure); }
        }
    }
}
