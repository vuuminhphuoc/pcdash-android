package vn.vuumi.pcdash;

import java.io.IOException;
import java.io.InputStream;

public interface UpdateAssetSource {
    InputStream openInfo() throws IOException;
    InputStream openApk() throws IOException;
    long apkSize();
}
