package vn.vuumi.pcdash.remote;

public class TabNavigator {

    public interface Listener {
        void onTabSelected(MainTab tab);
    }

    private MainTab current = MainTab.CONTROL;
    private Listener listener;

    public TabNavigator() {}

    public TabNavigator(Listener listener) {
        this.listener = listener;
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public MainTab current() {
        return current;
    }

    public boolean select(MainTab t) {
        if (t == null || t == MainTab.MORE) {
            return false;
        }
        if (t == current) {
            return false;
        }
        current = t;
        if (listener != null) {
            listener.onTabSelected(current);
        }
        return true;
    }

    public boolean onBack() {
        if (current != MainTab.CONTROL) {
            current = MainTab.CONTROL;
            if (listener != null) {
                listener.onTabSelected(current);
            }
            return true;
        }
        return false;
    }
}
