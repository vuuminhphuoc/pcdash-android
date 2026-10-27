package vn.vuumi.pcdash;

public interface RemoteActionExecutor {
    String statusJson();
    String doAction(String action);
    default String metricsJson() { return null; }
    default String quotaJson(boolean refresh) { return null; }
}
