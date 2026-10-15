package com.sony.playmemories.mobile.redesign;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.AbsListView;
import android.widget.Button;
import android.widget.GridView;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Locale;
import java.util.WeakHashMap;

/** Photo-library chrome around Sony's real adapters, selections and transfer controllers. */
public final class V3Photos {
    private static final String TAG = "V3Photos";
    private static final int INK = 0xff1d1d1f;
    private static final int MUTED = 0xff86868b;
    private static final int PAPER = 0xfff7f7f5;
    private static final int BLUE = 0xff007aff;
    private static final WeakHashMap<Activity, WeakReference<Binding>> BINDINGS =
            new WeakHashMap<Activity, WeakReference<Binding>>();

    private V3Photos() { }

    /** Invoke after the Activity's original onResume has bound its real views. */
    public static void apply(Activity activity) {
        if (activity == null || activity.isDestroyed() || activity.isFinishing()
                || !supported(activity.getClass().getName())) return;
        Binding existing = binding(activity);
        if (existing != null && existing.header.getParent() == existing.root
                && existing.root.getParent() != null && find(activity, "toolbar") == existing.toolbar) {
            existing.start();
            return;
        }
        if (existing != null) existing.dispose();
        try {
            View toolbar = find(activity, "toolbar");
            if (toolbar == null || !(toolbar.getParent() instanceof LinearLayout)) return;
            LinearLayout root = (LinearLayout) toolbar.getParent();
            if (root.getOrientation() != LinearLayout.VERTICAL) return;
            Binding next = new Binding(activity, root, toolbar);
            root.addView(next.header, root.indexOfChild(toolbar) + 1,
                    new LinearLayout.LayoutParams(-1, -2));
            root.addView(next.navigation, new LinearLayout.LayoutParams(-1, -2));
            next.header.setTag(next);
            BINDINGS.put(activity, new WeakReference<Binding>(next));
            next.start();
        } catch (RuntimeException error) {
            Log.e(TAG, "Photo navigation could not be installed", error);
        }
    }

    public static void pause(Activity activity) {
        Binding current = binding(activity);
        if (current != null) current.stop();
    }

    public static void destroy(Activity activity) {
        Binding current = binding(activity);
        if (current != null) current.dispose();
        BINDINGS.remove(activity);
    }

    /** No fake queue or modal state: original selection / transfer Back handling owns this. */
    public static boolean onBack(Activity activity) { return false; }

    private static Binding binding(Activity activity) {
        WeakReference<Binding> reference = BINDINGS.get(activity);
        return reference == null ? null : reference.get();
    }

    private static boolean supported(String name) {
        return name.equals("com.sony.playmemories.mobile.contentviewer.grid.ContentViewerGridActivity")
                || name.equals("com.sony.playmemories.mobile.v7.contentviewer.grid.ContentViewerGridActivity")
                || name.equals("com.sony.playmemories.mobile.transfer.webapi.list.ListViewActivity")
                || name.equals("com.sony.playmemories.mobile.transfer.webapi.grid.GridViewActivity")
                || name.equals("com.sony.playmemories.mobile.transfer.dlna.list.CdsListViewActivity")
                || name.equals("com.sony.playmemories.mobile.transfer.dlna.grid.CdsGridViewActivity")
                || name.equals("com.sony.playmemories.mobile.transfer.mtp.list.MtpListViewActivity")
                || name.equals("com.sony.playmemories.mobile.transfer.mtp.grid.MtpGridViewActivity");
    }

    private static View find(Activity activity, String name) {
        int id = activity.getResources().getIdentifier(name, "id", activity.getPackageName());
        return id == 0 ? null : activity.findViewById(id);
    }

    private static Object field(Object source, String name) {
        if (source == null) return null;
        for (Class<?> type = source.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(source);
            } catch (NoSuchFieldException missing) { }
            catch (Exception inaccessible) { return null; }
        }
        return null;
    }

    private static boolean selected(Object controller, int depth) {
        if (controller == null || depth > 4) return false;
        Object list = field(controller, "mSelectedContentList");
        if (!(list instanceof Collection)) list = field(controller, "selectedContentList");
        if (list instanceof Collection && !((Collection<?>) list).isEmpty()) return true;
        Object mode = field(controller, "mActionMode");
        if (mode != null && mode.getClass().getName().contains("androidx.appcompat.view")) return true;
        if (mode != controller && selected(mode, depth + 1)) return true;
        Object children = field(controller, "mControllers");
        if (children instanceof Iterable) {
            for (Object child : (Iterable<?>) children) if (selected(child, depth + 1)) return true;
        }
        return false;
    }

    private static final class Binding implements ViewTreeObserver.OnGlobalLayoutListener,
            ViewTreeObserver.OnPreDrawListener {
        final WeakReference<Activity> activity;
        final LinearLayout root, header, lead, segments, navigation;
        final View toolbar, illustration;
        final TextView title;
        final Button cameraTab, localTab;
        final boolean local;
        final WeakHashMap<View, Boolean> styledTiles = new WeakHashMap<View, Boolean>();
        GridView localGrid;
        View copyOriginal;
        Button copyButton;
        ViewGroup copyParent;
        float copyAlpha;
        int copyAccessibility;
        boolean observing, applying;
        boolean compact;

        Binding(final Activity activity, LinearLayout root, View toolbar) {
            this.activity = new WeakReference<Activity>(activity);
            this.root = root;
            this.toolbar = toolbar;
            local = activity.getClass().getName().contains(".contentviewer.");
            header = new LinearLayout(activity);
            header.setOrientation(LinearLayout.VERTICAL);
            header.setBackgroundColor(PAPER);
            lead = new LinearLayout(activity);
            lead.setOrientation(LinearLayout.HORIZONTAL);
            lead.setGravity(Gravity.CENTER_VERTICAL);
            title = label(activity, words(activity, "照片", "Photos"), 32, INK);
            title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            lead.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
            illustration = cameraIllustration(activity);
            if (illustration != null) {
                illustration.setClickable(false); illustration.setFocusable(false);
                illustration.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                lead.addView(illustration, new LinearLayout.LayoutParams(dp(96), dp(80)));
            }
            header.addView(lead, new LinearLayout.LayoutParams(-1, dp(80)));
            segments = new LinearLayout(activity);
            segments.setOrientation(LinearLayout.HORIZONTAL);
            segments.setPadding(dp(3), dp(3), dp(3), dp(3));
            segments.setBackground(round(0xffe9e9e7, dp(14)));
            cameraTab = tab(activity, words(activity, "相机中", "On camera"), !local);
            localTab = tab(activity, words(activity, "已导入", "Imported"), local);
            segments.addView(cameraTab, new LinearLayout.LayoutParams(0, -2, 1));
            segments.addView(localTab, new LinearLayout.LayoutParams(0, -2, 1));
            header.addView(segments, new LinearLayout.LayoutParams(-1, -2));
            cameraTab.setOnClickListener(new View.OnClickListener() {
                public void onClick(View view) { if (local) switchSource(true); }
            });
            localTab.setOnClickListener(new View.OnClickListener() {
                public void onClick(View view) { if (!local) switchSource(false); }
            });
            navigation = new LinearLayout(activity);
            navigation.setOrientation(LinearLayout.HORIZONTAL);
            navigation.setBackgroundColor(PAPER);
            navigation.setPadding(dp(12), dp(4), dp(12), dp(8));
            final String[] keys={"camera","photos","settings"};
            String[] captions={words(activity,"相机","Camera"),words(activity,"照片","Photos"),words(activity,"设置","Settings")};
            for(int i=0;i<keys.length;i++){
                final String key=keys[i];TextView item=label(activity,captions[i],12,i==1?INK:MUTED);
                item.setGravity(Gravity.CENTER);item.setMinHeight(dp(56));item.setSelected(i==1);
                item.setTypeface(Typeface.create(i==1?"sans-serif-medium":"sans-serif",Typeface.NORMAL));
                item.setContentDescription(captions[i]);
                if(i!=1)item.setOnClickListener(new View.OnClickListener(){public void onClick(View v){navigate(key);}});
                navigation.addView(item,new LinearLayout.LayoutParams(0,dp(56),1));
            }
            styleExisting(activity);
            // An inline text button uses the original discrete copy click, enabled and visibility.
            // It never changes the controller's selected files or starts an independent transfer.
            if (!local) installCopyButton(activity);
            updateSize();
        }

        void start() {
            if (!observing) {
                root.getViewTreeObserver().addOnGlobalLayoutListener(this);
                root.getViewTreeObserver().addOnPreDrawListener(this);
                observing = true;
            }
            syncCopyButton();
            refreshPresentation();
            updateSize();
        }

        void stop() {
            if (observing && root.getViewTreeObserver().isAlive()) {
                root.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                root.getViewTreeObserver().removeOnPreDrawListener(this);
            }
            observing = false;
        }

        void dispose() {
            stop();
            header.setTag(null);
            if (header.getParent() == root) root.removeView(header);
            if (navigation.getParent() == root) root.removeView(navigation);
            if (copyButton != null && copyParent != null) copyParent.removeView(copyButton);
            if (copyOriginal != null) {
                copyOriginal.setAlpha(copyAlpha);
                copyOriginal.setImportantForAccessibility(copyAccessibility);
            }
        }

        @Override public void onGlobalLayout() {
            if (applying) return;
            applying = true;
            try { updateSize(); syncCopyButton(); refreshPresentation(); }
            finally { applying = false; }
        }

        @Override public boolean onPreDraw() {
            // setEnabled() can invalidate without a layout pass; mirror it on the next draw.
            syncCopyButton();
            refreshPresentation();
            return true;
        }

        void refreshPresentation() {
            Activity host = activity.get();
            if (host == null) return;
            // AppCompat may re-apply its old title/background after onResume. Do not touch its
            // navigation icon, overflow menu, action mode, or any task/selection title.
            if (!(toolbar.getBackground() instanceof ColorDrawable)
                    || ((ColorDrawable) toolbar.getBackground()).getColor() != PAPER) {
                toolbar.setBackgroundColor(PAPER);
            }
            try {
                Object raw = toolbar.getClass().getMethod("getTitle").invoke(toolbar);
                String caption = raw == null ? "" : raw.toString().trim();
                if ("Imaging Edge Mobile".equals(caption)) {
                    Object controller = field(host, "mController");
                    if (controller == null) controller = field(host, "controller");
                    if (!selected(controller, 0)) {
                        toolbar.getClass().getMethod("setTitle", CharSequence.class).invoke(toolbar, "");
                    }
                }
            } catch (Exception unsupportedToolbar) { }
            if (!local) return;
            if (localGrid == null) {
                View grid = find(host, "content_viewer_grid");
                if (grid instanceof GridView) localGrid = (GridView) grid;
            }
            if (localGrid == null) return;
            // The adapter owns cell tags, all listeners, bitmaps and metadata. Only style newly
            // attached cells; do not add an OnScrollListener or change the column/row geometry.
            for (int index = 0; index < localGrid.getChildCount(); index++) {
                View tile = localGrid.getChildAt(index);
                if (styledTiles.put(tile, Boolean.TRUE) == null) styleLocalTile(host, tile);
            }
        }

        void styleLocalTile(Activity host, View tile) {
            tile.setBackgroundColor(PAPER);
            if (!host.getClass().getName().contains(".v7.")) {
                // Legacy WebAPI fixes each tile to its screen-derived mColumnWidth. Insets belong
                // inside that tile; changing its Grid's usable width could clip the fixed columns.
                tile.setPadding(dp(4), tile.getPaddingTop(), dp(4), tile.getPaddingBottom());
            }
            int thumbnailId = host.getResources().getIdentifier("thumbnail", "id", host.getPackageName());
            View thumbnail = thumbnailId == 0 ? null : tile.findViewById(thumbnailId);
            if (thumbnail instanceof ImageView) {
                // SquareImageView already measures width x width. CENTER_CROP only fills that
                // existing preview viewport; source files, rotations and detail view stay native.
                ((ImageView) thumbnail).setScaleType(ImageView.ScaleType.CENTER_CROP);
                thumbnail.setBackgroundColor(0xffeeeeeb);
                if (thumbnail.getParent() instanceof ViewGroup) {
                    ViewGroup imageArea = (ViewGroup) thumbnail.getParent();
                    imageArea.setBackgroundColor(PAPER);
                    // v7 includes an anonymous 4dp painted spacer beneath the square image.
                    for (int index = 0; index < imageArea.getChildCount(); index++) {
                        View spacer = imageArea.getChildAt(index);
                        if (spacer.getClass() == View.class && spacer.getId() == View.NO_ID) {
                            spacer.setBackgroundColor(PAPER);
                        }
                    }
                }
            }
            int fileNameId = host.getResources().getIdentifier("file_name", "id", host.getPackageName());
            // The older WebAPI local grid has duration text over the image, not a metadata footer.
            // Its overlay contrast/visibility remains entirely native.
            if (fileNameId == 0 || tile.findViewById(fileNameId) == null) return;
            for (String name : new String[] {"file_name", "content_type", "duration"}) {
                int id = host.getResources().getIdentifier(name, "id", host.getPackageName());
                View view = id == 0 ? null : tile.findViewById(id);
                if (!(view instanceof TextView)) continue;
                TextView metadata = (TextView) view;
                metadata.setTextColor(MUTED);
                metadata.setTextSize(name.equals("file_name") ? 10 : 9);
                metadata.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
                metadata.setIncludeFontPadding(false);
                // Retain the footer's original 44dp height, anchors, visibility and hit area.
                if (name.equals("file_name") && metadata.getParent() instanceof View) {
                    View footer = (View) metadata.getParent(); footer.setBackgroundColor(PAPER);
                    footer.setPadding(dp(4), dp(3), dp(4), dp(2));
                }
            }
        }

        void updateSize() {
            Activity host = activity.get();
            if (host == null) return;
            boolean next = host.getResources().getConfiguration().orientation
                    == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
            // Keep the source selector and original toolbar controls in short landscape windows.
            if (header.getPaddingLeft() != 0 && next == compact) return;
            compact = next;
            header.setPadding(dp(24), dp(compact ? 4 : 8), dp(24), dp(compact ? 12 : 24));
            header.setOrientation(compact ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
            header.setGravity(Gravity.CENTER_VERTICAL);
            lead.setMinimumHeight(compact ? 0 : dp(80));
            if (illustration != null) illustration.setVisibility(compact ? View.GONE : View.VISIBLE);
            title.setTextSize(compact ? 24 : 32);
            title.setLayoutParams(new LinearLayout.LayoutParams(compact ? -2 : 0, -2, compact ? 0 : 1));
            LinearLayout.LayoutParams leadParams = new LinearLayout.LayoutParams(compact ? -2 : -1, compact ? -2 : dp(80));
            if (compact) leadParams.rightMargin = dp(22);
            else leadParams.bottomMargin = dp(18);
            lead.setLayoutParams(leadParams);
            segments.setLayoutParams(new LinearLayout.LayoutParams(compact ? 0 : -1, -2,
                    compact ? 1 : 0));
        }

        void styleExisting(Activity host) {
            root.setBackgroundColor(PAPER);
            toolbar.setBackgroundColor(PAPER);
            for (String id : new String[] {"content_viewer_grid_layout", "header_layout",
                    "datelist_divider", "bottom_button"}) {
                View view = find(host, id);
                if (view != null) view.setBackgroundColor(PAPER);
            }
            // Preserve all adapter metrics / column calculations and original parent relations.
            for (String id : new String[] {"content_viewer_grid", "thumbnails", "datelistview"}) {
                View view = find(host, id);
                if (!(view instanceof AbsListView)) continue;
                view.setBackgroundColor(PAPER);
                ((AbsListView) view).setClipToPadding(false);
                if (view instanceof GridView) {
                    GridView grid = (GridView) view;
                    boolean flexibleLocal = local && host.getClass().getName().contains(".v7.");
                    grid.setHorizontalSpacing(dp(flexibleLocal ? 10 : 4));
                    grid.setVerticalSpacing(dp(flexibleLocal ? 12 : 8));
                    if (flexibleLocal) grid.setPadding(dp(12), dp(10), dp(12), dp(16));
                    // Native controllers compute number / width of columns; do not override them.
                } else if (view instanceof ListView) {
                    view.setPadding(dp(16), dp(8), dp(16), dp(12));
                    ((ListView) view).setDivider(new ColorDrawable(PAPER));
                    ((ListView) view).setDividerHeight(dp(8));
                }
            }
            for (String id : new String[] {"date", "devicename", "item_count", "no_imgae_text"}) {
                View view = find(host, id);
                if (view instanceof TextView) {
                    ((TextView) view).setTextColor(id.equals("item_count") ? MUTED : INK);
                    if (id.equals("item_count")) ((TextView) view).setTextSize(12);
                    ((TextView) view).setFontFeatureSettings("kern");
                }
            }
            View emptyText = find(host, "no_imgae_text");
            if (emptyText instanceof TextView) {
                ((TextView) emptyText).setText(words(host,
                        local ? "还没有导入的照片" : "没有可显示的照片",
                        local ? "No imported photos yet" : "No photos to display"));
                ((TextView) emptyText).setGravity(Gravity.CENTER);
            }
            // Keep native navigation/selection menu, including disabled actions and permission UI.
            for (String id : new String[] {"btn_return_to_camera", "btn_bt_power_on_off", "filter"}) {
                View action = find(host, id);
                if (action != null) action.setMinimumHeight(dp(48));
            }
        }

        void installCopyButton(Activity host) {
            View original = find(host, "btn_content_copy");
            if (!(original instanceof ImageButton) || !(original.getParent() instanceof RelativeLayout)) return;
            final View source = original;
            copyOriginal = source;
            copyParent = (ViewGroup) original.getParent();
            copyAlpha = original.getAlpha();
            copyAccessibility = original.getImportantForAccessibility();
            copyButton = new Button(host);
            copyButton.setText(words(host, "导入照片", "Import"));
            copyButton.setTextSize(15);
            copyButton.setAllCaps(false);
            copyButton.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            copyButton.setTextColor(Color.WHITE);
            copyButton.setBackgroundTintList(null);
            copyButton.setBackground(round(BLUE, dp(15)));
            copyButton.setPadding(dp(18), 0, dp(18), 0);
            copyButton.setMinHeight(dp(48));
            copyButton.setMinimumHeight(dp(48));
            copyButton.setStateListAnimator(null);
            copyButton.setContentDescription(words(host, "导入所选照片", "Import selected photos"));
            copyButton.setOnClickListener(new View.OnClickListener() {
                public void onClick(View view) {
                    if (source.getVisibility() == View.VISIBLE && source.isEnabled()
                            && source.hasOnClickListeners()) source.performClick();
                }
            });
            RelativeLayout.LayoutParams params = new RelativeLayout.LayoutParams(-2, dp(48));
            params.addRule(RelativeLayout.CENTER_IN_PARENT);
            copyParent.addView(copyButton, params);
            ViewGroup.LayoutParams barParams = copyParent.getLayoutParams();
            if (barParams != null && barParams.height > 0 && barParams.height < dp(64)) {
                barParams.height = dp(64);
                copyParent.setLayoutParams(barParams);
            }
            source.setAlpha(0f);
            source.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            syncCopyButton();
        }

        void syncCopyButton() {
            if (copyButton == null || copyOriginal == null) return;
            int visibility = copyOriginal.getVisibility();
            if (copyButton.getVisibility() != visibility) copyButton.setVisibility(visibility);
            boolean enabled = copyOriginal.isEnabled() && copyOriginal.hasOnClickListeners();
            if (copyButton.isEnabled() != enabled) copyButton.setEnabled(enabled);
            float alpha = enabled ? 1f : 0.38f;
            if (copyButton.getAlpha() != alpha) copyButton.setAlpha(alpha);
        }

        void switchSource(boolean toCamera) {
            Activity host = activity.get();
            if (host == null || host.isFinishing() || host.isDestroyed()) return;
            View processing = find(host, "processing_screen");
            if (processing != null && processing.isShown()) {
                Toast.makeText(host, words(host, "正在读取照片，请稍候", "Loading photos. Please wait."), Toast.LENGTH_SHORT).show();
                return;
            }
            Object controller = field(host, "mController");
            if (controller == null) controller = field(host, "controller");
            if (selected(controller, 0)) {
                Toast.makeText(host, words(host, "请先完成或取消当前选择", "Finish or cancel the current selection first."), Toast.LENGTH_SHORT).show();
                return;
            }
            try {
                if (toCamera) {
                    // The shell owns pending import intent and the existing Sony connection flow.
                    Method route = Class.forName("com.sony.playmemories.mobile.redesign.V3Shell")
                            .getMethod("openCameraPhotos", Activity.class);
                    Object handled = route.invoke(null, host);
                    if (handled instanceof Boolean && ((Boolean) handled).booleanValue()) { afterNavigation(host); return; }
                    Toast.makeText(host, words(host, "请从相机页选择导入照片", "Choose Import photos on the Camera page."), Toast.LENGTH_SHORT).show();
                } else {
                    V3TabMotion.prepare(host,1,"photos");
                    Intent intent = new Intent();
                    intent.setClassName(host, "com.sony.playmemories.mobile.v7.contentviewer.grid.ContentViewerGridActivity");
                    host.startActivity(intent);
                    afterNavigation(host);
                }
            } catch (Exception error) {
                Log.w(TAG, "Photo source route unavailable", error);
                Toast.makeText(host, words(host, "暂时无法打开，请返回重试", "Unable to open. Go back and try again."), Toast.LENGTH_SHORT).show();
            }
        }

        void navigate(String key){
            Activity host=activity.get();if(host==null)return;
            View processing=find(host,"processing_screen");
            Object controller=field(host,"mController");if(controller==null)controller=field(host,"controller");
            if((processing!=null&&processing.isShown())||selected(controller,0)){
                Toast.makeText(host,words(host,"请先完成或取消当前操作","Finish or cancel the current operation first."),Toast.LENGTH_SHORT).show();return;
            }
            try{if(V3Shell.openTab(host,key))afterNavigation(host);}catch(RuntimeException error){Log.w(TAG,"Tab navigation unavailable",error);}
        }

        Button tab(Activity host, String text, boolean selected) {
            Button button = new Button(host);
            button.setText(text);
            button.setTextSize(14);
            button.setAllCaps(false);
            button.setMinWidth(0);
            button.setMinimumWidth(0);
            button.setMinHeight(dp(48));
            button.setMinimumHeight(dp(48));
            button.setPadding(dp(8), dp(7), dp(8), dp(7));
            button.setTextColor(selected ? Color.WHITE : INK);
            button.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            button.setBackgroundTintList(null);
            button.setBackground(round(selected ? INK : Color.TRANSPARENT, dp(11)));
            button.setSelected(selected);
            button.setStateListAnimator(null);
            button.setContentDescription(text + (selected ? words(host, "，当前来源", ", current source") : ""));
            return button;
        }

        TextView label(Activity host, String text, int size, int color) {
            TextView view = new TextView(host);
            view.setText(text);
            view.setTextSize(size);
            view.setTextColor(color);
            view.setIncludeFontPadding(false);
            return view;
        }

        GradientDrawable round(int color, int radius) {
            GradientDrawable shape = new GradientDrawable();
            shape.setColor(color);
            shape.setCornerRadius(radius);
            return shape;
        }

        int dp(int value) { return Math.round(value * root.getResources().getDisplayMetrics().density); }
    }

    private static View cameraIllustration(Context context) {
        try {
            Object view = Class.forName("com.sony.playmemories.mobile.redesign.V3Shell")
                    .getMethod("cameraIllustration", Context.class).invoke(null, context);
            return view instanceof View ? (View) view : null;
        } catch (Exception unavailable) {
            Log.w(TAG, "Shared abstract camera illustration unavailable", unavailable);
            return null;
        }
    }

    /** Source/tab transitions use directional paging. Photo-detail transitions remain Sony-owned. */
    private static void afterNavigation(Activity activity) {
        try {
            Class.forName("com.sony.playmemories.mobile.redesign.V3TabMotion")
                    .getMethod("afterNavigation", Activity.class).invoke(null, activity);
        } catch (Exception unavailable) { Log.w(TAG, "Shared tab motion unavailable", unavailable); }
    }

    private static String words(Activity host, String chinese, String english) {
        Locale locale = Build.VERSION.SDK_INT >= 24
                ? host.getResources().getConfiguration().getLocales().get(0)
                : host.getResources().getConfiguration().locale;
        return "zh".equals(locale.getLanguage()) ? chinese : english;
    }
}
