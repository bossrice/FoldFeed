package vn.phucuong.foldfeed;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.widget.Toast;

/** Nhận kết quả cài bản cập nhật từ hệ thống. */
public class UpdateReceiver extends BroadcastReceiver {

    @SuppressWarnings("deprecation")
    @Override
    public void onReceive(Context context, Intent intent) {
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS,
                PackageInstaller.STATUS_FAILURE);
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            // Lần đầu (hoặc khi hệ thống yêu cầu): hiện màn hình xác nhận cài đặt.
            Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirm != null) {
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try {
                    context.startActivity(confirm);
                } catch (Exception ignored) { }
            }
        } else if (status != PackageInstaller.STATUS_SUCCESS) {
            String msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
            Toast.makeText(context,
                    "Cập nhật không thành công" + (msg != null ? ": " + msg : ""),
                    Toast.LENGTH_LONG).show();
        }
    }
}
