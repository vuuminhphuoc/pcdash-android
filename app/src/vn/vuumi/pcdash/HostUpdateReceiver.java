package vn.vuumi.pcdash;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.widget.Toast;

import vn.vuumi.pcdash.i18n.L10n;

/** Gets the installer's answer for a host update: shows Android's confirm screen, or says why it failed. */
public class HostUpdateReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !HostUpdater.ACTION_RESULT.equals(intent.getAction())) return;
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirm != null) {
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(confirm);
            }
            return;
        }
        if (status == PackageInstaller.STATUS_SUCCESS) return; // the app is replaced and restarts
        String detail = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
        String text = status == PackageInstaller.STATUS_FAILURE_ABORTED
                ? L10n.t("Update cancelled", "Đã hủy cập nhật")
                : status == PackageInstaller.STATUS_FAILURE_CONFLICT
                ? L10n.t("Update refused: the signature does not match the installed app", "Cập nhật bị từ chối: chữ ký không khớp bản đang cài")
                : L10n.t("Update failed", "Cập nhật thất bại") + (detail != null && !detail.isEmpty() ? ": " + detail : "");
        Toast.makeText(context, text, Toast.LENGTH_LONG).show();
    }
}
