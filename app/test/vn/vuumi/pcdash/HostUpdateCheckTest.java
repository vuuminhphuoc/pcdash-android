package vn.vuumi.pcdash;

import java.util.Map;

public class HostUpdateCheckTest {

    private static final String BASE = "https://github.com/vuuminhphuoc/pcdash-android/releases/download/v1.2.1/";

    private static String release(String body) {
        return "{\"tag_name\":\"v1.2.1\",\"body\":\"" + body + "\",\"assets\":["
                + "{\"name\":\"pcdash-host-v1.2.1.apk\",\"browser_download_url\":\"" + BASE.replace("/", "\\/") + "pcdash-host-v1.2.1.apk\"},"
                + "{\"name\":\"SHA256SUMS.txt\",\"browser_download_url\":\"" + BASE + "SHA256SUMS.txt\"},"
                + "{\"name\":\"pcdash-version.json\",\"browser_download_url\":\"" + BASE + "pcdash-version.json\"}]}";
    }

    private static void assertTrue(boolean cond, String msg) {
        if (!cond) throw new AssertionError("FAILED: " + msg);
    }

    private static final String VERSION_JSON =
            "{\"tag\":\"v1.2.1\",\"host\":{\"versionName\":\"1.26\",\"versionCode\":27},\"remote\":{\"versionName\":\"1.27\",\"versionCode\":28}}";

    public static void main(String[] args) {
        // A release with pcdash-version.json: newer than the installed code gives an offer
        HostUpdateCheck.Offer o = HostUpdateCheck.offer(release(""), VERSION_JSON, 26);
        assertTrue(o != null, "newer release gives an offer");
        assertTrue("1.26".equals(o.versionName) && o.versionCode == 27 && "v1.2.1".equals(o.tag), "offer carries version and tag");
        assertTrue("pcdash-host-v1.2.1.apk".equals(o.apkName), "apk name");
        assertTrue(o.apkUrl.equals(BASE + "pcdash-host-v1.2.1.apk"), "escaped slashes in the url are undone: " + o.apkUrl);
        assertTrue(o.sumsUrl.equals(BASE + "SHA256SUMS.txt"), "sums url");

        // Same or older: nothing to offer
        assertTrue(HostUpdateCheck.offer(release(""), VERSION_JSON, 27) == null, "same version: no offer");
        assertTrue(HostUpdateCheck.offer(release(""), VERSION_JSON, 40) == null, "older release: no offer");

        // No version file: the table in the release notes says it (as in v1.1.1 and v1.2.0)
        String notes = "| `pcdash-host-v1.2.1.apk` | PCDash host 1.26 (code 27) | the always-on phone |\\n| other | x | y |";
        HostUpdateCheck.Offer fromNotes = HostUpdateCheck.offer(release(notes), null, 26);
        assertTrue(fromNotes != null && fromNotes.versionCode == 27, "version read from the notes table");
        assertTrue(HostUpdateCheck.offer(release("no table here"), null, 26) == null, "a release that does not say what it holds gives no offer");

        // Files from anywhere but this project's releases are ignored
        String foreign = "{\"tag_name\":\"v9\",\"assets\":[{\"browser_download_url\":\"https://evil.example/pcdash-host-v9.apk\"},"
                + "{\"browser_download_url\":\"https://evil.example/SHA256SUMS.txt\"}]}";
        Map<String, String> assets = HostUpdateCheck.assets(foreign);
        assertTrue(assets.isEmpty(), "foreign downloads are dropped");
        assertTrue(HostUpdateCheck.offer(foreign, VERSION_JSON, 1) == null, "no offer without this project's files");
        assertTrue(!HostUpdateCheck.isOfficialDownload("https://github.com/someone/else/releases/download/v1/x.apk"), "another repo is not official");

        // Checksums
        String hex = "488b24f20fc09d52e3340d36bfc4b54ee0bc28a996ab18272a4c6547e50ecbcb";
        String sums = "0000000000000000000000000000000000000000000000000000000000000000  pcdash-remote-v1.2.1.apk\n"
                + hex.toUpperCase() + " *pcdash-host-v1.2.1.apk\n";
        assertTrue(hex.equals(HostUpdateCheck.checksumFor(sums, "pcdash-host-v1.2.1.apk")), "checksum found, lower-cased, binary marker ignored");
        assertTrue(HostUpdateCheck.checksumFor(sums, "pcdash-host-v9.apk") == null, "unlisted file has none");
        assertTrue(HostUpdateCheck.checksumFor("short  pcdash-host-v1.2.1.apk\n", "pcdash-host-v1.2.1.apk") == null, "a malformed line is ignored");

        System.out.println("PASS: HostUpdateCheckTest");
    }
}
