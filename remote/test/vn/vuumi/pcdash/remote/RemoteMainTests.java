package vn.vuumi.pcdash.remote;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

/**
 * Runs the remote's tests (plain classes with a main method that throws on failure) under Gradle,
 * one JUnit test per class: {@code gradlew :remote:testDebugUnitTest}.
 */
@RunWith(Parameterized.class)
public class RemoteMainTests {
    private static final String[] TESTS = {
            "RemoteTailnetTest", "RemoteControllerTest", "DiagnosticsFormatterTest", "MetricsParserTest",
            "MetricsFormatterTest", "MetricsApiTest", "UpdateInfoTest", "UpdateDownloadTest", "ApkInstallerTest",
            "ActionGuardTest", "LogParserTest", "HistoryParserTest", "QuotaParserTest", "QuotaFormatterTest",
            "QuotaViewModelTest", "TabNavigatorTest", "PollGenerationTest", "HistoryChartViewDataTest",
            "HostAlertRulesTest", "PcListTest", "TailnetPeersTest", "PowerActionWatchTest", "QuickActionsTest",
            "UiThreadSafetyTest",
    };

    @Parameterized.Parameters(name = "{0}")
    public static Collection<Object[]> tests() {
        List<Object[]> out = new ArrayList<Object[]>();
        for (String t : TESTS) {
            out.add(new Object[]{"vn.vuumi.pcdash.remote." + t});
        }
        return out;
    }

    private final String className;

    public RemoteMainTests(String className) {
        this.className = className;
    }

    @Test
    public void run() throws Throwable {
        try {
            Class.forName(className).getMethod("main", String[].class).invoke(null, (Object) new String[0]);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }
}
