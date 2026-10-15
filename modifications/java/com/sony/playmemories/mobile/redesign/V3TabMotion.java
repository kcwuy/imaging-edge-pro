package com.sony.playmemories.mobile.redesign;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import java.lang.ref.WeakReference;
import java.util.WeakHashMap;

/** Directional native presentation. Never navigates, retries a click or detaches a controller view. */
public final class V3TabMotion {
    private static final int PAPER = 0xfff7f7f4;
    private static final long ROUTE_LIFETIME_MS = 3500;
    private static final int MAX_SNAPSHOT_PIXELS = 2000000;
    private static final Object ROUTES = new Object();
    private static final WeakHashMap<Activity, Entry> ENTRIES = new WeakHashMap<Activity, Entry>();
    private static final WeakHashMap<Activity, Prepared> PREPARED = new WeakHashMap<Activity, Prepared>();
    // Values may not strongly reference a View: its Context/parent would retain the weak key.
    private static final WeakHashMap<FrameLayout, WeakReference<LocalMotion>> MOTIONS =
            new WeakHashMap<FrameLayout, WeakReference<LocalMotion>>();
    private static Pending pending;
    private V3TabMotion() { }

    private static final class Entry {
        final int direction;
        Entry(int direction) { this.direction = direction; }
    }
    private static final class Prepared {
        final int direction;
        final long when;
        Prepared(int direction, long when) { this.direction = direction; this.when = when; }
    }
    private static final class Pending {
        final WeakReference<Activity> source;
        final int direction, taskId;
        final String family;
        final long when;
        Pending(Activity activity, int direction, String family, long when) {
            source = new WeakReference<Activity>(activity); this.direction = direction; this.family = family; this.when = when;
            taskId = taskId(activity);
        }
    }

    private static int direction(int value) { return value < 0 ? -1 : 1; }
    private static int taskId(Activity activity) {
        try { return activity.getTaskId(); } catch (RuntimeException unavailable) { return -1; }
    }
    private static boolean validFamily(String value) { return "shell".equals(value) || "photos".equals(value) || "settings".equals(value); }
    private static String family(Activity host) {
        if (host == null) return null;
        String name = host.getClass().getName();
        if (name.equals("com.sony.playmemories.mobile.devicelist.WiFiActivity")
                || name.equals("com.sony.playmemories.mobile.home.HomeActivity")
                || name.equals("com.sony.playmemories.mobile.selectfunction.SelectFunctionActivity")) return "shell";
        if (name.equals("com.sony.playmemories.mobile.contentviewer.grid.ContentViewerGridActivity")
                || name.equals("com.sony.playmemories.mobile.v7.contentviewer.grid.ContentViewerGridActivity")
                || name.startsWith("com.sony.playmemories.mobile.transfer.")
                && (name.endsWith(".ListViewActivity") || name.endsWith(".GridViewActivity")
                || name.endsWith(".CdsListViewActivity") || name.endsWith(".CdsGridViewActivity")
                || name.endsWith(".MtpListViewActivity") || name.endsWith(".MtpGridViewActivity"))) return "photos";
        if (name.startsWith("com.sony.playmemories.mobile.settings.")
                || name.equals("com.sony.playmemories.mobile.home.AboutAppActivity")
                || name.equals("com.sony.playmemories.mobile.setup.TransferNotificationSettingsActivity")) return "settings";
        return null; // In particular: remote capture, full-screen detail and protocol pages.
    }

    /** Before a real startActivity, +1 means right-in/left-out; -1 means left-in/right-out. */
    public static void prepare(Activity source, int requestedDirection, String destinationFamily) {
        if (source == null) return;
        long now = SystemClock.uptimeMillis(); int way = direction(requestedDirection);
        synchronized (ROUTES) {
            PREPARED.put(source, new Prepared(way, now));
            pending = validFamily(destinationFamily) ? new Pending(source, way, destinationFamily, now) : null;
        }
    }

    /** Call before onCreate. onResume also handles an existing CLEAR_TOP/SINGLE_TOP target. */
    public static void configure(Activity host) { configureHost(host); }
    public static void onResume(Activity host) { configureHost(host); }

    private static void configureHost(Activity host) {
        if (host == null) return;
        String actualFamily = family(host); int entering = 1;
        synchronized (ROUTES) {
            long now = SystemClock.uptimeMillis();
            Entry previous = ENTRIES.get(host); if (previous != null) entering = previous.direction;
            if (pending != null) {
                Activity source = pending.source.get(); long age = now - pending.when;
                if (source == null || age < 0 || age > ROUTE_LIFETIME_MS) pending = null;
                else if (source != host) {
                    int destinationTask = taskId(host);
                    boolean sameTask = pending.taskId < 0 || destinationTask < 0 || pending.taskId == destinationTask;
                    if (sameTask && pending.family.equals(actualFamily)) entering = pending.direction;
                    // An intervening screen must never donate a route to a later, unrelated page.
                    pending = null;
                }
            }
            if (actualFamily != null) ENTRIES.put(host, new Entry(entering));
        }
        if (actualFamily == null || Build.VERSION.SDK_INT < 34) return;
        int[] opening = animations(host, entering), closing = animations(host, -entering);
        host.overrideActivityTransition(Activity.OVERRIDE_TRANSITION_OPEN, opening[0], opening[1], PAPER);
        host.overrideActivityTransition(Activity.OVERRIDE_TRANSITION_CLOSE, closing[0], closing[1], PAPER);
    }

    /** Immediately after a successful explicit navigation. Keeps the caller's prepared direction. */
    @SuppressWarnings("deprecation")
    public static void afterNavigation(Activity host) {
        if (host == null) return;
        int way = 1;
        synchronized (ROUTES) {
            Prepared route = PREPARED.remove(host);
            long age = route == null ? -1 : SystemClock.uptimeMillis() - route.when;
            if (route != null && age >= 0 && age <= ROUTE_LIFETIME_MS) way = route.direction;
        }
        int[] pair = animations(host, way);
        host.overridePendingTransition(pair[0], pair[1]);
    }

    private static int[] animations(Activity host, int way) {
        if (!ValueAnimator.areAnimatorsEnabled()) return new int[] {0, 0};
        String enter = way > 0 ? "v3_page_enter_from_right" : "v3_page_enter_from_left";
        String exit = way > 0 ? "v3_page_exit_to_left" : "v3_page_exit_to_right";
        // Missing resources fall back to an immediate change, never an unrelated fade or direction.
        return new int[] {animation(host, enter), animation(host, exit)};
    }
    private static int animation(Activity host, String name) {
        int id = host.getResources().getIdentifier(name, "anim", "com.sony.playmemories.mobile");
        return id == 0 ? host.getResources().getIdentifier(name, "anim", host.getPackageName()) : id;
    }

    /**
     * change runs synchronously exactly once, after capture and before registering this motion.
     * Thus render() may cancel the stage inside change without cancelling the new animation.
     * style 0: two opaque pages slide horizontally; style 1: old UI rounds/shrinks and exits right.
     * stage must sit below Sony's processing overlays; incoming must remain its descendant.
     */
    public static void replace(FrameLayout stage, View incoming, int requestedDirection, int style, Runnable change) {
        if (Looper.myLooper() != Looper.getMainLooper()) throw new IllegalStateException("replace requires UI thread");
        cancel(stage);
        if (change == null) return;
        boolean animate = stage != null && incoming != null && incoming != stage
                && stage.isAttachedToWindow() && ValueAnimator.areAnimatorsEnabled();
        Snapshot snapshot = animate ? capture(stage) : null;
        try { change.run(); }
        catch (RuntimeException failure) { if (snapshot != null) snapshot.releaseUnused(); throw failure; }
        catch (Error failure) { if (snapshot != null) snapshot.releaseUnused(); throw failure; }
        if (snapshot == null) return;
        if (!stage.isAttachedToWindow() || !descendant(incoming, stage) || incoming.getVisibility() != View.VISIBLE
                || current(stage) != null) { snapshot.releaseUnused(); return; }
        LocalMotion motion = new LocalMotion(stage, incoming, snapshot, direction(requestedDirection), style == 1);
        MOTIONS.put(stage, new WeakReference<LocalMotion>(motion));
        try { motion.attach(); }
        catch (RuntimeException unavailable) { motion.finish(); }
        catch (OutOfMemoryError unavailable) { motion.finish(); }
    }

    /** Cancels only this helper's overlay/transforms; it never removes a real child view. */
    public static void cancel(FrameLayout stage) {
        if (stage == null) return;
        LocalMotion motion = current(stage); if (motion != null) motion.finish();
    }
    private static LocalMotion current(FrameLayout stage) {
        WeakReference<LocalMotion> reference = MOTIONS.get(stage); return reference == null ? null : reference.get();
    }
    private static boolean descendant(View view, View root) {
        for (View current = view; current != null; ) {
            if (current == root) return true;
            ViewParent parent = current.getParent(); current = parent instanceof View ? (View) parent : null;
        }
        return false;
    }

    private static final class Snapshot {
        Bitmap bitmap;
        final Rect rect;
        Snapshot(Bitmap bitmap, Rect rect) { this.bitmap = bitmap; this.rect = rect; }
        void releaseUnused() { if (bitmap != null) { bitmap.recycle(); bitmap = null; } }
    }
    private static Snapshot capture(FrameLayout stage) {
        Rect visible = new Rect();
        if (stage.getWidth() <= 0 || stage.getHeight() <= 0 || !stage.getLocalVisibleRect(visible) || visible.isEmpty()) return null;
        long pixels = (long) visible.width() * visible.height();
        double scale = Math.min(1.0, Math.sqrt(MAX_SNAPSHOT_PIXELS / (double) pixels));
        int width = Math.max(1, (int) Math.floor(visible.width() * scale));
        int height = Math.max(1, (int) Math.floor(visible.height() * scale));
        Bitmap bitmap = null;
        try {
            bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap); canvas.drawColor(background(stage));
            canvas.scale(width / (float) visible.width(), height / (float) visible.height());
            canvas.translate(-visible.left, -visible.top); stage.draw(canvas);
            return new Snapshot(bitmap, new Rect(visible));
        } catch (RuntimeException unavailable) { if (bitmap != null) bitmap.recycle(); return null; }
        catch (OutOfMemoryError exhausted) { if (bitmap != null) bitmap.recycle(); return null; }
    }
    private static int background(View view) {
        for (View current = view; current != null; ) {
            Drawable drawable = current.getBackground();
            if (drawable instanceof ColorDrawable && (((ColorDrawable) drawable).getColor() >>> 24) == 255) return ((ColorDrawable) drawable).getColor();
            ViewParent parent = current.getParent(); current = parent instanceof View ? (View) parent : null;
        }
        return PAPER;
    }

    /** An overlay drawable has no Activity/Context and never receives touch events. */
    private static final class OldPage extends Drawable {
        Bitmap bitmap;
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        final Path clip = new Path();
        final RectF target = new RectF();
        float dx, dy, scale = 1, radius;
        OldPage(Snapshot snapshot) { bitmap = snapshot.bitmap; snapshot.bitmap = null; setBounds(snapshot.rect); }
        @Override public void draw(Canvas canvas) {
            if (bitmap == null || bitmap.isRecycled()) return;
            Rect bounds = getBounds(); float halfWidth = bounds.width() / 2f, halfHeight = bounds.height() / 2f;
            int checkpoint = canvas.save();
            canvas.translate(bounds.exactCenterX() + dx, bounds.exactCenterY() + dy); canvas.scale(scale, scale);
            target.set(-halfWidth, -halfHeight, halfWidth, halfHeight);
            if (radius > 0) { clip.reset(); clip.addRoundRect(target, radius, radius, Path.Direction.CW); canvas.clipPath(clip); }
            canvas.drawBitmap(bitmap, null, target, paint); canvas.restoreToCount(checkpoint);
        }
        @Override public void setAlpha(int ignored) { } // Opacity remains 1 throughout both styles.
        @Override public void setColorFilter(ColorFilter ignored) { }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
        void releaseDisplayed() { bitmap = null; } // GC owns displayed pixels; never recycle an in-flight frame.
    }

    private static final class LocalMotion extends AnimatorListenerAdapter implements
            ValueAnimator.AnimatorUpdateListener, ViewTreeObserver.OnPreDrawListener,
            View.OnAttachStateChangeListener, View.OnLayoutChangeListener {
        final WeakReference<FrameLayout> stage;
        final WeakReference<View> incoming;
        final OldPage old;
        final int direction;
        final boolean card;
        final float baseX, baseY, baseScaleX, baseScaleY, width, height, radius;
        final OvershootInterpolator rebound = new OvershootInterpolator(1.15f);
        WeakReference<View> touchShield;
        ValueAnimator animator;
        boolean finished, attached, observing;
        LocalMotion(FrameLayout stage, View incoming, Snapshot snapshot, int direction, boolean card) {
            this.stage = new WeakReference<FrameLayout>(stage); this.incoming = new WeakReference<View>(incoming);
            this.old = new OldPage(snapshot); this.direction = direction; this.card = card;
            baseX = incoming.getTranslationX(); baseY = incoming.getTranslationY();
            baseScaleX = incoming.getScaleX(); baseScaleY = incoming.getScaleY();
            width = snapshot.rect.width(); height = snapshot.rect.height(); radius = 22 * stage.getResources().getDisplayMetrics().density;
        }
        void attach() {
            FrameLayout host = stage.get(); View page = incoming.get();
            if (host == null || page == null || !host.isAttachedToWindow()) { finish(); return; }
            host.getOverlay().add(old); attached = true;
            host.addOnAttachStateChangeListener(this);
            host.addOnLayoutChangeListener(this);
            // The drawable snapshot intentionally has no touch handler. Block taps while it
            // hides the incoming real controls; never replace their listeners or replay a tap.
            View shield = new View(host.getContext());
            shield.setClickable(true); shield.setFocusable(false); shield.setFocusableInTouchMode(false);
            shield.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
            float topZ = 0;
            for (int i = 0; i < host.getChildCount(); i++) topZ = Math.max(topZ, host.getChildAt(i).getZ());
            shield.setZ(topZ + host.getResources().getDisplayMetrics().density);
            touchShield = new WeakReference<View>(shield);
            host.addView(shield, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
            shield.bringToFront();
            transform(0);
            host.getViewTreeObserver().addOnPreDrawListener(this); observing = true;
            host.invalidate();
        }
        @Override public boolean onPreDraw() {
            FrameLayout host = stage.get(); View page = incoming.get();
            removePreDraw();
            if (finished || host == null || page == null || current(host) != this
                    || !host.isAttachedToWindow() || !descendant(page, host) || page.getVisibility() != View.VISIBLE) { finish(); return true; }
            animator = ValueAnimator.ofFloat(0, 1); animator.setDuration(card ? 380 : 280);
            animator.setInterpolator(card ? new LinearInterpolator() : new DecelerateInterpolator(1.35f));
            animator.addUpdateListener(this); animator.addListener(this); animator.start();
            return true;
        }
        @Override public void onAnimationUpdate(ValueAnimator value) {
            FrameLayout host = stage.get(); View page = incoming.get();
            if (finished || host == null || page == null || current(host) != this || !host.isAttachedToWindow()) { finish(); return; }
            transform(((Number) value.getAnimatedValue()).floatValue());
        }
        void transform(float progress) {
            View page = incoming.get(); if (page == null) return;
            page.setAlpha(1f);
            if (card) {
                float shrink = smooth(Math.min(1, progress / .32f));
                float exit = smooth(Math.max(0, Math.min(1, (progress - .20f) / .80f)));
                old.scale = 1 - .10f * shrink - .06f * exit;
                old.dx = width * (.045f * shrink + 1.10f * exit); old.dy = height * .025f * shrink;
                old.radius = radius * shrink;
                float arrival = rebound.getInterpolation(progress);
                page.setTranslationX(baseX - width * .10f * (1 - arrival)); page.setTranslationY(baseY);
                page.setScaleX(baseScaleX * (.985f + .015f * arrival)); page.setScaleY(baseScaleY * (.985f + .015f * arrival));
            } else {
                old.dx = -direction * width * progress;
                page.setTranslationX(baseX + direction * width * (1 - progress)); page.setTranslationY(baseY);
                page.setScaleX(baseScaleX); page.setScaleY(baseScaleY);
            }
            old.invalidateSelf();
        }
        static float smooth(float value) { return value * value * (3 - 2 * value); }
        @Override public void onAnimationEnd(Animator animation) { finish(); }
        @Override public void onAnimationCancel(Animator animation) { finish(); }
        @Override public void onViewAttachedToWindow(View view) { }
        @Override public void onViewDetachedFromWindow(View view) { finish(); }
        @Override public void onLayoutChange(View view, int left, int top, int right, int bottom,
                int oldLeft, int oldTop, int oldRight, int oldBottom) {
            // An Activity may handle rotation/insets without detaching its guide/legacy stage.
            // Its old snapshot coordinates must not survive the stage's new geometry.
            if (left != oldLeft || top != oldTop || right != oldRight || bottom != oldBottom) finish();
        }
        void removePreDraw() {
            FrameLayout host = stage.get();
            if (observing && host != null && host.getViewTreeObserver().isAlive()) host.getViewTreeObserver().removeOnPreDrawListener(this);
            observing = false;
        }
        void finish() {
            if (finished) return; finished = true;
            FrameLayout host = stage.get(); boolean owns = host != null && current(host) == this;
            if (owns) MOTIONS.remove(host);
            removePreDraw();
            if (host != null) {
                host.removeOnAttachStateChangeListener(this); host.removeOnLayoutChangeListener(this);
                if (attached) host.getOverlay().remove(old);
                View shield = touchShield == null ? null : touchShield.get();
                if (shield != null && shield.getParent() == host) host.removeView(shield);
            }
            touchShield = null;
            View page = incoming.get();
            if (owns && page != null) {
                page.setTranslationX(baseX); page.setTranslationY(baseY); page.setScaleX(baseScaleX); page.setScaleY(baseScaleY); page.setAlpha(1f);
            }
            if (animator != null) { animator.removeAllUpdateListeners(); animator.removeAllListeners(); animator.cancel(); animator = null; }
            // The drawable may have reached RenderThread, so even a cancelled snapshot is left to GC.
            old.releaseDisplayed();
        }
    }

    /** Compatibility for older callers; directional callers use replace(), never an opacity fade. */
    public static void show(View content) {
        if (content != null) { content.animate().cancel(); content.setAlpha(1f); content.setTranslationX(0); }
    }
}
