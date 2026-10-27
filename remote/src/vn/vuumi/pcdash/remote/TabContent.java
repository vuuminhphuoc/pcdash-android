package vn.vuumi.pcdash.remote;

import android.view.View;

public interface TabContent {
    View view();
    void onShow();
    void onHide();
}
