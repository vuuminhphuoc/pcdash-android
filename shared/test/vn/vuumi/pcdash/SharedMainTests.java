package vn.vuumi.pcdash;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

/**
 * Runs the shared module's tests (plain classes with a main method that throws on failure) under
 * Gradle, one JUnit test per class: {@code gradlew :shared:testDebugUnitTest}.
 */
@RunWith(Parameterized.class)
public class SharedMainTests {
    private static final String[] TESTS = {
            "vn.vuumi.pcdash.pairing.PairingProtocolTest",
            "vn.vuumi.pcdash.pairing.PairingQrTest",
            "vn.vuumi.pcdash.net.PcnetStateParseTest",
            "vn.vuumi.pcdash.lock.AppLockTest",
            "vn.vuumi.pcdash.i18n.L10nTest",
    };

    @Parameterized.Parameters(name = "{0}")
    public static Collection<Object[]> tests() {
        List<Object[]> out = new ArrayList<Object[]>();
        for (String t : TESTS) {
            out.add(new Object[]{t});
        }
        return out;
    }

    private final String className;

    public SharedMainTests(String className) {
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
