package com.sony.playmemories.mobile.redesign;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Model-specific connection instructions only. Never changes camera/protocol capabilities. */
public final class V3CameraGuide {
    public static final String SMARTPHONE_CONNECT = "smartphone_connect";
    public static final String NETWORK_CONTROL = "network_control";
    public static final String SMART_REMOTE = "smart_remote";
    public static final String GENERIC = "generic";
    private static final Map<String, Profile> MODELS = new LinkedHashMap<String, Profile>();
    private static final Map<String, String> ALIASES = new LinkedHashMap<String, String>();
    private static final String SUPPORT = "https://www.sony.com/electronics/support/articles/00284526";

    static {
        add("ZV-E10", "ZV-E10 一代", SMARTPHONE_CONNECT,
                "https://helpguide.sony.net/ilc/2070/v1/zh-cn/contents/TP1000432928.html",
                "https://helpguide.sony.net/ilc/2070/v1/zh-cn/contents/TP1000432932.html", "ZVE10", "ZV-E10一代");
        add("ILCE-6400", "α6400", NETWORK_CONTROL,
                "https://helpguide.sony.net/ilc/1810/v1/zh-cn/contents/TP0002275426.html",
                "https://support.d-imaging.sony.co.jp/www/cscs/pmm/page2.php?abil=33900&area=gb&lang=en&loc=gb_en&mdl=ILCE-6400", "A6400");
        add("ILCE-6100", "α6100", NETWORK_CONTROL,
                "https://helpguide.sony.net/ilc/1950/v1/en/contents/TP0002670835.html",
                "https://helpguide.sony.net/ilc/1950/v1/en/contents/TP0002670840.html", "A6100");
        add("ILCE-6600", "α6600", NETWORK_CONTROL,
                "https://helpguide.sony.net/ilc/1940/v1/en/contents/TP0002679596.html",
                "https://helpguide.sony.net/ilc/1940/v1/zh-cn/contents/TP0002681606.html", "A6600");
        add("ILCE-7M3", "α7 III", NETWORK_CONTROL,
                "https://helpguide.sony.net/ilc/1720/v1/zh-cn/contents/TP0001663916.html",
                "https://helpguide.sony.net/ilc/1720/v1/en/contents/TP0001653186.html", "A7III", "A7M3");
        add("ILCE-7RM3", "α7R III", NETWORK_CONTROL,
                "https://helpguide.sony.net/ilc/1710/v1/en/contents/TP0001661942.html",
                "https://helpguide.sony.net/ilc/1710/v1/en/contents/TP0001653186.html", "A7RIII", "A7RM3");
        add("ILCE-6000", "α6000", SMART_REMOTE,
                "https://helpguide.sony.net/gbmig/45349331/v1/en/contents/TP0000518263.html",
                "https://www.sony.com/zh-cn/electronics/support/e-mount-body-ilce-6000-series/ilce-6000/articles/00120919", "A6000");
        add("ILCE-6300", "α6300", SMART_REMOTE,
                "https://helpguide.sony.net/ilc/1540/v1/en/contents/TP0000818618.html",
                "https://helpguide.sony.net/ilc/1540/v1/en/contents/TP0000818620.html", "A6300");
        add("ILCE-6500", "α6500", SMART_REMOTE,
                "https://helpguide.sony.net/ilc/1640/v1/zh-cn/contents/TP0001307985.html",
                "https://helpguide.sony.net/ilc/1640/v1/zh-cn/contents/TP0001308053.html", "A6500");
        add("ILCE-7M2", "α7 II", SMART_REMOTE,
                "https://helpguide.sony.net/ilc/1450/v1/en/contents/TP0000301905.html",
                "https://helpguide.sony.net/ilc/1450/v1/en/contents/TP0000301907.html", "A7II", "A7M2");
        add("ILCE-7RM2", "α7R II", SMART_REMOTE,
                "https://helpguide.sony.net/ilc/1520/v1/en/contents/TP0001087157.html",
                "https://helpguide.sony.net/ilc/1520/v1/en/contents/TP0001087159.html", "A7RII", "A7RM2");
    }

    private V3CameraGuide() { }

    public static final class Guide {
        public final String model, modelId, family, shootingTitle, importTitle, note, sourceUrl, importSourceUrl;
        public final List<String> shootingSteps, importSteps;
        public final boolean recognized;

        private Guide(String model, String modelId, String family, String[] shoot, String[] transfer,
                String note, String sourceUrl, String importSourceUrl, boolean recognized) {
            this.model = model; this.modelId = modelId; this.family = family;
            shootingTitle = "在相机上准备遥控"; importTitle = "在相机上准备传输";
            shootingSteps = Collections.unmodifiableList(Arrays.asList(shoot.clone()));
            importSteps = Collections.unmodifiableList(Arrays.asList(transfer.clone()));
            this.note = note; this.sourceUrl = sourceUrl; this.importSourceUrl = importSourceUrl;
            this.recognized = recognized;
        }

        public List<String> steps(boolean importing) { return importing ? importSteps : shootingSteps; }
        public String title(boolean importing) { return importing ? importTitle : shootingTitle; }
        public String source(boolean importing) { return importing ? importSourceUrl : sourceUrl; }
    }

    private static final class Profile {
        final String id, name, family, shootSource, importSource;
        Profile(String id, String name, String family, String shootSource, String importSource) {
            this.id = id; this.name = name; this.family = family;
            this.shootSource = shootSource; this.importSource = importSource;
        }
    }

    private static void add(String id, String name, String family, String shootSource, String importSource, String... aliases) {
        MODELS.put(id, new Profile(id, name, family, shootSource, importSource));
        ALIASES.put(normalize(id), id); ALIASES.put(normalize(name), id);
        for (String alias : aliases) ALIASES.put(normalize(alias), id);
    }

    /** Canonical camera IDs; this is a guide list, not an asserted hardware-compatibility allowlist. */
    public static String[] supportedModels() { return MODELS.keySet().toArray(new String[MODELS.size()]); }

    /** Display labels for an informational picker; selecting one must not register a camera. */
    public static String[] modelNames() {
        String[] names = new String[MODELS.size()]; int index = 0;
        for (Profile profile : MODELS.values()) names[index++] = profile.name;
        return names;
    }

    public static Guide forCamera(String cameraName) {
        String id = ALIASES.get(normalize(cameraName));
        Profile profile = id == null ? null : MODELS.get(id);
        String common = "菜单名称可能随语言或固件略有不同；可调参数以相机实际提供的项目为准。";
        if (profile == null) {
            String label = cameraName == null || cameraName.trim().length() == 0 ? "相机" : cameraName.trim();
            return new Guide(label, "", GENERIC, new String[] {
                    "在相机菜单中打开智能手机遥控功能。旧机型可能位于“应用程序列表”中的“嵌入式智能遥控”。",
                    "停留在相机显示的连接信息画面，查看其 Wi-Fi 名称和密码。",
                    "回到本应用连接相机；按相机提供的方式扫码或输入 Wi-Fi 密码。"
            }, new String[] {
                    "在相机播放模式中打开“发送到智能手机”，按相机提供的方式选择影像。",
                    "停留在相机显示的连接信息画面，再回到本应用连接。",
                    "如果相机未提供手机选片，请在相机上选定影像后再传输。"
            }, "此机型暂用通用引导，请以相机说明书为准。", SUPPORT, SUPPORT, false);
        }
        String[] shoot, transfer;
        if (SMARTPHONE_CONNECT.equals(profile.family)) {
            shoot = new String[] {
                    "MENU → 网络 → 智能手机连接功能，将“智能手机连接”设为“开”。",
                    "在“智能手机连接功能”中选择“连接”，显示二维码。",
                    "保持此画面，回到本应用连接；也可使用相机显示的 Wi-Fi 名称和密码。"
            };
            transfer = new String[] {
                    "在相机播放画面打开 MENU → 网络 → 发送到智能手机功能 → 发送到智能手机。",
                    "在相机上选“这个影像”或“多个影像”，确认后显示二维码。",
                    "回到本应用连接，接收相机已选定的影像。"
            };
            common = "ZV-E10 一代按相机端所选影像发送；手机选片能力以相机实际返回为准。";
        } else if (NETWORK_CONTROL.equals(profile.family)) {
            shoot = new String[] {
                    "MENU → 网络 → 使用智能手机控制，将同名开关设为“开”。",
                    "在“使用智能手机控制”中选择“连接”，显示二维码或 Wi-Fi 信息。",
                    "保持此画面，回到本应用连接。若要查看 Wi-Fi 密码，可按相机删除键切换显示。"
            };
            transfer = new String[] {
                    "MENU → 网络 → 发送到智能手机功能 → 发送到智能手机。",
                    "若要在手机浏览存储卡，选择“在智能手机上选择”；也可先在相机上选片。",
                    "停留在连接信息画面，回到本应用连接并导入。"
            };
        } else {
            shoot = new String[] {
                    "MENU → 应用程序 → 应用程序列表，启动“嵌入式智能遥控”或已安装的“智能遥控”。",
                    "保持相机的连接信息画面，查看其 Wi-Fi 名称和密码。部分版本也提供二维码。",
                    "回到本应用连接相机，并输入相机上显示的 Wi-Fi 密码。"
            };
            transfer = new String[] {
                    "MENU → 无线 → 发送到智能手机。",
                    "若要在手机浏览存储卡，选择“在智能手机上选择”；也可先在相机上选片。",
                    "停留在连接信息画面，回到本应用连接。扫码不可用时使用 Wi-Fi 名称和密码。"
            };
            common = "老机型的可调参数受机内遥控程序版本限制；“智能遥控”下载服务已结束，本应用不会新增相机原本未提供的功能。";
        }
        return new Guide(profile.name, profile.id, profile.family, shoot, transfer, common,
                profile.shootSource, profile.importSource, true);
    }

    private static String normalize(String raw) {
        if (raw == null) return "";
        String text = raw.trim().toUpperCase(Locale.US).replace('Α', 'A')
                .replace('－', '-').replace('–', '-').replace('—', '-');
        if (text.startsWith("DIRECT-")) {
            int separator = text.indexOf(':');
            if (separator >= 0) text = text.substring(separator + 1).trim();
        }
        if (text.startsWith("SONY ")) text = text.substring(5).trim();
        return text.replaceAll("[\\s-]", "");
    }
}
