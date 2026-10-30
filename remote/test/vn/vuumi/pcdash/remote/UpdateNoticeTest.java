package vn.vuumi.pcdash.remote;

public class UpdateNoticeTest {

    private static void check(boolean actual, boolean expected, String message) {
        if (actual != expected) {
            throw new AssertionError(message + ": expected " + expected + " but got " + actual);
        }
    }

    private static UpdateInfo offer(int versionCode) {
        return new UpdateInfo(true, versionCode, "1." + versionCode, "abc", 1024);
    }

    private static void testNewerVersionIsAnnouncedOnce() {
        check(UpdateNotice.shouldNotify(offer(27), 26, 0), true, "Newer than installed, never announced");
        check(UpdateNotice.shouldNotify(offer(27), 26, 27), false, "Already announced or shown on the card");
        check(UpdateNotice.shouldNotify(offer(28), 26, 27), true, "A later version is announced again");
        System.out.println("PASS: testNewerVersionIsAnnouncedOnce");
    }

    private static void testNothingNewIsNotAnnounced() {
        check(UpdateNotice.shouldNotify(offer(26), 26, 0), false, "Same version as installed");
        check(UpdateNotice.shouldNotify(offer(25), 26, 0), false, "Older than installed");
        check(UpdateNotice.shouldNotify(new UpdateInfo(false, 0, "", "", 0), 26, 0), false, "Host offers nothing");
        check(UpdateNotice.shouldNotify(null, 26, 0), false, "No answer");
        System.out.println("PASS: testNothingNewIsNotAnnounced");
    }

    public static void main(String[] args) {
        System.out.println("Running UpdateNoticeTest...");
        testNewerVersionIsAnnouncedOnce();
        testNothingNewIsNotAnnounced();
        System.out.println("ALL UpdateNoticeTest PASSED");
    }
}
