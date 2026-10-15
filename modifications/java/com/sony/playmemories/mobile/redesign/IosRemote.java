package com.sony.playmemories.mobile.redesign;

import android.app.Activity;
import android.graphics.Color;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.TextView;
import java.lang.reflect.InvocationTargetException;

/** Binds the added navigation/metadata only. Original camera views/listeners remain untouched. */
public final class IosRemote {
    private static final String TAG = "IosRemote";
    private IosRemote() { }

    /** Safe after original inflation/controller binding, and again after configuration reinflation. */
    public static void apply(final Activity activity) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        String name = activity.getClass().getName();
        if (!name.endsWith("remotecontrol.RemoteControlActivity")) return;
        View header = view(activity, "ios_remote_header");
        if (header == null) return;
        View back = view(activity, "ios_remote_back");
        if (back != null) {
            back.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View ignored) {
                    // Use the same original AndroidX callback chain as system predictive Back.
                    // It preserves Sony menu/shooting exit guards. Never force finish().
                    returnToCamera(activity);
                }
            });
        }
        Object camera = currentCamera(activity);
        String friendlyName = friendlyName(camera);
        TextView title = text(activity, "ios_remote_title");
        if (title != null && !TextUtils.isEmpty(friendlyName)) title.setText(friendlyName);
        boolean connected = connected(activity);
        TextView status = text(activity, "ios_remote_subtitle");
        if (status != null) status.setText(connected ? "遥控拍摄 · 相机已连接" : "遥控拍摄 · 等待相机");
        TextView indicator = text(activity, "ios_remote_connection");
        if (indicator != null) {
            indicator.setTextColor(Color.parseColor(connected ? "#30D158" : "#8E8E93"));
            indicator.setContentDescription(connected ? "相机已连接" : "等待相机连接");
        }
        describe(activity, "act_button", "拍摄或开始、停止录像");
        describe(activity, "settings_button", "相机拍摄设置");
        describe(activity, "remote_control_activity_switch_screen_button", "切换取景与参数");
        describe(activity, "remote_control_activity_clockwise_rotate_button", "旋转取景画面");
        describe(activity, "setting_table_shutter_speed", "快门速度");
        describe(activity, "setting_table_f_number", "光圈");
        describe(activity, "setting_table_ev", "曝光补偿");
        describe(activity, "setting_table_iso", "感光度 ISO");
        describe(activity, "setting_table_shooting_mode", "拍摄模式");
        describe(activity, "zoom_w", "缩小焦距");
        describe(activity, "zoom_t", "增大焦距");
    }

    private static void returnToCamera(Activity activity) {
        try {
            Object dispatcher = activity.getClass().getMethod("getOnBackPressedDispatcher").invoke(activity);
            if (dispatcher == null) { Log.w(TAG, "Original Back dispatcher unavailable"); return; }
            dispatcher.getClass().getMethod("onBackPressed").invoke(dispatcher);
        } catch (InvocationTargetException originalCallbackFailure) {
            // The original callback may already have acted; repeating it could exit twice.
            Log.e(TAG, "Original Back callback failed", originalCallbackFailure.getCause());
        } catch (NoSuchMethodException lookupFailure) {
            originalControllerBack(activity);
        } catch (IllegalAccessException accessFailure) {
            originalControllerBack(activity);
        } catch (RuntimeException failure) {
            Log.e(TAG, "Original Back dispatcher could not be invoked", failure);
        }
    }

    private static void originalControllerBack(Activity activity) {
        try {
            Object controller = activity.getClass().getField("mController").get(activity);
            if (controller != null) controller.getClass().getMethod("onBackPressed").invoke(controller);
        } catch (InvocationTargetException originalCallbackFailure) {
            Log.e(TAG, "Original controller Back callback failed", originalCallbackFailure.getCause());
        } catch (Exception failure) {
            Log.e(TAG, "Original controller Back callback unavailable", failure);
        }
    }

    private static Object currentCamera(Activity activity) {
        try {
            Object controller = activity.getClass().getField("mController").get(activity);
            if (controller != null) return controller.getClass().getField("mCamera").get(controller);
        } catch (Exception error) { Log.d(TAG, "Camera header metadata unavailable", error); }
        return null;
    }

    private static String friendlyName(Object camera) {
        if (camera == null) return null;
        try {
            Object description = camera.getClass().getField("mDdXml").get(camera);
            if (description == null) return null;
            Object value = description.getClass().getField("mFriendlyName").get(description);
            return value instanceof String ? (String) value : null;
        } catch (Exception error) { return null; }
    }

    private static boolean connected(Activity activity) {
        try {
            Class<?> keeper = Class.forName("com.sony.playmemories.mobile.redesign.ConnectionKeeper");
            Object value = keeper.getMethod("hasCameraConnection", android.content.Context.class)
                    .invoke(null, activity);
            return Boolean.TRUE.equals(value);
        } catch (Exception error) { return false; }
    }

    private static View view(Activity activity, String name) {
        int id = activity.getResources().getIdentifier(name, "id", activity.getPackageName());
        return id == 0 ? null : activity.findViewById(id);
    }
    private static TextView text(Activity activity, String name) {
        View view = view(activity, name);
        return view instanceof TextView ? (TextView) view : null;
    }
    private static void describe(Activity activity, String name, String value) {
        View view = view(activity, name);
        if (view != null) view.setContentDescription(value);
    }
}
