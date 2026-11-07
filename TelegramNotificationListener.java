package ir.poco.assistant;

import android.app.Notification;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

public class TelegramNotificationListener extends NotificationListenerService {
    private static final Set<String> TELEGRAM_PACKAGES = new HashSet<>(Arrays.asList(
            "org.telegram.messenger",
            "org.thunderdog.challegram"
    ));

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn == null || !TELEGRAM_PACKAGES.contains(sbn.getPackageName())) {
            return;
        }

        Notification notification = sbn.getNotification();
        if (notification == null || notification.extras == null) {
            return;
        }

        Bundle extras = notification.extras;
        String title = asText(extras.getCharSequence(Notification.EXTRA_TITLE));
        String body = asText(extras.getCharSequence(Notification.EXTRA_BIG_TEXT));

        if (body.isEmpty()) {
            body = asText(extras.getCharSequence(Notification.EXTRA_TEXT));
        }

        // اگر پیش‌نمایش پیام در تلگرام خاموش باشد، متن ممکن است خالی باشد.
        if (title.isEmpty() && body.isEmpty()) {
            return;
        }

        NotificationStore.add(
                this,
                sbn.getPackageName(),
                title,
                body,
                sbn.getPostTime()
        );
    }

    private String asText(CharSequence value) {
        return value == null ? "" : value.toString().trim();
    }
}
