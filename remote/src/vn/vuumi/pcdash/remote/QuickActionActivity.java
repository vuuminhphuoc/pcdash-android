package vn.vuumi.pcdash.remote;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/**
 * Invisible, non-exported entry point for the launcher shortcuts and the Quick Settings tile:
 * records the command and opens RemoteMainActivity, which sends it once the tailnet is up.
 */
public class QuickActionActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        QuickActions.request(getIntent() != null ? getIntent().getAction() : null, System.currentTimeMillis());
        Intent main = new Intent(this, RemoteMainActivity.class);
        main.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(main);
        finish();
    }
}
