package com.sony.playmemories.mobile.redesign;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Bitmap;
import android.graphics.Insets;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Build;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.ViewTreeObserver;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListAdapter;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.WeakHashMap;
import java.util.ArrayList;
import java.util.Map;

/** A native task shell around Sony's original, still-bound controllers and registration data. */
public final class V3Shell {
    private static final String PREFIX = "com.sony.playmemories.mobile.";
    private static final String ROOT_TAG = "imaging-edge-v3-shell";
    private static final int PAPER = 0xfff7f7f4, INK = 0xff181919, MUTED = 0xff70716f, BLUE = 0xff006cdb;
    private static final WeakHashMap<Activity, Binding> BINDINGS = new WeakHashMap<Activity, Binding>();
    private static final WeakHashMap<Activity, WebTransferWait> TRANSFER_WAITS = new WeakHashMap<Activity, WebTransferWait>();
    private static final WeakHashMap<Activity, Boolean> RESUMED = new WeakHashMap<Activity, Boolean>();
    private static final WeakHashMap<Activity, FirstDrawGate> DRAW_GATES = new WeakHashMap<Activity, FirstDrawGate>();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static String pendingTask;
    private static long pendingUntil;
    private static boolean routeFromPhotoPage;
    private static String routeTab;
    private static Handoff handoff;
    private static final int MAX_HANDOFF_PIXELS = 1000000;
    private V3Shell() { }
    public static View cameraIllustration(Context context) { return new CameraDrawing(context); }

    /** A bounded visual lease, independent of Sony's connection and dispatch state. */
    public static final class HandoffLease {
        private final String target;
        private final int androidTask;
        private final long deadline;
        private boolean canceled;
        public HandoffLease(String target, int androidTask, long now) {
            this.target = target; this.androidTask = androidTask; deadline = now + 12000L;
        }
        public boolean accepts(String current, int taskId, long now) {
            return !canceled && target != null && target.equals(current) && now < deadline
                    && (androidTask < 0 || taskId < 0 || androidTask == taskId);
        }
        public void cancel() { canceled = true; }
    }

    /** The native controller owns its progress or dialog; the handoff HUD is only a fallback. */
    public static final class LoadingPresentation {
        private LoadingPresentation() { }
        public static boolean showHandoff(boolean handoffVisible, boolean nativeBusy, boolean nativeDialogOrWindow) {
            return handoffVisible && !nativeBusy && !nativeDialogOrWindow;
        }
    }

    private static final class Handoff {
        final HandoffLease lease;
        final Bitmap frame;
        Handoff(String target, Activity activity, Bitmap frame) {
            lease = new HandoffLease(target, taskId(activity), SystemClock.elapsedRealtime()); this.frame = frame;
        }
    }
    private static int taskId(Activity activity) {
        try { return activity.getTaskId(); } catch (RuntimeException unavailable) { return -1; }
    }
    private static boolean intermediate(Activity activity) {
        String name = activity.getClass().getName();
        return name.equals(PREFIX + "home.HomeActivity") || name.equals(PREFIX + "selectfunction.SelectFunctionActivity");
    }
    private static boolean hasHandoff(Activity activity) {
        return handoff != null && handoff.lease.accepts(task(), taskId(activity), SystemClock.elapsedRealtime());
    }
    /** CommonActivity calls this before its posted installers. Native onResume still runs in full. */
    public static void beforeResume(Activity activity) {
        if (activity == null) return;
        RESUMED.put(activity, Boolean.TRUE);
        if (!intermediate(activity) || !hasHandoff(activity)) return;
        releaseDrawGate(activity);
        FirstDrawGate gate = new FirstDrawGate(activity); DRAW_GATES.put(activity, gate);
        activity.getWindow().getDecorView().getViewTreeObserver().addOnPreDrawListener(gate);
        MAIN.postDelayed(gate, 800); // A failed installer must never leave the window undrawable.
    }
    private static void releaseDrawGate(Activity activity) {
        FirstDrawGate gate = DRAW_GATES.remove(activity); if (gate != null) gate.release();
    }
    private static final class FirstDrawGate implements ViewTreeObserver.OnPreDrawListener, Runnable {
        final WeakReference<Activity> host;
        boolean released;
        FirstDrawGate(Activity activity) { host = new WeakReference<Activity>(activity); }
        @Override public boolean onPreDraw() {
            Activity activity = host.get();
            if (released || activity == null || !hasHandoff(activity)) { release(); return true; }
            return false;
        }
        @Override public void run() { release(); }
        void release() {
            if (released) return;
            released = true; MAIN.removeCallbacks(this);
            Activity activity = host.get(); if (activity == null) return;
            View view = activity.getWindow().getDecorView();
            ViewTreeObserver observer = view.getViewTreeObserver(); if (observer.isAlive()) observer.removeOnPreDrawListener(this);
            if (DRAW_GATES.get(activity) == this) DRAW_GATES.remove(activity);
            view.invalidate();
        }
    }

    public static void install(Activity activity) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed() || Boolean.FALSE.equals(RESUMED.get(activity))) return;
        String name = activity.getClass().getName();
        if (name.startsWith(PREFIX + "transfer.")) { clearTask(); return; }
        if (name.contains(".remotecontrol.") || name.contains(".ptpipremotecontrol.")) {
            if ("photos".equals(task()) && name.equals(PREFIX + "remotecontrol.RemoteControlActivity")) {
                if (!TRANSFER_WAITS.containsKey(activity)) {
                    WebTransferWait waiting = new WebTransferWait(activity); TRANSFER_WAITS.put(activity, waiting); MAIN.post(waiting);
                }
            } else clearTask();
            return;
        }
        boolean eligible = name.equals(PREFIX + "devicelist.WiFiActivity") || name.equals(PREFIX + "home.HomeActivity")
                || name.equals(PREFIX + "selectfunction.SelectFunctionActivity");
        if (!eligible) return;
        Binding binding = BINDINGS.get(activity);
        ViewGroup content = (ViewGroup) activity.findViewById(android.R.id.content);
        if (content == null || content.getChildCount() == 0) return;
        if (binding != null && binding.root.getParent() != content) { binding.stop(); BINDINGS.remove(activity); binding = null; }
        if (binding == null) {
            binding = new Binding(activity, content);
            BINDINGS.put(activity, binding);
        }
        binding.start();
        if (hasHandoff(activity) && intermediate(activity)) {
            // The native function screen is an internal handoff, not a second home navigation.
            if (Build.VERSION.SDK_INT >= 34) activity.overrideActivityTransition(Activity.OVERRIDE_TRANSITION_OPEN, 0, 0, PAPER);
            else activity.overridePendingTransition(0, 0);
        }
        releaseDrawGate(activity); // The identical source frame is already above the new shell.
        if (routeTab != null) {
            String selected = routeTab; routeTab = null;
            binding.guide = false; binding.setLegacyVisible(false); binding.tab = selected; binding.render(); binding.scroll.scrollTo(0,0);
            return;
        }
        if (routeFromPhotoPage) {
            routeFromPhotoPage = false;
            if (ConnectionKeeper.hasCameraConnection(activity)) binding.startTask("photos");
            else { clearTask(); binding.tab = "photos"; binding.localSource = false; binding.render(); }
            return;
        }
        if (name.contains(".selectfunction.") || name.contains(".home.")) binding.continueTask();
    }
    public static void pause(Activity activity) { RESUMED.put(activity, Boolean.FALSE); releaseDrawGate(activity); Binding b = BINDINGS.get(activity); if (b != null) b.stop(); WebTransferWait w = TRANSFER_WAITS.remove(activity); if (w != null) MAIN.removeCallbacks(w); }
    public static void destroy(Activity activity) { pause(activity); BINDINGS.remove(activity); RESUMED.remove(activity); }
    public static boolean onBack(Activity activity) {
        Binding b = BINDINGS.get(activity);
        if (b == null) return false;
        if (b.handoffCover != null) {
            // A visible Sony blocker owns Back/cancel; removing our cover cannot consume that action.
            boolean nativeBusy = b.busy(); clearTask();
            if (b.sessionRequest != null) { b.sessionRequest.cancel(); b.sessionRequest = null; }
            b.launching = false; return !nativeBusy;
        }
        if (b.sessionRequest != null) { b.sessionRequest.cancel(); b.sessionRequest = null; clearTask(); return true; }
        if (b.busy()) return false;
        if (b.guide) { b.closeGuide(true); return true; }
        if (b.legacy) { b.showLegacy(false); return true; }
        if (!"camera".equals(b.tab)) { b.switchTab("camera"); return true; }
        clearTask(); return false;
    }
    public static void clearTask() {
        pendingTask = null; pendingUntil = 0;
        if (handoff != null) handoff.lease.cancel();
        handoff = null;
        for (Binding binding : new ArrayList<Binding>(BINDINGS.values())) binding.endHandoff();
        for (FirstDrawGate gate : new ArrayList<FirstDrawGate>(DRAW_GATES.values())) gate.release();
    }
    /** Photo-source navigation requests a task; Sony still owns connection and function switching. */
    public static boolean openCameraPhotos(Activity activity) {
        Binding binding = BINDINGS.get(activity);
        if (binding != null) {
            if (ConnectionKeeper.hasCameraConnection(activity)) binding.startTask("photos");
            else { clearTask(); binding.localSource=false; binding.switchTab("photos"); }
            return true;
        }
        requestTask("photos"); routeFromPhotoPage = true;
        try {
            V3TabMotion.prepare(activity,-1,"shell");
            Intent intent = new Intent(); intent.setClassName(activity, PREFIX + "devicelist.WiFiActivity");
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            activity.startActivity(intent); return true;
        } catch (RuntimeException error) { routeFromPhotoPage = false; clearTask(); Log.e("V3Shell", "Photo task routing failed", error); return false; }
    }
    /** Bottom navigation from the real library; no camera operation is issued. */
    public static boolean openTab(Activity activity, String selected) {
        if (!"camera".equals(selected) && !"settings".equals(selected)) return false;
        clearTask(); routeFromPhotoPage=false;
        Binding binding=BINDINGS.get(activity);
        if(binding!=null){binding.switchTab(selected);return true;}
        routeTab=selected;
        try {
            V3TabMotion.prepare(activity,"camera".equals(selected)?-1:1,"shell");
            Intent intent=new Intent();intent.setClassName(activity,PREFIX+"devicelist.WiFiActivity");
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP);
            activity.startActivity(intent);return true;
        }catch(RuntimeException error){routeTab=null;Log.e("V3Shell","Tab navigation failed",error);return false;}
    }
    private static boolean continueWebApiTransfer(Activity activity) {
        Object controller = field(activity, "mController");
        Object candidate = findController(controller, ".PlayBackSettingController", 0);
        if (candidate != null) {
            try {
                Class<?> properties = Class.forName(PREFIX + "webapi.camera.property.EnumCameraProperty");
                Object cameraFunction = properties.getField("CameraFunction").get(null);
                if (!Boolean.TRUE.equals(properties.getMethod("canSetValue").invoke(cameraFunction))) return false;
                if (field(candidate, "mWebApiEvent") == null) return false;
                Method select = candidate.getClass().getMethod("onIconSelected", int.class);
                // Consume before a command that may already act and then throw. Never replay it.
                clearTask();
                // onIconSelected uses its real selection dialog; Sony creates it in show().
                candidate.getClass().getMethod("show").invoke(candidate);
                select.invoke(candidate, 0); return true;
            } catch (Exception error) { clearTask(); Log.e("V3Shell", "Sony photo mode request failed", error); return true; }
        }
        return false;
    }
    private static final class WebTransferWait implements Runnable {
        final WeakReference<Activity> activity; int attempts;
        WebTransferWait(Activity value) { activity = new WeakReference<Activity>(value); }
        public void run() {
            Activity a = activity.get();
            if (a == null) return;
            if (a.isDestroyed() || a.isFinishing() || !"photos".equals(task())) { TRANSFER_WAITS.remove(a); return; }
            if (a.hasWindowFocus() && continueWebApiTransfer(a)) { TRANSFER_WAITS.remove(a); return; }
            if (++attempts >= 60) {
                clearTask(); TRANSFER_WAITS.remove(a);
                Toast.makeText(a, "相机尚未允许切换照片模式，请稍后在参数中选择相机回放。", Toast.LENGTH_LONG).show();
            } else MAIN.postDelayed(this, 300);
        }
    }
    private static Object findController(Object owner, String suffix, int depth) {
        if (owner == null || depth > 6) return null;
        if (owner.getClass().getName().endsWith(suffix)) return owner;
        Object children = field(owner, "mControllers");
        if (children instanceof Iterable<?>) for (Object child : (Iterable<?>) children) {
            Object result = findController(child, suffix, depth + 1); if (result != null) return result;
        }
        return null;
    }
    public static void requestTask(String task) {
        if (!"shoot".equals(task) && !"photos".equals(task)) return;
        pendingTask = task; pendingUntil = SystemClock.elapsedRealtime() + 120000;
    }
    private static String task() {
        if (SystemClock.elapsedRealtime() > pendingUntil) clearTask();
        return pendingTask;
    }

    private static final class Binding implements Runnable {
        final WeakReference<Activity> host;
        final ViewGroup content;
        final FrameLayout root, retained, presentation, pageStage;
        final ScrollView scroll;
        final LinearLayout shell, body, navigation;
        FrameLayout handoffCover;
        View handoffProgress;
        final ViewTreeObserver.OnPreDrawListener loadingOwner = new ViewTreeObserver.OnPreDrawListener() {
            @Override public boolean onPreDraw() { syncLoadingPresentation(); return true; }
        };
        final Handler handler = new Handler(Looper.getMainLooper());
        final boolean wifi;
        boolean active, guide, legacy, localSource, dispatching, launching;
        long launchStarted;
        V3SessionRouter.Request sessionRequest;
        String guideModel = "";
        String tab = "camera", signature = "";
        Binding(Activity activity, ViewGroup content) {
            host = new WeakReference<Activity>(activity); this.content = content;
            wifi = activity.getClass().getName().contains(".devicelist.");
            root = new FrameLayout(activity); root.setTag(ROOT_TAG); root.setBackgroundColor(PAPER); root.setFitsSystemWindows(false);
            root.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
                @Override public WindowInsets onApplyWindowInsets(View view, WindowInsets insets) {
                    safeArea(insets);
                    return Build.VERSION.SDK_INT >= 30 ? WindowInsets.CONSUMED : insets.consumeSystemWindowInsets();
                }
            });
            retained = new FrameLayout(activity);
            while (content.getChildCount() > 0) {
                View child = content.getChildAt(0); content.removeView(child);
                // These three original top-level roots have no XML padding; the new root owns bars.
                if (child.getFitsSystemWindows()) { child.setFitsSystemWindows(false); child.setPadding(0, 0, 0, 0); }
                retained.addView(child, new FrameLayout.LayoutParams(-1, -1));
            }
            presentation=new FrameLayout(activity);presentation.setBackgroundColor(PAPER);
            root.addView(presentation,new FrameLayout.LayoutParams(-1,-1));
            presentation.addView(retained, new FrameLayout.LayoutParams(-1, -1)); retained.setVisibility(View.GONE);
            shell = new LinearLayout(activity); shell.setOrientation(LinearLayout.VERTICAL); shell.setBackgroundColor(PAPER);
            presentation.addView(shell, new FrameLayout.LayoutParams(-1, -1));
            body = new LinearLayout(activity); body.setOrientation(LinearLayout.VERTICAL);
            scroll = new ScrollView(activity); scroll.setFillViewport(true); scroll.setClipToPadding(false);
            scroll.addView(body, new ScrollView.LayoutParams(-1, -2));
            pageStage=new FrameLayout(activity);pageStage.setBackgroundColor(PAPER);
            pageStage.addView(scroll,new FrameLayout.LayoutParams(-1,-1));
            shell.addView(pageStage, new LinearLayout.LayoutParams(-1, 0, 1));
            navigation = new LinearLayout(activity); navigation.setOrientation(LinearLayout.HORIZONTAL); navigation.setGravity(Gravity.CENTER);
            navigation.setPadding(dp(12), dp(4), dp(12), dp(8));
            shell.addView(navigation, new LinearLayout.LayoutParams(-1, -2));
            content.addView(root, new ViewGroup.LayoutParams(-1, -1));
            WindowInsets currentInsets = activity.getWindow().getDecorView().getRootWindowInsets();
            if (currentInsets != null) safeArea(currentInsets);
            root.requestApplyInsets();
            // These controller-owned blockers must remain above the native task shell.
            for (String id : new String[]{"processing_screen", "wireless_connect_progress_layout"}) {
                View overlay = original(id);
                if (overlay != null && overlay.getParent() instanceof ViewGroup) {
                    ((ViewGroup) overlay.getParent()).removeView(overlay);
                    root.addView(overlay, new FrameLayout.LayoutParams(-1, -1));
                }
            }
            syncHandoff();
            render();
        }
        void beginHandoff() {
            final Activity activity = host.get(); if (activity == null || task() == null) return;
            if (!hasHandoff(activity)) {
                Bitmap frame = null;
                int width = presentation.getWidth(), height = presentation.getHeight();
                if (width > 0 && height > 0) try {
                    float scale = Math.min(1f, (float) Math.sqrt(MAX_HANDOFF_PIXELS / ((double) width * height)));
                    frame = Bitmap.createBitmap(Math.max(1, Math.round(width * scale)), Math.max(1, Math.round(height * scale)), Bitmap.Config.ARGB_8888);
                    // Snapshot only this sibling: both the handoff HUD and controller-owned
                    // processing overlays live outside presentation and cannot be baked in.
                    Canvas canvas = new Canvas(frame); canvas.scale(scale, scale); presentation.draw(canvas);
                } catch (RuntimeException unavailable) { frame = null; }
                catch (OutOfMemoryError unavailable) { frame = null; }
                final Handoff created = new Handoff(task(), activity, frame); handoff = created;
                MAIN.postDelayed(new Runnable() { @Override public void run() {
                    if (handoff != created) return;
                    clearTask(); toast("相机准备时间较长，请检查连接后重试。");
                }}, 12000);
            }
            syncHandoff();
        }
        void syncHandoff() {
            Activity activity = host.get();
            if (activity == null || !hasHandoff(activity)) { endHandoff(); return; }
            if (handoffCover != null) { syncLoadingPresentation(); return; }
            handoffCover = new FrameLayout(activity); handoffCover.setBackgroundColor(PAPER);
            handoffCover.setClickable(true); handoffCover.setFocusable(true);
            if (handoff.frame != null) {
                ImageView image = new ImageView(activity); image.setImageBitmap(handoff.frame); image.setScaleType(ImageView.ScaleType.FIT_XY);
                image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                handoffCover.addView(image, new FrameLayout.LayoutParams(-1, -1));
            }
            LinearLayout progress = horizontal(); progress.setPadding(dp(18), dp(14), dp(18), dp(14)); progress.setBackground(round(0xffefefea, 20));
            ProgressBar spinner = new ProgressBar(activity); spinner.setIndeterminateTintList(ColorStateList.valueOf(INK));
            progress.addView(spinner, new LinearLayout.LayoutParams(dp(22), dp(22)));
            TextView message = text("photos".equals(task()) ? "正在打开相机照片…" : "正在准备遥控拍摄…", 13, INK, false);
            message.setPadding(dp(12), 0, 0, 0); progress.addView(message, new LinearLayout.LayoutParams(-2, -2));
            handoffCover.addView(progress, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
            handoffProgress = progress;
            // Controller-owned processing overlays remain ABOVE this visual handoff.
            root.addView(handoffCover, Math.min(1, root.getChildCount()), new FrameLayout.LayoutParams(-1, -1));
            root.getViewTreeObserver().addOnPreDrawListener(loadingOwner);
            syncLoadingPresentation();
        }
        void endHandoff() {
            if (handoffCover == null) return;
            ViewTreeObserver observer = root.getViewTreeObserver();
            if (observer.isAlive()) observer.removeOnPreDrawListener(loadingOwner);
            if (handoffCover.getParent() instanceof ViewGroup) ((ViewGroup) handoffCover.getParent()).removeView(handoffCover);
            handoffCover.removeAllViews(); handoffCover = null; handoffProgress = null;
        }
        void syncLoadingPresentation() {
            Activity activity = host.get();
            if (handoffProgress == null || activity == null) return;
            boolean otherWindow = !activity.hasWindowFocus() || nativeDialogVisible();
            boolean show = LoadingPresentation.showHandoff(handoffCover != null, busy(), otherWindow);
            int visibility = show ? View.VISIBLE : View.INVISIBLE;
            if (handoffProgress.getVisibility() != visibility) handoffProgress.setVisibility(visibility);
        }
        boolean nativeDialogVisible() {
            Activity activity = host.get(); if (activity == null) return false;
            Object dialogs = field(field(activity, "mDialogManager"), "mDialogList");
            if (dialogs instanceof Map<?, ?>) for (Object dialog : ((Map<?, ?>) dialogs).values()) {
                if (dialogShowing(dialog)) return true;
            }
            Object manager = field(activity, "dialogManager");
            if (dialogShowing(field(manager, "mFilePath")) || dialogShowing(field(manager, "mFileName"))) return true;
            for (String name : new String[] {"mPairingDialog", "mSyncDialog", "mPreviewingDialog"})
                if (dialogShowing(field(activity, name))) return true;
            return false;
        }
        boolean dialogShowing(Object value) {
            return value instanceof Dialog && ((Dialog) value).isShowing();
        }
        boolean nativeFailureVisible() {
            Activity activity = host.get(); if (activity == null) return false;
            Object dialogs = field(field(activity, "mDialogManager"), "mDialogList");
            if (dialogs instanceof Map<?, ?>) for (Map.Entry<?, ?> entry : ((Map<?, ?>) dialogs).entrySet()) {
                if (String.valueOf(entry.getKey()).contains("Failed") && entry.getValue() instanceof Dialog
                        && ((Dialog) entry.getValue()).isShowing()) return true;
            }
            Object dialog = field(field(activity, "dialogManager"), "mFilePath");
            return dialog instanceof Dialog && !(dialog instanceof ProgressDialog) && ((Dialog) dialog).isShowing();
        }
        void safeArea(WindowInsets insets) {
            int left, top, right, bottom;
            if (Build.VERSION.SDK_INT >= 30) {
                Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                left = bars.left; top = bars.top; right = bars.right; bottom = bars.bottom;
            } else {
                left = insets.getSystemWindowInsetLeft(); top = insets.getSystemWindowInsetTop();
                right = insets.getSystemWindowInsetRight(); bottom = insets.getSystemWindowInsetBottom();
            }
            if (root.getPaddingLeft() != left || root.getPaddingTop() != top || root.getPaddingRight() != right || root.getPaddingBottom() != bottom)
                root.setPadding(left, top, right, bottom);
        }
        void start() { active = true; handler.removeCallbacks(this); syncHandoff(); if(!guide&&!legacy&&"camera".equals(tab))render(); refresh(); handler.postDelayed(this, handoffCover != null ? 150 : 1200); }
        void stop() {
            active = false; handler.removeCallbacks(this); launching = false;
            if (sessionRequest != null) { sessionRequest.cancel(); sessionRequest = null; clearTask(); }
            V3TabMotion.cancel(presentation); V3TabMotion.cancel(pageStage);
        }
        public void run() {
            Activity a = host.get();
            if (!active || a == null || a.isDestroyed() || a.isFinishing()) return;
            refresh();
            if (handoffCover != null && nativeFailureVisible()) { launching = false; clearTask(); }
            if (sessionRequest != null || !wifi && task() != null && !launching && handoffCover != null) continueTask();
            if (launching && SystemClock.elapsedRealtime() - launchStarted > 10000L) { launching = false; clearTask(); toast("尚未进入拍摄，请重试。"); }
            syncHandoff();
            handler.postDelayed(this, sessionRequest != null || handoffCover != null ? 150 : 1200);
        }
        void refresh() {
            Activity a = host.get(); if (a == null) return;
            Configuration config = a.getResources().getConfiguration();
            V3SessionRouter.Snapshot session = V3SessionRouter.inspect(a);
            String next = cameraName() + "|" + ConnectionKeeper.hasCameraConnection(a) + "|" + session.state + "|" + session.detail + "|" + ConnectionKeeper.isEnabled(a) + "|" + ConnectionKeeper.isRunning() + "|" + config.orientation + "|" + config.fontScale;
            if (!signature.equals(next)) { signature = next; if (!guide && !legacy) render(); }
        }
        void render() {
            Activity a = host.get(); if (a == null) return;
            V3TabMotion.cancel(presentation); V3TabMotion.cancel(pageStage);
            a.getWindow().setStatusBarColor(PAPER); a.getWindow().setNavigationBarColor(PAPER);
            root.setBackgroundColor(PAPER);
            body.removeAllViews(); body.setPadding(dp(24), dp(24), dp(24), dp(12));
            if (guide) guidePage();
            else if ("photos".equals(tab)) photosPage();
            else if ("settings".equals(tab)) settingsPage();
            else cameraPage();
            nav();
        }
        void cameraPage() {
            Activity a = host.get(); boolean connected = ConnectionKeeper.hasCameraConnection(a);
            if (a.getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE) {
                landscapeCameraPage(connected); return;
            }
            LinearLayout top = horizontal();
            TextView brand = text("IMAGING EDGE", 12, INK, true); brand.setLetterSpacing(.12f);
            top.addView(brand, new LinearLayout.LayoutParams(0, dp(48), 1));
            TextView details = button("•••", false, new Runnable() { public void run() { deviceDetails(); }}); details.setContentDescription("相机详情与切换");
            details.setBackgroundColor(android.graphics.Color.TRANSPARENT);
            top.addView(details, new LinearLayout.LayoutParams(dp(48), dp(48))); body.addView(top);
            String name = cameraName();
            add(text("你的相机",13,MUTED,false),14);
            add(text(name.length() == 0 ? "添加你的相机。" : name, 40, INK, true), 6);
            add(text(connectionLabel(connected, name), 12, connected ? 0xff258658 : MUTED, false), 8);
            if (name.toUpperCase().replace(" ", "").equals("ZV-E10")) {
                int imageId = a.getResources().getIdentifier("v3_zve10", "drawable", a.getPackageName());
                if (imageId != 0) {
                    ImageView image = new ImageView(a); image.setImageResource(imageId); image.setScaleType(ImageView.ScaleType.CENTER_CROP);
                    image.setContentDescription("Sony ZV-E10 相机"); add(image, 4, 200);
                } else add(new CameraDrawing(a), 4, 160);
            } else add(new CameraDrawing(a), 4, 160);
            add(text("专注这一刻。",23,INK,true),8);
            add(text(connected?"选择拍摄或导入，继续你的创作。":"选好要做的事，连接后继续。",13,MUTED,false),9);
            add(button("遥控拍摄",true,new Runnable(){public void run(){startTask("shoot");}}),20);
            TextView importAction=importButton();importAction.setMinHeight(dp(48));
            add(importAction,6);
            View breathingRoom=new View(a);body.addView(breathingRoom,new LinearLayout.LayoutParams(-1,0,1));
            recentSection();
        }
        void landscapeCameraPage(boolean connected) {
            Activity a = host.get(); body.setPadding(dp(24), dp(8), dp(24), dp(16));
            LinearLayout top = horizontal();
            TextView brand = text("IMAGING EDGE", 11, INK, true); brand.setLetterSpacing(.12f);
            top.addView(brand, new LinearLayout.LayoutParams(0, dp(48), 1));
            TextView details = button("•••", false, new Runnable(){public void run(){deviceDetails();}});
            details.setBackgroundColor(android.graphics.Color.TRANSPARENT);
            details.setContentDescription("相机详情与切换"); top.addView(details, new LinearLayout.LayoutParams(dp(48), dp(48))); body.addView(top);
            LinearLayout board = horizontal(); String name = cameraName(); View illustration;
            int imageId = name.toUpperCase().replace(" ", "").equals("ZV-E10") ? a.getResources().getIdentifier("v3_zve10", "drawable", a.getPackageName()) : 0;
            if (imageId != 0) {
                ImageView image = new ImageView(a); image.setImageResource(imageId); image.setScaleType(ImageView.ScaleType.CENTER_CROP);
                image.setContentDescription("Sony ZV-E10 相机"); illustration = image;
            } else illustration = new CameraDrawing(a);
            board.addView(illustration, new LinearLayout.LayoutParams(0, dp(140), .38f));
            LinearLayout words = new LinearLayout(a); words.setOrientation(LinearLayout.VERTICAL); words.setPadding(dp(20), 0, 0, 0);
            words.addView(text(name.length()==0 ? "添加你的相机。" : name, 26, INK, true));
            TextView state = text(connectionLabel(connected, name), 11, connected ? 0xff258658 : MUTED, false);
            state.setPadding(0, dp(3), 0, dp(10)); words.addView(state);
            LinearLayout actions = horizontal();
            TextView shoot = button("遥控拍摄", true, new Runnable(){public void run(){startTask("shoot");}});
            TextView photos = importButton();
            LinearLayout.LayoutParams first = new LinearLayout.LayoutParams(0, -2, 1); first.rightMargin=dp(4);
            actions.addView(shoot, first); actions.addView(photos, new LinearLayout.LayoutParams(0, -2, 1)); words.addView(actions);
            board.addView(words, new LinearLayout.LayoutParams(0, -2, .62f)); add(board, 0);
            recentSection();
        }
        TextView importButton(){
            TextView v=button("导入照片 ↓",false,new Runnable(){public void run(){startTask("photos");}});
            GradientDrawable background=round(0xffe5edfa,28);background.setStroke(dp(1),0xffc1d1e8);
            v.setBackground(background);v.setTextColor(0xff0059bb);return v;
        }
        void recentSection(){
            LinearLayout strip=horizontal();strip.setMinimumHeight(dp(48));
            TextView title=text("最近导入  ›",11,MUTED,false);title.setGravity(Gravity.CENTER_VERTICAL);title.setMinHeight(dp(48));
            title.setContentDescription("最近导入，查看全部照片");title.setOnClickListener(new View.OnClickListener(){public void onClick(View v){openLocal();}});
            strip.addView(title,new LinearLayout.LayoutParams(0,dp(48),1));
            int screenWidth=Math.round(host.get().getResources().getDisplayMetrics().widthPixels/host.get().getResources().getDisplayMetrics().density);
            int width=Math.max(150,Math.min(232,screenWidth-124));
            strip.addView(V3Recent.create(host.get(),3),new LinearLayout.LayoutParams(dp(width),-2));add(strip,18);
        }
        void photosPage() {
            add(text("照片", 36, INK, true), 8);
            LinearLayout segments = horizontal(); segments.setBackground(round(0xffe9eae4, 20));
            segments.addView(button("相机中", true, new Runnable() { public void run() { }}), new LinearLayout.LayoutParams(0, dp(50), 1));
            segments.addView(button("已导入", false, new Runnable() { public void run() { localSource = true; openLocal(); }}), new LinearLayout.LayoutParams(0, dp(50), 1));
            add(segments, 24);
            add(cameraIllustration(host.get()),24,100);
            add(text("相机尚未连接", 21, INK, true), 40);
            add(text("连接相机后，照片会显示在这里。已导入的照片可以随时浏览。", 14, MUTED, false), 12);
            add(button("连接相机，查看照片", true, new Runnable() { public void run() { startTask("photos"); }}), 24);
        }
        void settingsPage() {
            final Activity a = host.get();
            add(text("设置", 36, INK, true), 8); section("连接");
            LinearLayout row = horizontal(); row.setPadding(dp(16), dp(14), dp(12), dp(14)); row.setBackground(round(0xffffffff, 18));
            LinearLayout words = new LinearLayout(a); words.setOrientation(1);
            words.addView(text("后台保持连接", 16, INK, true));
            boolean enabled = ConnectionKeeper.isEnabled(a), connected = ConnectionKeeper.hasCameraConnection(a);
            String state = !enabled ? "已关闭" : !connected ? "已开启 · 连接相机后生效" : ConnectionKeeper.isRunning() ? "连接服务正在运行" : "已开启 · 等待连接服务";
            TextView subtitle = text(state, 12, MUTED, false); subtitle.setPadding(0, dp(6), 0, 0); words.addView(subtitle);
            row.addView(words, new LinearLayout.LayoutParams(0, -2, 1));
            Switch toggle = new Switch(a); toggle.setChecked(enabled); toggle.setShowText(false); toggle.setMinWidth(dp(56)); toggle.setMinHeight(dp(48));
            toggle.setThumbTintList(ColorStateList.valueOf(0xffffffff));
            toggle.setTrackTintList(new ColorStateList(new int[][]{new int[]{android.R.attr.state_checked},new int[]{}},new int[]{0xff34c759,0xffc7c7cc}));
            toggle.setContentDescription("后台保持连接");
            toggle.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() { public void onCheckedChanged(android.widget.CompoundButton b, boolean value) { ConnectionKeeper.setEnabled(a, value); handler.post(new Runnable(){public void run(){refresh();}}); }});
            row.addView(toggle, new LinearLayout.LayoutParams(-2, dp(48))); add(row, 12);
            add(row("当前连接", connected ? "会话已连接" : "未连接", new Runnable(){public void run(){deviceDetails();}}), 6);
            section("照片与相机");
            add(row("导入质量与保存位置", "沿用相机与应用当前设置", new Runnable(){public void run(){openOriginalSettings();}}), 4);
            add(row("相机工具", "位置关联、远程电源等", new Runnable(){public void run(){showLegacy(true);}}), 4);
            add(row("连接帮助", "查看相机端操作", new Runnable(){public void run(){showGuide();}}), 4);
            add(row("检查相机资料", V3CatalogPrompt.status(a), new Runnable(){public void run(){V3CatalogPrompt.requestUpdate(a);}}), 4);
            section("关于");
            add(row("关于 Imaging Edge", "4.0.1", new Runnable(){public void run(){launchSettings("settings.AboutScreen");}}), 4);
        }
        void guidePage() {
            add(button("‹  返回", false, new Runnable(){public void run(){closeGuide(true);}}), 0);
            add(text("连接相机", 13, MUTED, false), 18);
            add(text("在相机上，\n做好连接准备。", 31, INK, true), 12);
            String name = guideModel.length() == 0 ? cameraName() : guideModel;
            V3CameraGuide.Guide model = V3CameraGuide.forCamera(name);
            final boolean importing = "photos".equals(task());
            add(row(model.model + " · 操作说明", "选择其他机型  ›", new Runnable(){public void run(){
                new AlertDialog.Builder(host.get()).setTitle("选择说明机型")
                        .setItems(V3CameraGuide.modelNames(), new android.content.DialogInterface.OnClickListener(){
                            public void onClick(android.content.DialogInterface dialog, int index) {
                                guideModel = V3CameraGuide.supportedModels()[index]; render();
                            }
                        }).setNegativeButton("取消", null).show();
            }}), 14);
            LinearLayout card = new LinearLayout(host.get()); card.setOrientation(1); card.setPadding(dp(18),dp(18),dp(18),dp(18)); card.setBackground(round(0xffeeefe9,20));
            StringBuilder instructions = new StringBuilder(model.title(importing));
            int step = 1;
            for (String instruction : model.steps(importing)) instructions.append("\n\n").append(step++).append("  ").append(instruction);
            TextView path = text(instructions.toString(), 15, INK, true); card.addView(path); add(card, 24);
            add(text("轻点下方“开始连接”，按应用提示扫码或输入相机 Wi-Fi 密码。部分老机型不提供二维码。", 14, MUTED, false), 22);
            add(text(model.note, 12, MUTED, false), 14);
            add(text("相机 Wi-Fi 通常不提供互联网。保留此连接，即可继续拍摄或导入。", 12, MUTED, false), 22);
            add(button("开始连接", true, new Runnable(){public void run(){beginOriginalPairing();}}), 26);
            add(button("使用已保存的相机", false, new Runnable(){public void run(){boolean pending=task()!=null;closeGuide(false);if(pending)connectSaved();}}), 6);
        }
        void nav() {
            navigation.removeAllViews(); navigation.setVisibility(guide ? View.GONE : View.VISIBLE);
            String[] keys={"camera","photos","settings"}, labels={"相机","照片","设置"};
            for (int i=0;i<keys.length;i++) {
                final String key=keys[i]; final TextView label=text(labels[i],12,key.equals(tab)?INK:MUTED,key.equals(tab));
                label.setGravity(Gravity.CENTER); label.setMinHeight(dp(56)); label.setContentDescription(labels[i]); label.setSelected(key.equals(tab));
                label.setOnClickListener(new View.OnClickListener(){public void onClick(View v){
                    clearTask();
                    if ("photos".equals(key)) { localSource=true; openLocal(); return; }
                    if(key.equals(tab))return;
                    switchTab(key);
                }});
                navigation.addView(label,new LinearLayout.LayoutParams(0,dp(56),1));
            }
        }
        void startTask(String target) {
            if (sessionRequest != null && task() == null) { sessionRequest.cancel(); sessionRequest = null; }
            if (launching || sessionRequest != null) { toast("正在准备相机，请稍候。"); return; }
            if (busy()) { toast("正在处理相机操作，请稍候。"); return; }
            requestTask(target);
            if (continueTask()) return;
            if (wifi && savedCount() > 0) { connectSaved(); return; }
            showGuide();
        }
        boolean busy() {
            for (String name : new String[]{"processing_screen", "wireless_connect_progress_layout"}) {
                View blocker = original(name);
                if (blocker != null && blocker.isShown()) return true;
            }
            return false;
        }
        boolean continueTask() {
            if (task() == null) { if (sessionRequest != null) { sessionRequest.cancel(); sessionRequest = null; } return false; }
            if (dispatching || launching) return false;
            Activity a = host.get(); if (a == null || !active) return false;
            V3SessionRouter.Snapshot session = V3SessionRouter.inspect(a);
            if (sessionRequest != null) {
                int decision = sessionRequest.step(session.camera, task(), session.state, busy(), SystemClock.elapsedRealtime());
                if (decision == V3SessionRouter.WAIT) return true;
                sessionRequest = null;
                if (decision != V3SessionRouter.READY) { clearTask(); toast("相机会话未准备好，请重试连接。"); return true; }
            } else if (session.state == V3SessionRouter.WAIT || (session.state == V3SessionRouter.READY && busy())) {
                beginHandoff();
                sessionRequest = new V3SessionRouter.Request(session.camera, task(), SystemClock.elapsedRealtime());
                handler.removeCallbacks(this); handler.postDelayed(this, 150); return true;
            }
            if (wifi) {
                if (session.state != V3SessionRouter.READY) return false;
                if ("photos".equals(task()) && "PtpIpRemoteShooting".equals(String.valueOf(field(session.camera, "mModel")))) {
                    // This native dispatcher only opens remote shooting; it cannot open a photo browser.
                    clearTask();
                    new AlertDialog.Builder(a).setTitle("请切换相机连接方式")
                            .setMessage("当前连接用于遥控拍摄。请在相机上开启“发送到智能手机”，再查看相机照片。")
                            .setPositiveButton("知道了", null).show();
                    return true;
                }
                // Re-running discovery suppresses cameraAdded for an already registered camera.
                // Reuse Sony's original function dispatcher and preserve the task across native navigation.
                beginHandoff(); launching = true; launchStarted = SystemClock.elapsedRealtime();
                if (V3SessionRouter.reuseWifi(a, session.camera)) return true;
                launching = false; clearTask(); toast("暂时无法打开相机功能，请重试连接。"); return true;
            }
            if (session.state == V3SessionRouter.STALE) { clearTask(); toast("相机会话已失效，请重新连接。"); return true; }
            if (busy()) return false;
            View target = original("shoot".equals(task()) ? "remote_shooting" : a.getClass().getName().contains(".selectfunction.") ? "smartphone_transfer" : "content_viewer");
            if (target == null || !target.isEnabled() || !target.hasOnClickListeners()) return false;
            // Preserve the task and identical source frame until the REAL destination installs.
            // Sony may prepare a function asynchronously after this click.
            beginHandoff(); dispatching=true; launching=true; launchStarted=SystemClock.elapsedRealtime();
            try { boolean accepted=target.performClick(); if(!accepted){launching=false;clearTask();}return accepted; }
            catch (RuntimeException failed) { launching=false; clearTask(); Log.e("V3Shell", "Native function dispatch failed", failed); return true; }
            finally { dispatching=false; }
        }
        String connectionLabel(boolean connected, String name) {
            V3SessionRouter.Snapshot session = V3SessionRouter.inspect(host.get());
            if (session.state == V3SessionRouter.WAIT) return "正在准备相机会话…";
            if (session.state == V3SessionRouter.STALE) return "相机会话已失效 · 请重新连接";
            return connected ? "相机会话已连接" : name.length() == 0 ? "连接相机，开始你的创作。" : "未连接 · 已保留相机记录";
        }
        void connectSaved() {
            View list = original("wifi_activity_device_list_view");
            if (list instanceof ListView) {
                ListAdapter adapter=((ListView)list).getAdapter();
                if (adapter != null && adapter.getCount()>0) {
                    View row=adapter.getView(0,null,(ListView)list);
                    int id=id("wifi_camera_connecting_status_icon"); View connect=row.findViewById(id);
                    if (connect != null && connect.isEnabled() && connect.hasOnClickListeners()) { beginHandoff(); connect.performClick(); return; }
                }
            }
            clearTask(); toast("暂时无法读取已保存的相机，请重新选择。"); deviceDetails();
        }
        int savedCount() {
            View list=original("wifi_activity_device_list_view");
            return list instanceof ListView && ((ListView)list).getAdapter()!=null ? ((ListView)list).getAdapter().getCount() : 0;
        }
        String cameraName() {
            Activity a=host.get(); if(a==null)return "";
            try {
                Object camera = field(field(a,"controller"),"mCamera");
                if(camera==null) {
                    Class<?> manager=Class.forName(PREFIX+"camera.CameraManagerUtil"); Object instance=manager.getField("sInstance").get(null);
                    if(instance!=null)camera=instance.getClass().getMethod("getPrimaryCamera").invoke(instance);
                }
                Object desc=field(camera,"mDdXml"), value=field(desc,"mFriendlyName");
                if(value instanceof String && ((String)value).length()>0)return clean((String)value);
            } catch(Exception ignored) { }
            if(wifi) {
                View list=original("wifi_activity_device_list_view");
                if(list instanceof ListView) {
                    ListAdapter adapter=((ListView)list).getAdapter();
                    if(adapter!=null && adapter.getCount()>0) {
                        Object item=adapter.getItem(0);
                        for(String getter:new String[]{"realmGet$friendlyName","realmGet$modelName"}) {
                            Object value=call(item,getter); if(value instanceof String && ((String)value).trim().length()>0)return clean((String)value);
                        }
                    }
                }
            }
            View label=original("select_function_device_name");
            if(label instanceof TextView) { String value=((TextView)label).getText().toString(); if(!"-".equals(value))return clean(value); }
            return "";
        }
        void deviceDetails() {
            Activity a=host.get(); if(a==null)return;
            new AlertDialog.Builder(a).setTitle(cameraName().length()==0?"相机":cameraName())
                .setItems(new String[]{"连接这台相机", "添加相机", "切换或管理相机", "连接详情"},new android.content.DialogInterface.OnClickListener(){public void onClick(android.content.DialogInterface d,int which){
                    if(which==0)startTask("shoot");
                    else if(which==1){showGuide();}
                    else if(which==2){clearTask();if(!click("other_registered_cameras_text"))showLegacy(true);}
                    else connectionDetails();
                }}).setNegativeButton("取消",null).show();
        }
        void connectionDetails() {
            Activity a=host.get();boolean connected=ConnectionKeeper.hasCameraConnection(a);
            new AlertDialog.Builder(a).setTitle("连接详情").setMessage((cameraName().length()==0?"尚未读取相机名称":cameraName())+"\n\n"+(connected?"相机会话已建立。拍摄就绪由取景和相机当前状态决定。":"当前未检测到有效相机会话。")+"\n\n后台偏好："+(ConnectionKeeper.isEnabled(a)?"开启":"关闭")+"\n连接服务："+(ConnectionKeeper.isRunning()?"正在运行":"未运行"))
                .setPositiveButton("知道了",null).show();
        }
        void beginOriginalPairing() {
            if (click("new_camera_connect")) return;
            // HomeActivity routes via its existing camera drawer; no synthetic camera Intent.
            showLegacy(true); toast("请在相机管理中选择添加相机，完成后会继续原任务。");
        }
        void openLocal() {
            clearTask();
            V3TabMotion.prepare(host.get(),"settings".equals(tab)?-1:1,"photos");
            if(click("quick_viewer") || click("card_for_qv_layout")){V3TabMotion.afterNavigation(host.get());return;}
            if(launch("v7.contentviewer.grid.ContentViewerGridActivity"))V3TabMotion.afterNavigation(host.get());
        }
        void openOriginalSettings() {
            V3TabMotion.prepare(host.get(),1,"settings");
            if(click("setup")){V3TabMotion.afterNavigation(host.get());return;}
            if(launch("settings.SettingsActivity"))V3TabMotion.afterNavigation(host.get());
        }
        void launchSettings(String suffix){
            V3TabMotion.prepare(host.get(),1,"settings");if(launch(suffix))V3TabMotion.afterNavigation(host.get());
        }
        boolean launch(String suffix) {
            Activity a=host.get(); if(a==null)return false;
            try { Intent intent=new Intent();intent.setClassName(a,PREFIX+suffix);a.startActivity(intent);return true; }
            catch(RuntimeException error){Log.w("V3Shell","Original screen unavailable: "+suffix,error);toast("暂时无法打开此功能，请在相机工具中访问。");return false;}
        }
        int tabIndex(String value){return "camera".equals(value)?0:"photos".equals(value)?1:2;}
        void switchTab(final String target){
            if(legacy){tab=target;showLegacy(false);return;}
            if(guide){tab=target;closeGuide(true);return;}
            if(target.equals(tab))return;
            final int direction=tabIndex(target)>tabIndex(tab)?1:-1;
            V3TabMotion.cancel(presentation);
            V3TabMotion.replace(pageStage,scroll,direction,0,new Runnable(){public void run(){tab=target;render();scroll.scrollTo(0,0);}});
        }
        void showGuide(){
            guideModel = "";
            if(guide)return;
            V3TabMotion.replace(presentation,shell,1,0,new Runnable(){public void run(){guide=true;render();scroll.scrollTo(0,0);}});
        }
        void closeGuide(final boolean clearIntent){
            V3TabMotion.replace(presentation,shell,-1,0,new Runnable(){public void run(){guide=false;if(clearIntent)clearTask();render();scroll.scrollTo(0,0);}});
        }
        void setLegacyVisible(boolean show){
            legacy=show;if(show)guide=false;
            retained.setVisibility(show?View.VISIBLE:View.GONE); shell.setVisibility(show?View.GONE:View.VISIBLE);
        }
        void showLegacy(final boolean show) {
            if(show==legacy)return;
            V3TabMotion.cancel(pageStage);
            V3TabMotion.replace(presentation,show?retained:shell,show?1:-1,show?0:1,new Runnable(){public void run(){
                setLegacyVisible(show);if(!show){render();scroll.scrollTo(0,0);}
            }});
            if(show)toast("相机工具 · 系统返回可回到新版");
        }
        boolean click(String name) {
            View target=original(name);
            return target!=null && target.isEnabled() && target.hasOnClickListeners() && target.performClick();
        }
        View original(String name) { int value=id(name); return value==0?null:root.findViewById(value); }
        int id(String name) { Activity a=host.get(); return a==null?0:a.getResources().getIdentifier(name,"id",a.getPackageName()); }
        int dp(int n) { Activity a=host.get();return Math.round(n*(a==null?1:a.getResources().getDisplayMetrics().density)); }
        TextView text(String value,int size,int color,boolean bold) {
            TextView v=new TextView(host.get());v.setText(value);v.setTextSize(size);v.setTextColor(color);v.setIncludeFontPadding(false);
            v.setTypeface(Typeface.create(bold?"sans-serif-medium":"sans-serif",Typeface.NORMAL));v.setLineSpacing(dp(3),1);return v;
        }
        TextView button(String title,boolean primary,final Runnable action) {
            TextView v=text(title,15,primary?0xffffffff:INK,primary);v.setGravity(Gravity.CENTER);v.setMinHeight(dp(54));v.setPadding(dp(15),dp(12),dp(15),dp(12));
            v.setBackground(round(primary?INK:0xffe9eae4,28));v.setFocusable(true);v.setClickable(true);v.setAccessibilityDelegate(new View.AccessibilityDelegate(){@Override public void onInitializeAccessibilityNodeInfo(View host,android.view.accessibility.AccessibilityNodeInfo info){super.onInitializeAccessibilityNodeInfo(host,info);info.setClassName(Button.class.getName());}});
            v.setOnClickListener(new View.OnClickListener(){public void onClick(View view){action.run();}});return v;
        }
        LinearLayout horizontal(){LinearLayout row=new LinearLayout(host.get());row.setOrientation(0);row.setGravity(Gravity.CENTER_VERTICAL);return row;}
        View row(String title,String subtitle,final Runnable action) {
            LinearLayout row=horizontal();row.setMinimumHeight(dp(64));row.setPadding(dp(4),dp(8),dp(4),dp(8));
            LinearLayout words=new LinearLayout(host.get());words.setOrientation(1);words.addView(text(title,15,INK,false));
            if(subtitle.length()>0){TextView sub=text(subtitle,11,MUTED,false);sub.setPadding(0,dp(4),0,0);words.addView(sub);}
            row.addView(words,new LinearLayout.LayoutParams(0,-2,1));TextView arrow=text("›",24,MUTED,false);arrow.setGravity(Gravity.CENTER);row.addView(arrow,new LinearLayout.LayoutParams(dp(32),dp(48)));
            row.setFocusable(true);row.setContentDescription(title+"，"+subtitle);row.setOnClickListener(new View.OnClickListener(){public void onClick(View v){action.run();}});return row;
        }
        GradientDrawable round(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}
        void add(View view,int top){add(view,top,-2);} void add(View view,int top,int height){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,height<0?height:dp(height));p.topMargin=dp(top);body.addView(view,p);}
        void divider(){View v=new View(host.get());v.setBackgroundColor(0xffe7e7e2);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,dp(1));p.topMargin=dp(18);p.bottomMargin=dp(6);body.addView(v,p);}
        void section(String title){add(text(title,12,MUTED,false),28);}
        void toast(String value){Activity a=host.get();if(a!=null)Toast.makeText(a,value,Toast.LENGTH_LONG).show();}
    }
    private static Object field(Object owner,String name){if(owner==null)return null;try{return owner.getClass().getField(name).get(owner);}catch(Exception e){return null;}}
    private static Object call(Object owner,String method){if(owner==null)return null;try{return owner.getClass().getMethod(method).invoke(owner);}catch(Exception e){return null;}}
    private static String clean(String value){return value.replace('\n',' ').replace('\r',' ').trim();}
    private static final class CameraDrawing extends View {
        final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        CameraDrawing(Context context){super(context);setContentDescription("相机");}
        @Override protected void onDraw(Canvas canvas){super.onDraw(canvas);float u=Math.min(getWidth()/280f,getHeight()/180f);canvas.save();canvas.translate((getWidth()-220*u)/2,(getHeight()-130*u)/2);canvas.scale(u,u);paint.setColor(0xffdedfd7);paint.setStyle(Paint.Style.FILL);canvas.drawRoundRect(new RectF(6,18,214,124),17,17,paint);paint.setColor(0xff292d28);canvas.drawRoundRect(new RectF(0,12,220,118),17,17,paint);canvas.drawRoundRect(new RectF(25,0,84,35),8,8,paint);paint.setColor(0xff4e554b);canvas.drawCircle(123,66,55,paint);paint.setColor(0xff141a13);canvas.drawCircle(123,66,46,paint);paint.setColor(0xff62725b);canvas.drawCircle(123,66,32,paint);paint.setColor(0xff1e2b1b);canvas.drawCircle(123,66,25,paint);paint.setColor(0xffa7b79f);canvas.drawCircle(113,55,8,paint);canvas.restore();}
    }
}
