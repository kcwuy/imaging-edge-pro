package com.sony.playmemories.mobile.redesign;

import android.app.Activity;
import java.lang.ref.WeakReference;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.WeakHashMap;

/** Camera-model bridge for inline controls. No Sony dialog or screen-mode callback is invoked. */
public final class V3CameraParameters {
    private static final String WEB = "com.sony.playmemories.mobile.webapi.camera.property.";
    private static final String PTP = "com.sony.playmemories.mobile.ptpip.";
    private static final String CODEC = "com.google.android.gms.internal.measurement.zzkj";
    private static final WeakHashMap<Activity, State> STATES = new WeakHashMap<Activity, State>();
    private static final long WRITE_TIMEOUT_MS = 8000L;
    private V3CameraParameters() { }

    public static final class Snapshot {
        public final List<String> values;
        public final String current;
        public final boolean writable;
        public final String reason;
        public final String error;
        public Snapshot(List<String> values, String current, boolean writable, String reason) {
            this(values, current, writable, reason, "");
        }
        public Snapshot(List<String> values, String current, boolean writable, String reason, String error) {
            this.values = Collections.unmodifiableList(new ArrayList<String>(values));
            this.current = current == null ? "" : current;
            this.writable = writable;
            this.reason = reason == null ? "" : reason;
            this.error = error == null ? "" : error;
        }
    }

    private static final class State {
        final WeakReference<Object> controller;
        final HashMap<String, Fetched> fetched = new HashMap<String, Fetched>();
        Attempt attempt;
        State(Object controller) { this.controller = new WeakReference<Object>(controller); }
    }

    private static final class Fetched {
        Object value, candidates;
        long requestedAt, receivedAt;
        boolean pending;
    }

    private static final class Attempt {
        final String key;
        final long startedAt = now();
        volatile boolean pending = true;
        volatile String reason = "正在应用，等待相机确认";
        Attempt(String key) { this.key = key; }
    }

    private static final class NativeValues {
        final LinkedHashMap<String, Object> candidates = new LinkedHashMap<String, Object>();
        Object property, code, dataset, client;
        String current = "";
        boolean writable;
        String reason = "";
    }

    public static Snapshot read(Activity activity, String key) {
        try {
            Object controller = controller(activity);
            if (controller == null) return unavailable("等待相机连接");
            State state = state(activity, controller);
            NativeValues model = readNative(activity, controller, state, key);
            String reason = model.reason;
            String error = "";
            boolean writable = model.writable;
            synchronized (state) {
                Attempt attempt = state.attempt;
                if (attempt != null && attempt.pending && now() - attempt.startedAt > WRITE_TIMEOUT_MS) {
                    attempt.pending = false;
                    attempt.reason = "相机未确认，请重试";
                }
                if (attempt != null && attempt.pending) {
                    writable = false;
                    reason = attempt.reason;
                } else if (attempt != null && key.equals(attempt.key) && attempt.reason.length() != 0
                        && now() - attempt.startedAt < 15000L) {
                    reason = attempt.reason;
                    error = attempt.reason;
                }
            }
            return new Snapshot(new ArrayList<String>(model.candidates.keySet()), model.current, writable, reason, error);
        } catch (Exception unsupported) {
            return unavailable("此相机暂未提供可用参数");
        } catch (LinkageError unsupported) {
            return unavailable("此相机暂未提供可用参数");
        }
    }

    /** True means submitted, not confirmed. Current readouts always come from the native camera model. */
    public static boolean set(Activity activity, String key, String raw) {
        if (raw == null) return false;
        State state = null;
        Attempt attempt = null;
        try {
            Object controller = controller(activity);
            if (controller == null) return false;
            state = state(activity, controller);
            synchronized (state) {
                if (state.attempt != null && state.attempt.pending
                        && now() - state.attempt.startedAt <= WRITE_TIMEOUT_MS) return false;
            }
            NativeValues model = readNative(activity, controller, state, key);
            if (!model.writable || !model.candidates.containsKey(raw)) return false;
            if (raw.equals(model.current)) return true;
            attempt = new Attempt(key);
            synchronized (state) { state.attempt = attempt; }
            if (model.property != null) {
                Object callback = callback(load(activity, WEB + "IPropertyKeyCallback"), state, attempt, null);
                call(model.property, "setValue", callback, model.candidates.get(raw));
            } else {
                Class<?> codec = load(activity, CODEC);
                Object dataType = field(model.dataset, "mDataType");
                long value = ((Number) model.candidates.get(raw)).longValue();
                byte[] bytes = (byte[]) callStatic(codec, "getBytes", dataType, Long.valueOf(value));
                Object callback = callback(load(activity, PTP + "property.DevicePropertySetter$IDevicePropertySetterCallback"), state, attempt, null);
                call(model.client, "setDeviceProperty", model.code, bytes, callback);
            }
            return true;
        } catch (Exception failed) {
            fail(state, attempt, "参数未能应用，请重试");
            return false;
        } catch (LinkageError failed) {
            fail(state, attempt, "此相机暂不支持修改此参数");
            return false;
        }
    }

    /** Presentation aliases only; commands must use the original strings in Snapshot.values. */
    public static String display(String key, String raw) {
        if (raw == null || raw.length() == 0) return "—";
        if ("mode".equals(key)) return modeLabel(raw, false);
        if ("focus".equals(key)) return focusLabel(raw, false);
        if ("iso".equals(key)) return isoLabel(raw, false);
        String normalized = normalize(raw);
        if (isAutoValue(key, raw)) return "自动";
        if ("wb".equals(key)) {
            String nativeLabel = raw.toLowerCase(Locale.US).trim();
            if (nativeLabel.equals("fluor::daylight(+2)")) return "荧光灯+2";
            if (nativeLabel.equals("fluor::day white(+1)")) return "荧光灯+1";
            if (nativeLabel.equals("fluor::cool white(0)")) return "荧光灯0";
            if (nativeLabel.equals("fluor::warm white(-1)")) return "荧光灯−1";
            if (nativeLabel.equals("flush")) return "闪光灯";
            if (nativeLabel.equals("tungsten(incandescent)")) return "白炽灯";
            if (nativeLabel.equals("c.temp.")) return "色温";
            if (nativeLabel.equals("underwater auto")) return "水下自动";
            if (nativeLabel.equals("one-push automatic")) return "一键白平衡";
            if (normalized.equals("daylight")) return "日光";
            if (normalized.equals("shade")) return "阴影";
            if (normalized.equals("cloudy")) return "阴天";
            if (normalized.equals("incandescent") || normalized.equals("tungsten")) return "白炽灯";
            if (normalized.equals("flash")) return "闪光灯";
            if (normalized.contains("fluorescent")) return "荧光灯";
            if (normalized.equals("color temperature") || normalized.equals("color temp")) return "色温";
            if (normalized.startsWith("custom")) return "自定义" + raw.replaceAll("[^0-9]", "");
        }
        if ("aperture".equals(key) && !normalized.startsWith("f")) return "F" + raw;
        return raw;
    }

    /** Full labels for the inline editor. Display aliases are never passed to set(). */
    public static String displayExpanded(String key, String raw) {
        if (raw == null || raw.length() == 0) return "—";
        if ("focus".equals(key)) return focusLabel(raw, true);
        if ("mode".equals(key)) return modeLabel(raw, true);
        if ("iso".equals(key)) return isoLabel(raw, true);
        if ("wb".equals(key)) {
            if (isAutoValue(key, raw)) return "自动白平衡";
            String value = raw.toLowerCase(Locale.US).trim();
            if (value.equals("fluor::daylight(+2)")) return "荧光灯·日光 +2";
            if (value.equals("fluor::day white(+1)")) return "荧光灯·日光白 +1";
            if (value.equals("fluor::cool white(0)")) return "荧光灯·冷白 0";
            if (value.equals("fluor::warm white(-1)")) return "荧光灯·暖白 −1";
        }
        return display(key, raw);
    }

    /** Recognizes only a plain automatic setting, not the distinct multi-frame NR ISO modes. */
    public static boolean isAutoValue(String key, String raw) {
        if (raw == null) return false;
        String token = normalize(raw).replace(" ", "");
        if ("iso".equals(key)) return token.equals("auto") || token.equals("automatic")
                || token.equals("isoauto") || token.equals("autoiso");
        if ("wb".equals(key)) return token.equals("auto") || token.equals("automatic") || token.equals("awb");
        return token.equals("auto") || token.equals("automatic");
    }

    private static String normalize(String raw) {
        return raw.toLowerCase(Locale.US).replace('_', ' ').replace('-', ' ').trim().replaceAll("\\s+", " ");
    }

    private static String focusLabel(String raw, boolean expanded) {
        String token = raw.toUpperCase(Locale.US).replace('_', '-').replaceAll("\\s+", "");
        String normalized = normalize(raw);
        if (token.equals("DMF") || token.contains("(DMF)") || normalized.equals("direct manual focus"))
            return expanded ? "直接手动 DMF" : "DMF";
        if (token.equals("MF-R") || token.contains("(MF-R)") || normalized.equals("manual focus reverse"))
            return expanded ? "反向手动 MF-R" : "反向";
        if (token.equals("MF") || token.contains("(MF)") || normalized.equals("manual") || normalized.equals("manual focus"))
            return expanded ? "手动对焦 MF" : "手动";
        if (token.equals("AF-S") || token.contains("(AF-S)") || normalized.equals("single af")
                || normalized.equals("single shot af")) return expanded ? "单次对焦 AF-S" : "单次";
        if (token.equals("AF-C") || token.contains("(AF-C)") || normalized.equals("continuous af"))
            return expanded ? "连续对焦 AF-C" : "连续";
        if (token.equals("AF-A") || token.contains("(AF-A)") || normalized.equals("automatic af"))
            return expanded ? "自动切换 AF-A" : "自动";
        if (token.equals("AF-D") || token.contains("(AF-D)")) return expanded ? "深度对焦 AF-D" : "深度";
        if (token.equals("PF") || token.contains("(PF)") || normalized.equals("preset focus"))
            return expanded ? "预设对焦 PF" : "预设";
        if (normalized.startsWith("automatic macro")) return expanded ? "自动微距对焦" : "微距";
        if (token.equals("AF") || normalized.equals("auto focus") || normalized.equals("auto") || normalized.equals("automatic"))
            return expanded ? "自动对焦 AF" : "自动";
        return raw;
    }

    private static String isoLabel(String raw, boolean expanded) {
        if (isAutoValue("iso", raw)) return "自动";
        String value = raw.trim();
        String upper = value.toUpperCase(Locale.US);
        if (upper.matches("ISO\\s*[0-9]+")) return value.replaceFirst("(?i)^ISO\\s*", "");
        String token = normalize(raw).replace(" ", "");
        int iso = token.lastIndexOf("iso");
        if (iso >= 0 && token.contains("multiframe") && token.contains("nr")) {
            String number = token.substring(iso + 3);
            if (number.matches("[0-9]+") || number.equals("auto")) {
                if (number.equals("auto")) number = "自动";
                boolean high = token.contains("high"), extended = token.startsWith("extended") || token.startsWith("ext");
                String prefix = expanded ? (extended ? "扩展多帧降噪" : "多帧降噪") : (extended ? "扩展降噪" : "降噪");
                return prefix + (high ? "高 " : " ") + number;
            }
        }
        return raw;
    }

    private static String modeLabel(String raw, boolean expanded) {
        String normalized = normalize(raw), token = normalized.replace(" ", "");
        if (token.equals("m") || token.equals("manual") || token.equals("manual(m)")) return expanded ? "手动曝光 M" : "M";
        if (token.equals("p") || token.equals("programauto") || token.equals("automatic(p)")) return expanded ? "程序自动 P" : "P";
        if (token.equals("a") || token.equals("aperture") || token.equals("aperturepriority") || token.equals("aperturepriority(a)"))
            return expanded ? "光圈优先 A" : "A";
        if (token.equals("s") || token.equals("shutter") || token.equals("shutterpriority") || token.equals("shutterpriority(s)"))
            return expanded ? "快门优先 S" : "S";
        if (token.equals("auto") || token.equals("intelligentauto")) return expanded ? "智能自动" : "自动";
        if (token.equals("auto+") || token.equals("superiorauto")) return expanded ? "增强自动" : "自动+";
        if (token.equals("automatic")) return expanded ? "程序自动 P" : "P";
        if (token.equals("pa")) return expanded ? "程序偏移 P-A" : "P-A";
        if (token.equals("ps")) return expanded ? "程序偏移 P-S" : "P-S";
        if (normalized.startsWith("program creative")) return expanded ? "景深优先程序" : "景深";
        if (normalized.startsWith("program action")) return expanded ? "动作优先程序" : "动作";
        if (token.startsWith("scn:")) token = token.substring(4);
        String[][] scenes = {{"portrait", "人像"}, {"sportsaction", "运动"}, {"sunset", "日落"},
                {"nightscene", "夜景"}, {"landscape", "风景"}, {"macro", "微距"},
                {"handheldtwilight", "手持夜景"}, {"nightportrait", "夜景人像"}, {"antimotionblur", "动作防抖"},
                {"pet", "宠物"}, {"gourmet", "美食"}, {"fireworks", "烟花"}, {"highsensitivity", "高感光度"},
                {"memoryrecall(mr)", "记忆调用 MR"}, {"flashoff", "禁用闪光"}, {"pictureeffect", "照片效果"},
                {"still", "照片"}, {"movie", "视频"}, {"moviefmode", "视频 F"}, {"s&qfmode", "慢与快 F"}};
        for (String[] scene : scenes) if (token.equals(scene[0])) return scene[1];
        if (normalized.contains("panorama")) return normalized.startsWith("3d") ? "3D 全景" : "全景";
        if (normalized.contains("continuous priority ae")) return normalized.startsWith("tele") ? "变焦连拍" : "连拍优先";
        if (normalized.startsWith("movie recording") || normalized.startsWith("high frame rate") || normalized.startsWith("s&q motion")) {
            String prefix = normalized.contains("slow&quick") || normalized.startsWith("s&q") ? "慢与快"
                    : normalized.startsWith("high frame") ? "高帧率" : "视频";
            for (String letter : new String[] {"AUTO", "P", "A", "S", "M"}) {
                if (raw.toUpperCase(Locale.US).contains("(" + letter + ")"))
                    return prefix + " " + (letter.equals("AUTO") ? "自动" : letter);
            }
        }
        return raw;
    }

    private static NativeValues readNative(Activity activity, Object controller, State state, String key) throws Exception {
        String webName = webName(key), ptpName = ptpName(key);
        if (webName == null) return empty("此参数不可用");
        Object camera = field(controller, "mCamera");
        if (camera == null || !Boolean.TRUE.equals(call(camera, "isEnabled"))) return empty("等待相机连接");
        NativeValues model = controller.getClass().getName().contains("ptpipremotecontrol")
                ? readPtp(activity, controller, ptpName)
                : readWeb(activity, camera, state, key, webName);
        if (model.writable && nativeBusy(activity)) {
            model.writable = false; model.reason = "相机正在处理，请稍后调整";
        }
        return model;
    }

    private static NativeValues readPtp(Activity activity, Object controller, String name) throws Exception {
        NativeValues model = new NativeValues();
        model.code = field(load(activity, PTP + "base.transaction.EnumDevicePropCode"), name);
        model.client = field(controller, "mPtpIpClient");
        if (model.client == null || !"Initialized".equals(String.valueOf(field(model.client, "mPtpIpClientStatus"))))
            return empty("等待相机会话");
        model.dataset = call(controller, "getDevicePropInfoDataset", model.code);
        if (model.dataset == null || !Boolean.TRUE.equals(call(controller, "canGet", model.code)))
            return empty("相机当前模式未提供此参数");
        Class<?> codec = load(activity, CODEC);
        long current = ((Number) field(model.dataset, "mCurrentValue")).longValue();
        String currentString = nativeString(codec, model.code, current);
        model.current = usable(currentString) ? currentString : "";
        Object ordered = field(model.dataset, "mGetSetValues");
        Object allowed = field(model.dataset, "mSetValues");
        boolean nativeWritable = Boolean.TRUE.equals(call(controller, "canSet", model.code));
        if (ordered instanceof List && allowed instanceof Set) {
            for (Object candidate : new ArrayList<Object>((List<?>) ordered)) {
                if (!(candidate instanceof Number) || !((Set<?>) allowed).contains(candidate)) continue;
                String value = nativeString(codec, model.code, ((Number) candidate).longValue());
                if (usable(value) && !model.candidates.containsKey(value)) model.candidates.put(value, candidate);
            }
        }
        if ("ShutterSpeed".equals(name)) {
            Object highResolution = field(load(activity, PTP + "base.transaction.EnumDevicePropCode"), "HighResolutionShutterSpeed");
            if (Boolean.TRUE.equals(call(controller, "canGet", highResolution))) {
                nativeWritable = false; model.reason = "请先在相机上关闭高精度快门模式";
            }
        }
        model.writable = nativeWritable && !model.candidates.isEmpty();
        if (!model.writable && model.reason.length() == 0)
            model.reason = nativeWritable ? "相机暂未提供可调档位" : "请在相机上切换至可调节的拍摄模式";
        return model;
    }

    private static NativeValues readWeb(Activity activity, Object camera, State state, String key, String name) throws Exception {
        NativeValues model = new NativeValues();
        Object enumKey = field(load(activity, WEB + "EnumCameraProperty"), name);
        // Resolve against this Activity's camera, rather than the Enum's global current-camera lookup.
        model.property = call(call(camera, "getCameraProperty"), "getProperty", enumKey);
        if (model.property == null || !Boolean.TRUE.equals(call(model.property, "canGetValue")))
            return empty("相机当前模式未提供此参数");
        Object current = call(model.property, "getCurrentValue");
        Object candidates = call(model.property, "getValueCandidate");
        Fetched fetched;
        synchronized (state) {
            fetched = state.fetched.get(key);
            if (fetched == null) { fetched = new Fetched(); state.fetched.put(key, fetched); }
            if (current == null && now() - fetched.receivedAt < 3000L) current = fetched.value;
            if ((candidates == null || Array.getLength(candidates) == 0) && now() - fetched.receivedAt < 3000L)
                candidates = fetched.candidates;
        }
        if (current != null && usable(current.toString())) model.current = current.toString();
        if ("ev".equals(key) && current != null) {
            addWebExposure(activity, model, current);
        } else if (candidates != null && candidates.getClass().isArray()) {
            for (int index = 0; index < Array.getLength(candidates); index++) {
                Object candidate = Array.get(candidates, index);
                if (candidate != null && usable(candidate.toString())) model.candidates.put(candidate.toString(), candidate);
            }
        }
        boolean nativeWritable = Boolean.TRUE.equals(call(model.property, "canSetValue"));
        model.writable = nativeWritable && !model.candidates.isEmpty();
        if (!model.writable) {
            model.reason = nativeWritable ? "正在读取相机可调档位" : "请在相机上切换至可调节的拍摄模式";
            if (nativeWritable && model.candidates.isEmpty()) queryWeb(activity, state, fetched, model.property);
        }
        return model;
    }

    private static void addWebExposure(Activity activity, NativeValues model, Object value) throws Exception {
        int min = ((Number) field(value, "mMinExposureCompensation")).intValue();
        int max = ((Number) field(value, "mMaxExposureCompensation")).intValue();
        int step = ((Number) field(value, "mStepIndexOfExposureCompensation")).intValue();
        if (max < min || (long) max - min > 500L) return;
        Class<?> integer = load(activity, WEB + "value.ArbitraryInteger");
        // These are camera-provided step indices, formatted by Sony's own EV formatter.
        for (int index = min; index <= max; index++) {
            String raw = String.valueOf(callStatic(value.getClass(), "toString", Integer.valueOf(index), Integer.valueOf(step)));
            model.candidates.put(raw, callStatic(integer, "create", Integer.valueOf(index)));
        }
    }

    private static void queryWeb(Activity activity, State state, Fetched fetched, Object property) throws Exception {
        synchronized (state) {
            if (fetched.pending && now() - fetched.requestedAt < 5000L) return;
            if (now() - fetched.requestedAt < 2000L) return;
            fetched.pending = true; fetched.requestedAt = now();
        }
        try { call(property, "getValue", callback(load(activity, WEB + "IPropertyKeyCallback"), state, null, fetched)); }
        catch (Exception failed) { synchronized (state) { fetched.pending = false; } throw failed; }
    }

    private static Object callback(final Class<?> type, final State state, final Attempt attempt, final Fetched fetched) {
        return Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, new InvocationHandler() {
            @Override public Object invoke(Object proxy, Method method, Object[] args) {
                String name = method.getName();
                if (name.equals("toString")) return "InlineCameraParameterCallback";
                if (name.equals("hashCode")) return Integer.valueOf(System.identityHashCode(proxy));
                if (name.equals("equals")) return Boolean.valueOf(args != null && args.length == 1 && proxy == args[0]);
                synchronized (state) {
                    if (attempt != null && state.attempt == attempt) {
                        if (name.equals("setValueSucceeded") || name.equals("onSetValueSucceeded")) {
                            attempt.pending = false; attempt.reason = "";
                        } else if (name.equals("setValueFailed") || name.equals("onSetValueFailed")) {
                            attempt.pending = false; attempt.reason = "相机未接受此设置，请重试";
                        }
                    }
                    if (fetched != null) {
                        if (name.equals("getValueSucceeded") && args != null && args.length >= 4) {
                            fetched.value = args[2]; fetched.candidates = args[3]; fetched.receivedAt = now(); fetched.pending = false;
                        } else if (name.equals("getValueFailed")) fetched.pending = false;
                    }
                }
                return null;
            }
        });
    }

    private static void fail(State state, Attempt attempt, String reason) {
        if (state != null && attempt != null) synchronized (state) {
            if (state.attempt == attempt) { attempt.pending = false; attempt.reason = reason; }
        }
    }

    private static Object controller(Activity activity) throws Exception {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return null;
        String name = activity.getClass().getName();
        if (!name.equals("com.sony.playmemories.mobile.remotecontrol.RemoteControlActivity")
                && !name.equals("com.sony.playmemories.mobile.ptpipremotecontrol.RemoteControlActivity")) return null;
        Object controller = field(activity, "mController");
        if (controller == null || Boolean.TRUE.equals(field(controller, "mDestroyed"))) return null;
        return controller;
    }

    private static boolean nativeBusy(Activity activity) {
        try {
            for (String name : new String[] {"processing_screen", "restriction_status_screen", "image_download_layout"}) {
                int id = activity.getResources().getIdentifier(name, "id", "com.sony.playmemories.mobile");
                Object view = id == 0 ? null : activity.findViewById(id);
                if (view != null && Boolean.TRUE.equals(call(view, "isShown"))) return true;
            }
        } catch (Exception unavailable) { /* Capability checks still gate writes. */ }
        return false;
    }

    private static State state(Activity activity, Object controller) {
        synchronized (STATES) {
            State state = STATES.get(activity);
            if (state == null || state.controller.get() != controller) { state = new State(controller); STATES.put(activity, state); }
            return state;
        }
    }

    private static NativeValues empty(String reason) { NativeValues model = new NativeValues(); model.reason = reason; return model; }
    private static Snapshot unavailable(String reason) { return new Snapshot(Collections.<String>emptyList(), "", false, reason); }
    private static long now() { return System.nanoTime() / 1000000L; }
    private static boolean usable(String value) {
        return value != null && value.length() != 0 && !value.equals("Unknown") && !value.equals("Undefined") && !value.equals("Empty");
    }
    private static String nativeString(Class<?> codec, Object code, long raw) throws Exception {
        return String.valueOf(callStatic(codec, "toString", code, Long.valueOf(raw)));
    }
    private static String webName(String key) {
        if ("shutter".equals(key)) return "ShutterSpeed";
        if ("aperture".equals(key)) return "FNumber";
        if ("iso".equals(key)) return "IsoSpeedRate";
        if ("ev".equals(key)) return "ExposureCompensation";
        if ("wb".equals(key)) return "WhiteBalance";
        if ("focus".equals(key)) return "FocusMode";
        if ("mode".equals(key)) return "ExposureMode";
        return null;
    }
    private static String ptpName(String key) {
        if ("iso".equals(key)) return "ISOSensitivity";
        if ("ev".equals(key)) return "ExposureBiasCompensation";
        if ("mode".equals(key)) return "ExposureProgramMode";
        return webName(key);
    }
    private static Class<?> load(Activity activity, String name) throws ClassNotFoundException {
        return Class.forName(name, true, activity.getClassLoader());
    }
    private static Object field(Object receiver, String name) throws Exception {
        Class<?> type = receiver instanceof Class ? (Class<?>) receiver : receiver.getClass();
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try { Field field = current.getDeclaredField(name); field.setAccessible(true); return field.get(receiver instanceof Class ? null : receiver); }
            catch (NoSuchFieldException missing) { }
        }
        throw new NoSuchFieldException(name);
    }
    private static Object call(Object receiver, String name, Object... args) throws Exception {
        return invoke(receiver.getClass(), receiver, name, args);
    }
    private static Object callStatic(Class<?> type, String name, Object... args) throws Exception { return invoke(type, null, name, args); }
    private static Object invoke(Class<?> type, Object receiver, String name, Object[] args) throws Exception {
        for (Method method : type.getMethods()) {
            if (!method.getName().equals(name) || method.getParameterTypes().length != args.length) continue;
            Class<?>[] parameters = method.getParameterTypes(); boolean compatible = true;
            for (int index = 0; index < args.length; index++) {
                Class<?> expected = parameters[index];
                if (expected.isPrimitive()) expected = expected == Long.TYPE ? Long.class : expected == Integer.TYPE ? Integer.class
                        : expected == Boolean.TYPE ? Boolean.class : expected;
                if (args[index] != null && !expected.isInstance(args[index])) { compatible = false; break; }
            }
            if (compatible) { method.setAccessible(true); return method.invoke(receiver, args); }
        }
        throw new NoSuchMethodException(type.getName() + "." + name);
    }
}
