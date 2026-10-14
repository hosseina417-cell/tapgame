package ai.arena.nabzcrypto.data;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;

import ai.arena.nabzcrypto.MainActivity;
import ai.arena.nabzcrypto.model.SignalEvent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Emits only journal state transitions; repeated unchanged scans generate no notifications. */
public final class SignalNotifier {
    public static final String CHANNEL_ID = "signal_changes_v1";
    private final Context context;
    private final NotificationManager manager;

    public SignalNotifier(Context context) {
        this.context = context.getApplicationContext();
        manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        ensureChannel();
    }

    public void notifyEvents(List<SignalEvent> events, boolean enabled) {
        if (!enabled || manager == null || events == null || events.isEmpty() || !permitted()) return;
        Intent intent = new Intent(context, MainActivity.class);
        intent.putExtra(MainActivity.EXTRA_OPEN_PERFORMANCE, true);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pending = PendingIntent.getActivity(context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        // Keep only the latest transition per record in a scan. Intermediate transitions remain
        // in the journal, while the status bar cannot fire several alerts for one fast candle.
        Map<String, SignalEvent> latestByRecord = new LinkedHashMap<>();
        for (SignalEvent event : events) latestByRecord.put(event.recordId, event);
        List<SignalEvent> unique = new ArrayList<>(latestByRecord.values());
        if (unique.size() > 3) {
            StringBuilder text = new StringBuilder(unique.size())
                    .append(" تغییر مهم ثبت شد: ");
            for (int i = 0; i < Math.min(3, unique.size()); i++) {
                if (i > 0) text.append(" • ");
                text.append(unique.get(i).messageFa);
            }
            post(pending, 0x4E425A, "نبض کریپتو • خلاصهٔ تغییرها", text.toString());
            return;
        }
        for (SignalEvent event : unique) {
            post(pending, event.recordId.hashCode(),
                    "نبض کریپتو • " + event.state.faLabel, event.messageFa);
        }
    }

    private void post(PendingIntent pending, int id, String title, String text) {
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(context, CHANNEL_ID)
                : new Notification.Builder(context);
        builder.setSmallIcon(context.getApplicationInfo().icon)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(pending)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_STATUS);
        manager.notify(id, builder.build());
    }

    private boolean permitted() {
        return Build.VERSION.SDK_INT < 33
                || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void ensureChannel() {
        if (manager == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                "تغییر وضعیت سیگنال", NotificationManager.IMPORTANCE_DEFAULT);
        channel.setDescription("فقط سیگنال جدید، تأیید، ابطال، هدف یا انقضا");
        manager.createNotificationChannel(channel);
    }
}
