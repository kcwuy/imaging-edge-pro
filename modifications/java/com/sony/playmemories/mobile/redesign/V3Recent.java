package com.sony.playmemories.mobile.redesign;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.util.Size;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Recent imported photographs, read-only and native. create() returns the row/empty state,
 * without a heading. The caller should use WRAP_CONTENT rather than a fixed preview height.
 *
 * Source verified against original v7 LocalContents.updateData(): its ClientDb Realm query
 * limits filePathOriginal to the configured/default saving destinations and TRANSFER_DIR_PATH,
 * sorted dateTimeAdded DESC, filePathOriginal DESC. getContentUri(path) resolves that exact
 * record's originalId and volume. This class never queries all MediaStore images, guesses a
 * storage directory, refreshes/writes the Sony database, requests permission or modifies files.
 * Realm objects stay on the main thread. Only copied metadata reaches the decoder.
 *
 * attach/detach and Activity lifecycle callbacks own all work/listeners; no shell hooks needed.
 * The app owns LocalContents/Realm; this class never closes or refreshes that shared singleton.
 * Unavailable permissions, codecs or deleted records produce a light honest empty/error state.
 * Clicks open the original v7 imported grid, without constructing unsupported detail extras.
 */
public final class V3Recent {
    private static final String SOURCE = "com.sony.playmemories.mobile.v7.contentviewer.LocalContents";
    private static final String LISTENER = SOURCE + "$IDataChangedListener";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final int MUTED = 0xff70716f;
    private static final ThreadFactory WORKER_THREADS = new ThreadFactory() {
        @Override public Thread newThread(Runnable work) { return new Thread(work, "ImagingEdge-RecentThumbnail"); }
    };
    private V3Recent() { }

    /** UI-thread API. maxCount is deliberately limited to the requested one-to-three images. */
    public static View create(Activity activity, int maxCount) {
        if (activity == null) throw new IllegalArgumentException("Activity required");
        if (Looper.myLooper() != Looper.getMainLooper()) throw new IllegalStateException("create must run on UI thread");
        return new RecentView(activity, Math.max(1, Math.min(3, maxCount)));
    }

    /** Optional explicit refresh after import. Does not refresh or mutate Sony's database. */
    public static void invalidate(View view) {
        if (!(view instanceof RecentView)) return;
        final WeakReference<RecentView> weak = new WeakReference<RecentView>((RecentView) view);
        if (Looper.myLooper() == Looper.getMainLooper()) ((RecentView) view).requestRefresh();
        else MAIN.post(new Runnable() { @Override public void run() { RecentView row = weak.get(); if (row != null) row.requestRefresh(); } });
    }

    /** Last successfully displayed metadata signature, useful for diagnostics, never a file path fallback. */
    public static String signature(View view) { return view instanceof RecentView ? ((RecentView) view).loadedSignature : null; }

    private static final class Photo {
        final String path;
        final Uri uri;
        final int angle;
        final long added, modified, size;
        Photo(String path, Uri uri, int angle, long added, long modified, long size) {
            this.path = path; this.uri = uri; this.angle = angle; this.added = added; this.modified = modified; this.size = size;
        }
        void appendSignature(StringBuilder target) {
            target.append(path.length()).append(':').append(path).append('|').append(uri).append('|').append(angle)
                    .append('|').append(added).append('|').append(modified).append('|').append(size).append(';');
        }
    }

    private static final class Token {
        volatile boolean cancelled;
        final CancellationSignal signal = new CancellationSignal();
        void cancel() { cancelled = true; signal.cancel(); }
    }

    private static final class Tile extends FrameLayout {
        final ImageView image;
        Tile(Context context) {
            super(context); image = new ImageView(context); image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            GradientDrawable shape = new GradientDrawable(); shape.setColor(0xffe7e7e2);
            shape.setCornerRadius(10 * getResources().getDisplayMetrics().density);
            setBackground(shape); setClipToOutline(true);
            addView(image, new FrameLayout.LayoutParams(-1, -1));
        }
        @Override protected void onMeasure(int width, int height) {
            int pixels = MeasureSpec.getSize(width);
            if (MeasureSpec.getMode(width) == MeasureSpec.UNSPECIFIED) pixels = Math.round(100 * getResources().getDisplayMetrics().density);
            super.onMeasure(MeasureSpec.makeMeasureSpec(pixels, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(Math.round(pixels * 2f / 3f), MeasureSpec.EXACTLY));
        }
    }

    private static final class RecentView extends LinearLayout {
        final WeakReference<Activity> host;
        final Context app;
        final int count;
        final LinearLayout images;
        final TextView message;
        final Tile[] tiles;
        final Application.ActivityLifecycleCallbacks lifecycle;
        final Runnable poll;
        boolean attached, foreground = true;
        int generation;
        String loadedSignature;
        Object local, dataListener;
        Class<?> localClass, listenerClass;
        ThreadPoolExecutor executor;
        Future<?> future;
        Token token;
        MetadataTask metadata;

        RecentView(Activity activity, int count) {
            super(activity); this.host = new WeakReference<Activity>(activity); this.app = activity.getApplicationContext(); this.count = count;
            setOrientation(VERTICAL);
            images = new LinearLayout(activity); images.setOrientation(HORIZONTAL); images.setBaselineAligned(false);
            images.setVisibility(GONE); addView(images, new LinearLayout.LayoutParams(-1, -2));
            tiles = new Tile[count];
            for (int index = 0; index < count; index++) {
                tiles[index] = new Tile(activity); tiles[index].setVisibility(INVISIBLE);
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1);
                if (index > 0) params.leftMargin = dp(8);
                images.addView(tiles[index], params);
            }
            message = new TextView(activity); message.setTextColor(MUTED); message.setTextSize(13);
            message.setTypeface(android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL));
            message.setGravity(Gravity.CENTER_VERTICAL); message.setMinHeight(dp(48)); message.setPadding(0, dp(8), 0, dp(8));
            message.setText(words("正在读取已导入照片…", "Loading imported photos…"));
            message.setOnClickListener(new OnClickListener() { @Override public void onClick(View ignored) { openImported(); } });
            addView(message, new LinearLayout.LayoutParams(-1, -2));
            poll = new Poll(this);
            lifecycle = new Lifecycle(this);
        }

        int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
        String words(String chinese, String english) {
            Locale locale = getResources().getConfiguration().getLocales().get(0);
            return "zh".equals(locale.getLanguage()) ? chinese : english;
        }
        boolean ready() {
            Activity activity = host.get();
            return attached && foreground && activity != null && !activity.isFinishing() && !activity.isDestroyed();
        }
        @Override protected void onAttachedToWindow() {
            super.onAttachedToWindow(); if (attached) return; attached = true;
            Activity activity = host.get(); if (activity != null) activity.getApplication().registerActivityLifecycleCallbacks(lifecycle);
            resume();
        }
        @Override protected void onDetachedFromWindow() { detach(); super.onDetachedFromWindow(); }
        void resume() {
            foreground = true; if (!ready()) return;
            if (executor == null || executor.isShutdown()) {
                executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
                        new ArrayBlockingQueue<Runnable>(1), WORKER_THREADS);
            }
            loadedSignature = null; // A permission change/read failure may recover on return.
            MAIN.removeCallbacks(poll); requestRefresh(); MAIN.postDelayed(poll, 10000);
        }
        void pause() {
            foreground = false; MAIN.removeCallbacks(poll); cancelWork();
            if (executor != null) { executor.shutdownNow(); executor = null; }
        }
        void detach() {
            if (!attached) return;
            attached = false; pause(); unregisterSource();
            Activity activity = host.get(); if (activity != null) activity.getApplication().unregisterActivityLifecycleCallbacks(lifecycle);
            clearImages(); loadedSignature = null;
        }
        void cancelWork() {
            generation++;
            if (metadata != null) { MAIN.removeCallbacks(metadata); metadata = null; }
            if (token != null) { token.cancel(); token = null; }
            if (future != null) { future.cancel(true); future = null; }
            if (executor != null) executor.getQueue().clear();
        }

        void requestRefresh() {
            if (!ready()) return;
            cancelWork();
            try {
                if (local == null) {
                    localClass = Class.forName(SOURCE);
                    local = localClass.getMethod("getInstance", Context.class).invoke(null, app);
                    registerSource();
                }
                Object data = localClass.getMethod("getData").invoke(local);
                if (!(data instanceof List)) throw new IllegalStateException("Sony imported list unavailable");
                metadata = new MetadataTask(this, generation, (List<?>) data);
                MAIN.post(metadata);
            } catch (Exception unavailable) { showUnavailable(); }
        }

        void registerSource() {
            try {
                listenerClass = Class.forName(LISTENER);
                dataListener = Proxy.newProxyInstance(listenerClass.getClassLoader(), new Class<?>[] {listenerClass}, new SourceListener(this));
                localClass.getMethod("registerDataChangedListener", listenerClass).invoke(local, dataListener);
            } catch (Exception optionalListenerUnavailable) { dataListener = null; }
        }
        void unregisterSource() {
            if (local != null && dataListener != null) try {
                localClass.getMethod("unregisterDataChangedListener", listenerClass).invoke(local, dataListener);
            } catch (Exception ignored) { }
            dataListener = null; local = null; localClass = null; listenerClass = null;
        }

        void snapshotComplete(int expected, final ArrayList<Photo> photos) {
            if (!ready() || expected != generation) return;
            metadata = null;
            // Clear emptiness before signature comparison: deleted images cannot remain on screen.
            if (photos.isEmpty()) {
                loadedSignature = null; clearImages();
                message.setText(words("还没有已导入的照片", "No imported photos yet")); message.setVisibility(VISIBLE); return;
            }
            StringBuilder signature = new StringBuilder(); for (Photo photo : photos) photo.appendSignature(signature);
            final String nextSignature = signature.toString();
            if (nextSignature.equals(loadedSignature)) return;
            final Token request = new Token(); token = request;
            final int edge = Math.min(512, Math.max(160, images.getWidth() > 0 ? (images.getWidth() - dp(8) * (count - 1)) / count : dp(120)));
            try {
                future = executor.submit(new DecodeTask(this, photos, request, edge, expected, nextSignature));
            } catch (RuntimeException stoppedExecutor) { showUnavailable(); }
        }

        void clearImages() {
            for (Tile tile : tiles) {
                tile.image.setImageDrawable(null); tile.setOnClickListener(null); tile.setContentDescription(null);
                tile.setVisibility(INVISIBLE); tile.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
            }
            // Displayed bitmaps are released to GC, never recycled while RenderThread may hold a frame.
            images.setVisibility(GONE);
        }
        void render(ArrayList<Bitmap> bitmaps, ArrayList<Photo> photos) {
            clearImages();
            if (bitmaps.isEmpty()) { showUnavailable(); return; }
            for (int index = 0; index < bitmaps.size(); index++) {
                Tile tile = tiles[index]; tile.image.setImageBitmap(bitmaps.get(index)); tile.setVisibility(VISIBLE);
                tile.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES); tile.setFocusable(true);
                String file = photos.get(index).path; int slash = Math.max(file.lastIndexOf('/'), file.lastIndexOf('\\'));
                String title = slash < 0 ? file : file.substring(slash + 1);
                tile.setContentDescription(words("已导入照片：", "Imported photo: ") + title + words("，打开已导入影像", ", open imported library"));
                tile.setOnClickListener(new OnClickListener() { @Override public void onClick(View ignored) { openImported(); } });
            }
            images.setVisibility(VISIBLE); message.setVisibility(GONE);
        }
        void showUnavailable() {
            loadedSignature = null; clearImages();
            message.setText(words("照片预览暂不可用，点按查看已导入影像", "Preview unavailable. Tap to open imported images.")); message.setVisibility(VISIBLE);
        }
        void openImported() {
            Activity activity = host.get(); if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
            try {
                V3TabMotion.prepare(activity,1,"photos");
                Intent intent = new Intent(); intent.setClassName(activity, "com.sony.playmemories.mobile.v7.contentviewer.grid.ContentViewerGridActivity");
                activity.startActivity(intent);
                V3TabMotion.afterNavigation(activity);
            } catch (RuntimeException unavailable) {
                message.setText(words("暂时无法打开已导入影像", "Imported library is unavailable.")); message.setVisibility(VISIBLE);
            }
        }
    }

    /** Static tasks/callbacks deliberately never retain an Activity through an anonymous outer this. */
    private static final class Poll implements Runnable {
        final WeakReference<RecentView> owner;
        Poll(RecentView view) { owner = new WeakReference<RecentView>(view); }
        @Override public void run() {
            RecentView row = owner.get(); if (row == null || !row.ready()) return;
            row.requestRefresh(); MAIN.postDelayed(this, 10000);
        }
    }
    private static final class Lifecycle implements Application.ActivityLifecycleCallbacks {
        final WeakReference<RecentView> owner;
        Lifecycle(RecentView view) { owner = new WeakReference<RecentView>(view); }
        @Override public void onActivityResumed(Activity activity) { RecentView row = owner.get(); if (row != null && row.host.get() == activity) row.resume(); }
        @Override public void onActivityPaused(Activity activity) { RecentView row = owner.get(); if (row != null && row.host.get() == activity) row.pause(); }
        @Override public void onActivityDestroyed(Activity activity) { RecentView row = owner.get(); if (row != null && row.host.get() == activity) row.detach(); }
        @Override public void onActivityCreated(Activity activity, Bundle state) { }
        @Override public void onActivityStarted(Activity activity) { }
        @Override public void onActivityStopped(Activity activity) { }
        @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) { }
    }
    private static final class SourceListener implements InvocationHandler, Runnable {
        final WeakReference<RecentView> owner;
        SourceListener(RecentView view) { owner = new WeakReference<RecentView>(view); }
        @Override public Object invoke(Object proxy, Method method, Object[] arguments) {
            if ("hashCode".equals(method.getName())) return System.identityHashCode(proxy);
            if ("equals".equals(method.getName())) return arguments != null && arguments.length == 1 && proxy == arguments[0];
            if ("toString".equals(method.getName())) return "V3Recent imported-list listener";
            if ("onDataChanged".equals(method.getName())) MAIN.post(this);
            return null;
        }
        @Override public void run() { RecentView row = owner.get(); if (row != null) row.requestRefresh(); }
    }
    private static final class DecodeTask implements Runnable {
        final WeakReference<RecentView> owner;
        final Context app;
        final ArrayList<Photo> photos;
        final Token token;
        final int edge, generation, count;
        final String signature;
        DecodeTask(RecentView view, ArrayList<Photo> photos, Token token, int edge, int generation, String signature) {
            owner = new WeakReference<RecentView>(view); app = view.app; count = view.count;
            this.photos = photos; this.token = token; this.edge = edge; this.generation = generation; this.signature = signature;
        }
        @Override public void run() {
            ArrayList<Bitmap> bitmaps = new ArrayList<Bitmap>(); ArrayList<Photo> displayed = new ArrayList<Photo>();
            boolean failed = false;
            for (Photo photo : photos) {
                if (token.cancelled || Thread.currentThread().isInterrupted() || bitmaps.size() == count) break;
                Bitmap bitmap = decode(app, photo, edge, token);
                if (bitmap == null) { failed = true; continue; }
                bitmaps.add(bitmap); displayed.add(photo);
            }
            if (token.cancelled || Thread.currentThread().isInterrupted()) { recycleUndisplayed(bitmaps); return; }
            if (!MAIN.post(new Delivery(owner, bitmaps, displayed, token, generation, failed ? null : signature))) recycleUndisplayed(bitmaps);
        }
    }
    private static final class Delivery implements Runnable {
        final WeakReference<RecentView> owner;
        final ArrayList<Bitmap> bitmaps;
        final ArrayList<Photo> photos;
        final Token token;
        final int generation;
        final String signature;
        Delivery(WeakReference<RecentView> owner, ArrayList<Bitmap> bitmaps, ArrayList<Photo> photos, Token token, int generation, String signature) {
            this.owner = owner; this.bitmaps = bitmaps; this.photos = photos; this.token = token; this.generation = generation; this.signature = signature;
        }
        @Override public void run() {
            RecentView row = owner.get();
            if (row == null || !row.ready() || row.generation != generation || token.cancelled) { recycleUndisplayed(bitmaps); return; }
            row.render(bitmaps, photos); row.loadedSignature = bitmaps.isEmpty() ? null : signature;
            row.future = null; if (row.token == token) row.token = null;
        }
    }

    /** Main-thread batches keep Realm ownership correct and avoid scanning a large movie list in one frame. */
    private static final class MetadataTask implements Runnable {
        final WeakReference<RecentView> owner;
        final int generation;
        final List<?> data;
        final ArrayList<Photo> photos = new ArrayList<Photo>();
        int index;
        MetadataTask(RecentView view, int generation, List<?> data) { owner = new WeakReference<RecentView>(view); this.generation = generation; this.data = data; }
        @Override public void run() {
            RecentView row = owner.get(); if (row == null || !row.ready() || row.generation != generation) return;
            try {
                int end = Math.min(data.size(), index + 32);
                for (; index < end && photos.size() < row.count * 4; index++) {
                    Object item = data.get(index);
                    Object mime = getter(item, "mimeType");
                    if (!(mime instanceof String) || !((String) mime).startsWith("image/")) continue;
                    Object path = getter(item, "filePathOriginal");
                    if (!(path instanceof String) || ((String) path).length() == 0) continue;
                    Uri uri = null;
                    try { uri = (Uri) row.localClass.getMethod("getContentUri", String.class).invoke(row.local, path); }
                    catch (Exception unavailableUri) { /* Only that record's own saved path remains a fallback. */ }
                    Object angle = getter(item, "orientation");
                    photos.add(new Photo((String) path, uri, angle instanceof Number ? ((Number) angle).intValue() : 0,
                            time(getter(item, "dateTimeAdded")), time(getter(item, "dateTimeLastModified")), number(getter(item, "size"))));
                }
                if (index < data.size() && photos.size() < row.count * 4) MAIN.post(this);
                else row.snapshotComplete(generation, photos);
            } catch (Exception unavailableData) { if (row.generation == generation) { row.metadata = null; row.showUnavailable(); } }
        }
    }

    private static Object getter(Object object, String field) throws Exception { return object.getClass().getMethod("realmGet$" + field).invoke(object); }
    private static long time(Object value) { return value instanceof Date ? ((Date) value).getTime() : 0; }
    private static long number(Object value) { return value instanceof Number ? ((Number) value).longValue() : 0; }

    private static Bitmap decode(Context context, Photo photo, int edge, Token token) {
        if (token.cancelled) return null;
        Bitmap bitmap = null;
        // The platform can provide a RAW/HEIF preview without decoding a full sensor image.
        if (Build.VERSION.SDK_INT >= 29 && photo.uri != null) try {
            bitmap = context.getContentResolver().loadThumbnail(photo.uri, new Size(edge, Math.max(1, edge * 2 / 3)), token.signal);
            if (token.cancelled) { if (bitmap != null) bitmap.recycle(); return null; }
            if (bitmap != null) return bounded(bitmap, edge * 2);
        } catch (Exception unavailableThumbnail) { if (bitmap != null) bitmap.recycle(); bitmap = null; }
        catch (OutOfMemoryError exhausted) { if (bitmap != null) bitmap.recycle(); return null; }
        try {
            BitmapFactory.Options options = new BitmapFactory.Options(); options.inJustDecodeBounds = true;
            try (InputStream stream = open(context, photo)) { BitmapFactory.decodeStream(stream, null, options); }
            if (options.outWidth <= 0 || options.outHeight <= 0 || token.cancelled) return null;
            options.inSampleSize = 1;
            while (Math.max(options.outWidth, options.outHeight) / options.inSampleSize > edge * 2) options.inSampleSize *= 2;
            options.inJustDecodeBounds = false; options.inPreferredConfig = Bitmap.Config.ARGB_8888;
            try (InputStream stream = open(context, photo)) { bitmap = BitmapFactory.decodeStream(stream, null, options); }
            if (bitmap == null) return null;
            if (token.cancelled) { bitmap.recycle(); return null; }
            if (photo.angle == 90 || photo.angle == 180 || photo.angle == 270) {
                Matrix rotation = new Matrix(); rotation.postRotate(photo.angle);
                Bitmap rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), rotation, true);
                if (rotated != bitmap) bitmap.recycle(); bitmap = rotated;
            }
            return bounded(bitmap, edge * 2);
        } catch (IOException unreadable) { if (bitmap != null) bitmap.recycle(); return null; }
        catch (RuntimeException unsupported) { if (bitmap != null) bitmap.recycle(); return null; }
        catch (OutOfMemoryError exhausted) { if (bitmap != null) bitmap.recycle(); return null; }
    }

    private static InputStream open(Context context, Photo photo) throws IOException {
        if (photo.uri != null) try {
            InputStream input = context.getContentResolver().openInputStream(photo.uri); if (input != null) return input;
        } catch (IOException unavailable) { } catch (SecurityException unavailable) { }
        if (photo.path.startsWith("content://")) {
            InputStream input = context.getContentResolver().openInputStream(Uri.parse(photo.path));
            if (input != null) return input; throw new IOException("Imported item unavailable");
        }
        return new FileInputStream(photo.path.startsWith("file://") ? Uri.parse(photo.path).getPath() : photo.path);
    }
    private static Bitmap bounded(Bitmap bitmap, int edge) {
        int longest = Math.max(bitmap.getWidth(), bitmap.getHeight()); if (longest <= edge) return bitmap;
        float ratio = edge / (float) longest;
        Bitmap scaled = Bitmap.createScaledBitmap(bitmap, Math.max(1, Math.round(bitmap.getWidth() * ratio)), Math.max(1, Math.round(bitmap.getHeight() * ratio)), true);
        if (scaled != bitmap) bitmap.recycle(); return scaled;
    }
    private static void recycleUndisplayed(List<Bitmap> images) {
        for (Bitmap bitmap : images) if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
    }
}
