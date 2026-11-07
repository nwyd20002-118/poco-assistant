package ir.poco.assistant;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.DateFormat;
import java.util.Date;

public class MainActivity extends Activity {
    private TextView statusView;
    private TextView messagesView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("پوکو | نسخه آزمایشی ۰٫۱");
        title.setTextSize(22);
        root.addView(title);

        TextView description = new TextView(this);
        description.setText("گام اول پل اندرویدی: دریافت اعلان‌های تلگرام و نگهداری محلیِ حداکثر ۳۰ اعلان آخر.");
        description.setTextSize(15);
        description.setPadding(0, dp(8), 0, dp(12));
        root.addView(description);

        statusView = new TextView(this);
        statusView.setTextSize(16);
        root.addView(statusView);

        Button accessButton = new Button(this);
        accessButton.setText("باز کردن تنظیمات دسترسی اعلان");
        accessButton.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
            } catch (Exception e) {
                Toast.makeText(this, "باز کردن تنظیمات اعلان ممکن نشد.", Toast.LENGTH_LONG).show();
            }
        });
        root.addView(accessButton);

        Button refreshButton = new Button(this);
        refreshButton.setText("به‌روزرسانی فهرست");
        refreshButton.setOnClickListener(v -> refreshScreen());
        root.addView(refreshButton);

        Button clearButton = new Button(this);
        clearButton.setText("پاک کردن اعلان‌های ذخیره‌شده");
        clearButton.setOnClickListener(v -> {
            NotificationStore.clear(this);
            refreshScreen();
        });
        root.addView(clearButton);

        TextView privacyNote = new TextView(this);
        privacyNote.setText("حریم خصوصی این نسخه:\n" +
                "• فقط بسته‌های شناخته‌شده تلگرام در کد پردازش می‌شوند.\n" +
                "• داده‌ها فقط در فضای خصوصی همین برنامه روی گوشی ذخیره می‌شوند.\n" +
                "• برنامه مجوز اینترنت، میکروفون، مخاطبان، پیامک یا دسترسی‌پذیری ندارد.\n" +
                "• برای خواندن اعلان‌ها باید خودت دسترسی را در تنظیمات اندروید فعال کنی.");
        privacyNote.setTextSize(13);
        privacyNote.setPadding(0, dp(12), 0, dp(12));
        root.addView(privacyNote);

        TextView scopeNote = new TextView(this);
        scopeNote.setText("هنوز در این نسخه وجود ندارد: پاسخ‌دادن به تلگرام، هوش مصنوعی/API، اتوماسیون برنامه‌ها و فعال‌سازی صوتی پس‌زمینه.");
        scopeNote.setTextSize(13);
        root.addView(scopeNote);

        ScrollView scroll = new ScrollView(this);
        messagesView = new TextView(this);
        messagesView.setTextSize(14);
        messagesView.setTextIsSelectable(true);
        messagesView.setPadding(0, dp(16), 0, dp(24));
        scroll.addView(messagesView);
        root.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
        refreshScreen();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshScreen();
    }

    private void refreshScreen() {
        boolean enabled = hasNotificationAccess();
        statusView.setText(enabled
                ? "وضعیت دسترسی اعلان: فعال"
                : "وضعیت دسترسی اعلان: خاموش (هنوز هیچ داده‌ای دریافت نمی‌شود)");
        renderMessages();
    }

    private boolean hasNotificationAccess() {
        String enabledListeners = Settings.Secure.getString(
                getContentResolver(), "enabled_notification_listeners");
        if (enabledListeners == null) return false;
        String component = new ComponentName(
                this, TelegramNotificationListener.class).flattenToString();
        return enabledListeners.contains(component);
    }

    private void renderMessages() {
        JSONArray rows = NotificationStore.read(this);
        if (rows.length() == 0) {
            messagesView.setText("هنوز اعلان تلگرامی ذخیره نشده است.");
            return;
        }
        StringBuilder out = new StringBuilder("اعلان‌های ذخیره‌شده (جدیدترین اول):\n\n");
        DateFormat dateFormat = DateFormat.getDateTimeInstance(
                DateFormat.SHORT, DateFormat.SHORT);
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i);
            if (row == null) continue;
            String title = row.optString("title", "");
            String body = row.optString("text", "");
            long time = row.optLong("time", 0L);
            out.append(dateFormat.format(new Date(time))).append('\n');
            if (!title.isEmpty()) out.append(title).append('\n');
            if (!body.isEmpty()) out.append(body).append('\n');
            out.append("\n--------------------\n\n");
        }
        messagesView.setText(out.toString());
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
