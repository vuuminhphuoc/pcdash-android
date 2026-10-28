package vn.vuumi.pcdash;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reading a GitHub release for the host's own update: which version it holds, where the APK and the
 * checksums are, and what the checksum of a file is. No Android code, so it can be tested on its own.
 */
final class HostUpdateCheck {
    /** Where releases come from; an APK from anywhere else is refused. */
    static final String RELEASE_API = "https://api.github.com/repos/vuuminhphuoc/pcdash-android/releases/latest";
    static final String DOWNLOAD_PREFIX = "https://github.com/vuuminhphuoc/pcdash-android/releases/download/";
    static final String VERSION_ASSET = "pcdash-version.json";
    static final String SUMS_ASSET = "SHA256SUMS.txt";

    private static final Pattern TAG = Pattern.compile("\"tag_name\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern ASSET_URL = Pattern.compile("\"browser_download_url\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern BODY = Pattern.compile("\"body\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");
    // pcdash-version.json, written by package-release.ps1: {"tag":"v1.2.1","host":{"versionName":"1.25","versionCode":26},...}
    private static final Pattern HOST_IN_JSON = Pattern.compile(
            "\"host\"\\s*:\\s*\\{[^}]*\"versionName\"\\s*:\\s*\"([^\"]+)\"[^}]*\"versionCode\"\\s*:\\s*(\\d+)");
    // Release notes written by hand: | `pcdash-host-v1.2.0.apk` | PCDash host 1.25 (code 26) | ...
    private static final Pattern HOST_IN_NOTES = Pattern.compile("pcdash-host-[^|]*\\|\\s*PCDash host ([0-9.]+) \\(code (\\d+)\\)");

    private HostUpdateCheck() {}

    static final class Offer {
        final String tag;
        final String versionName;
        final long versionCode;
        final String apkName;
        final String apkUrl;
        final String sumsUrl;

        Offer(String tag, String versionName, long versionCode, String apkName, String apkUrl, String sumsUrl) {
            this.tag = tag;
            this.versionName = versionName;
            this.versionCode = versionCode;
            this.apkName = apkName;
            this.apkUrl = apkUrl;
            this.sumsUrl = sumsUrl;
        }
    }

    /** The release's files by name; only downloads of this project count. */
    static Map<String, String> assets(String releaseJson) {
        Map<String, String> out = new LinkedHashMap<String, String>();
        Matcher m = ASSET_URL.matcher(releaseJson);
        while (m.find()) {
            String url = m.group(1).replace("\\/", "/");
            if (!isOfficialDownload(url)) continue;
            out.put(url.substring(url.lastIndexOf('/') + 1), url);
        }
        return out;
    }

    static boolean isOfficialDownload(String url) {
        return url != null && url.startsWith(DOWNLOAD_PREFIX);
    }

    static String tag(String releaseJson) {
        Matcher m = TAG.matcher(releaseJson);
        return m.find() ? m.group(1) : null;
    }

    /**
     * The host version a release carries: from its pcdash-version.json when it has one, else from the
     * table in its notes. Null when neither says.
     */
    static String[] hostVersion(String releaseJson, String versionJson) {
        if (versionJson != null) {
            Matcher m = HOST_IN_JSON.matcher(versionJson);
            if (m.find()) return new String[]{m.group(1), m.group(2)};
        }
        Matcher b = BODY.matcher(releaseJson);
        if (b.find()) {
            String notes = unescape(b.group(1));
            Matcher m = HOST_IN_NOTES.matcher(notes);
            if (m.find()) return new String[]{m.group(1), m.group(2)};
        }
        return null;
    }

    /**
     * An offer when the release holds a host APK newer than [installedCode]; null when the app is up to
     * date, or the release does not say what it holds.
     */
    static Offer offer(String releaseJson, String versionJson, long installedCode) {
        String[] version = hostVersion(releaseJson, versionJson);
        if (version == null) return null;
        long code;
        try {
            code = Long.parseLong(version[1]);
        } catch (NumberFormatException e) {
            return null;
        }
        if (code <= installedCode) return null;
        Map<String, String> files = assets(releaseJson);
        String apkName = null;
        for (String name : files.keySet()) {
            if (name.startsWith("pcdash-host-") && name.endsWith(".apk")) apkName = name;
        }
        String sums = files.get(SUMS_ASSET);
        if (apkName == null || sums == null) return null;
        return new Offer(tag(releaseJson), version[0], code, apkName, files.get(apkName), sums);
    }

    /** The SHA-256 (lower case hex) listed for [name] in a SHA256SUMS.txt, or null. */
    static String checksumFor(String sums, String name) {
        for (String raw : sums.split("\n")) {
            String line = raw.trim();
            if (line.length() < 66 || line.charAt(64) != ' ') continue;
            String hex = line.substring(0, 64);
            String file = line.substring(65).trim();
            if (file.startsWith("*")) file = file.substring(1);
            if (file.equals(name) && hex.matches("[0-9a-fA-F]{64}")) return hex.toLowerCase(java.util.Locale.ROOT);
        }
        return null;
    }

    private static String unescape(String s) {
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != '\\' || i + 1 >= s.length()) {
                out.append(c);
                continue;
            }
            char n = s.charAt(++i);
            switch (n) {
                case 'n': out.append('\n'); break;
                case 'r': out.append('\r'); break;
                case 't': out.append('\t'); break;
                case 'u':
                    if (i + 4 < s.length()) {
                        try {
                            out.append((char) Integer.parseInt(s.substring(i + 1, i + 5), 16));
                            i += 4;
                            break;
                        } catch (NumberFormatException ignored) {
                        }
                    }
                    out.append('u');
                    break;
                default: out.append(n);
            }
        }
        return out.toString();
    }
}
