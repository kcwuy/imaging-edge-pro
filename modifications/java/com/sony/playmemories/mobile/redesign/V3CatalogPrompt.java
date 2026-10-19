package com.sony.playmemories.mobile.redesign;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.util.Log;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;
import java.io.File;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.WeakHashMap;

/** Keeps usable downloaded model resources available without interrupting every resume. */
public final class V3CatalogPrompt {
    private static final String PREFIX = "com.sony.playmemories.mobile.";
    private static final String CONTROLLER = PREFIX + "home.controller.CameraGuideImageController";
    private static final String TAG = "V3CatalogPrompt";
    private static final WeakHashMap<Activity, WeakReference<Object>> CONTROLLERS =
            new WeakHashMap<Activity, WeakReference<Object>>();
    private static final ThreadLocal<Object> MANUAL = new ThreadLocal<Object>();

    private V3CatalogPrompt() { }

    /** Native calls this only after its own date, pending-size and internet eligibility checks. */
    public static boolean allowAutomaticPrompt(Object controller, int pendingBytes) {
        try {
            Object raw = field(controller, "mActivity");
            if (!(raw instanceof Activity)) return true;
            Activity activity = (Activity) raw;
            CONTROLLERS.put(activity, new WeakReference<Object>(controller));
            if (samePromptVisible(activity, controller)) return false;
            // Explicit Settings requests pass through once; no download/version/date is fabricated.
            if (MANUAL.get() == controller) return true;
            return !usableCache(activity, controller);
        } catch (Exception unavailable) {
            Log.w(TAG, "Model resource readiness unavailable; preserving native prompt", unavailable);
            return true;
        } catch (LinkageError unavailable) { return true; }
    }

    /** Invokes the existing native update entry only on the Activity that owns that controller. */
    public static boolean requestUpdate(Activity activity) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return false;
        try {
            Object controller = controller(activity);
            if (controller == null) {
                toast(activity, "请回到相机主页的设置中更新型号资料。");
                return false;
            }
            if (samePromptVisible(activity, controller)) return true;
            if (!activity.hasWindowFocus()) {
                toast(activity, "请回到相机主页后再检查型号资料。");
                return false;
            }
            Object downloader = field(load("guideimage.GuideImageClient"), "guideImageDownloader");
            if (Boolean.TRUE.equals(field(downloader, "isDownloading"))) {
                toast(activity, "型号资料正在下载，请稍候。");
                return true;
            }
            Object before = dialog(controller);
            MANUAL.set(controller);
            try { controller.getClass().getMethod("onResume").invoke(controller); }
            finally { MANUAL.remove(); }
            Object after = dialog(controller);
            if (after instanceof Dialog && ((Dialog) after).isShowing() && after != before) return true;
            if (!Boolean.TRUE.equals(field(load("wificonnection.NetworkUtil"), "mIsInternetConnected"))) {
                toast(activity, "请先连接互联网后再更新型号资料。");
            } else if (readInt(activity, "GuideImageTotalZipSize") > 0) {
                toast(activity, "型号资料更新已暂缓，请稍后再试。");
            } else {
                toast(activity, "暂未发现待下载的型号资料。");
            }
            return false;
        } catch (Exception unavailable) {
            MANUAL.remove();
            Log.w(TAG, "Native model update entry unavailable", unavailable);
            toast(activity, "暂时无法检查型号资料，请稍后重试。");
            return false;
        } catch (LinkageError unavailable) {
            MANUAL.remove(); toast(activity, "当前页面暂不支持检查型号资料。"); return false;
        }
    }

    /** A cache/pending indication, never a claim that a fresh internet check succeeded. */
    public static String status(Activity activity) {
        if (activity == null) return "连接相机后可检查";
        try {
            if (readInt(activity, "GuideImageTotalZipSize") > 0) return "有可下载的型号资料";
            Object owner = controller(activity);
            return owner != null && usableCache(activity, owner)
                    ? "现有型号资料可用" : "连接相机后可检查";
        } catch (Exception unavailable) { return "连接相机后可检查"; }
        catch (LinkageError unavailable) { return "连接相机后可检查"; }
    }

    private static Object controller(Activity activity) throws Exception {
        Object owner = field(activity, "controller");
        if (owner == null) owner = field(activity, "mController");
        Object found = find(owner, 0);
        if (found == null) {
            WeakReference<Object> saved = CONTROLLERS.get(activity);
            found = saved == null ? null : saved.get();
        }
        if (found == null || field(found, "mActivity") != activity) return null;
        CONTROLLERS.put(activity, new WeakReference<Object>(found));
        return found;
    }

    private static Object find(Object owner, int depth) throws Exception {
        if (owner == null || depth > 6) return null;
        if (CONTROLLER.equals(owner.getClass().getName())) return owner;
        Object children = field(owner, "mControllers");
        if (children instanceof Iterable<?>) for (Object child : (Iterable<?>) children) {
            Object found = find(child, depth + 1);
            if (found != null) return found;
        }
        return null;
    }

    private static Object dialog(Object controller) throws Exception {
        return field(field(controller, "dialogManager"), "mFilePath");
    }

    private static boolean samePromptVisible(Activity activity, Object controller) throws Exception {
        Object candidate = dialog(controller);
        if (!(candidate instanceof Dialog) || !((Dialog) candidate).isShowing()) return false;
        View message = ((Dialog) candidate).findViewById(android.R.id.message);
        if (!(message instanceof TextView)) return false;
        int resource = activity.getResources().getIdentifier(
                "STRID_dialog_resource_download_description", "string", activity.getPackageName());
        return resource != 0 && ((TextView) message).getText().toString().startsWith(activity.getString(resource));
    }

    private static boolean usableCache(Activity activity, Object owner) throws Exception {
        // These are the three installed version checks used by Sony's own cached-JSON fallback.
        for (String key : new String[] {"CameraImageVersion", "PairingGuideImageVersion", "WiFiGuideImageVersion"}) {
            if (readInt(activity, key) <= 0) return false;
        }
        String model = registeredModel();
        if (model.length() == 0) {
            Object camera = field(owner, "mCamera");
            Object info = field(field(field(camera, "mDdXml"), "mDidXml"), "mDeviceInfo");
            Object name = field(info, "mModelName");
            if (name instanceof String) model = ((String) name).trim();
        }
        if (model.length() == 0 || model.indexOf('/') >= 0 || model.indexOf('\\') >= 0) return false;
        Object guide = guideForModel(activity, model);
        if (guide == null) return false; // New or missing model information keeps the native first-run flow.
        Object warehouse = field(load("guideimage.GuideImageClient"), "guideImageWarehouse");
        Object cameraDir = field(warehouse, "cameraImageDir");
        Object pairingDir = field(warehouse, "pairingGuideImageDir");
        Object wifiDir = field(warehouse, "wifiGuideImageDir");
        if (!(cameraDir instanceof File) || !(pairingDir instanceof File) || !(wifiDir instanceof File)) return false;
        boolean camera = image(new File((File) cameraDir, model + ".jpg"))
                || image(new File((File) cameraDir, model + ".png"));
        int pairing = ((Number) call(guide, "realmGet$pairingGuidePattern")).intValue();
        int wifi = ((Number) call(guide, "realmGet$wifiGuidePattern")).intValue();
        return camera && imagesIn(new File((File) pairingDir, "P" + pairing))
                && imagesIn(new File((File) wifiDir, "P" + wifi));
    }

    private static String registeredModel() {
        try {
            Object target = call(load("cameralist.RegisteredCameraUtil"), "getTargetCamera");
            Object name = call(target, "realmGet$modelName");
            return name instanceof String ? ((String) name).trim() : "";
        } catch (Exception absent) { return ""; }
    }

    private static Object guideForModel(Activity activity, String model) throws Exception {
        Object db = call(load("database.ClientDb"), "getInstance", activity);
        Object config = field(db, "mConfig");
        Object realm = call(Class.forName("io.realm.Realm"), "getInstance", config);
        try {
            Object query = call(realm, "where", load("database.realm.CameraGuideImageObject"));
            call(query, "equalTo", "modelName", model);
            Object guide = call(query, "findFirst");
            // Detach while Realm is open; no managed object is accessed after closing it.
            return guide == null ? null : call(realm, "copyFromRealm", guide);
        } finally { call(realm, "close"); }
    }

    private static boolean image(File file) { return file.isFile() && file.canRead() && file.length() > 0; }
    private static boolean imagesIn(File directory) {
        File[] files = directory.listFiles();
        if (files == null) return false;
        for (File file : files) {
            String name = file.getName().toLowerCase(java.util.Locale.ROOT);
            if ((name.endsWith(".jpg") || name.endsWith(".png")) && image(file)) return true;
        }
        return false;
    }

    private static int readInt(Context context, String key) throws Exception {
        Object reader = call(load("utility.setting.SharedPreferenceReaderWriter"), "getInstance", context);
        return ((Number) call(reader, "getInt", 0, "defaultSharedPreference", key)).intValue();
    }
    private static Class<?> load(String suffix) throws ClassNotFoundException { return Class.forName(PREFIX + suffix); }
    private static Object field(Object target, String name) throws Exception {
        if (target == null) return null;
        Class<?> type = target instanceof Class<?> ? (Class<?>) target : target.getClass();
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                Field field = current.getDeclaredField(name); field.setAccessible(true);
                return field.get(target instanceof Class<?> ? null : target);
            } catch (NoSuchFieldException absent) { }
        }
        return null;
    }
    private static Object call(Object target, String name, Object... arguments) throws Exception {
        if (target == null) return null;
        Class<?> type = target instanceof Class<?> ? (Class<?>) target : target.getClass();
        for (Method method : type.getMethods()) {
            if (!name.equals(method.getName()) || method.getParameterTypes().length != arguments.length) continue;
            boolean compatible = true;
            Class<?>[] parameters = method.getParameterTypes();
            for (int index = 0; index < parameters.length; index++) {
                Class<?> parameter = parameters[index];
                if (parameter == int.class) parameter = Integer.class;
                if (arguments[index] != null && !parameter.isInstance(arguments[index])) compatible = false;
            }
            if (compatible) {
                method.setAccessible(true);
                return method.invoke(target instanceof Class<?> ? null : target, arguments);
            }
        }
        throw new NoSuchMethodException(type.getName() + "." + name);
    }
    private static void toast(Activity activity, String message) { Toast.makeText(activity, message, Toast.LENGTH_LONG).show(); }
}
