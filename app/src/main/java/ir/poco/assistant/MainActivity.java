package ir.poco.assistant;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.EditText;
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
    private TextView bridgeInfoView;
    private EditText endpointInput;
    private EditText tokenInput;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("پوکو | نسخه آزمایشی ۰٫۳");
        title.setTextSize(22);
        root.addView(title);

        TextView description = new TextView(this);
        description.setText("آزمایش دریافت اعلان‌های تلگرام؛ این نسخه وضعیت اتصال سرویس و تعداد اعلان‌های دریافتی را هم نشان می‌دهد.");
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
        privacyNote.setText("حریم خصوصی: اعلان‌های تلگرام در حافظه خصوصی گوشی می‌مانند. پل ارتباطی فقط از اینترنت برای اتصال HTTPS به سروری که خودت تنظیم می‌کنی استفاده می‌کند؛ این برنامه به میکروفون، مخاطبان، پیامک یا دسترسی‌پذیری دسترسی نمی‌خواهد.");
        privacyNote.setTextSize(13);
        privacyNote.setPadding(0, dp(12), 0, dp(12));
        root.addView(privacyNote);

        TextView bridgeTitle = new TextView(this);
        bridgeTitle.setText("پل ارتباطی پوکو | آزمایشی");
        bridgeTitle.setTextSize(19);
        root.addView(bridgeTitle);

        TextView bridgeDescription = new TextView(this);
        bridgeDescription.setText("نشانی HTTPS سرور و توکن مخصوص همین گوشی را وارد کن. توکن با کلید Android Keystore رمزگذاری می‌شود. این مرحله فقط اتصال و دریافت پاکت فرمان را آزمایش می‌کند؛ هیچ فرمانی روی گوشی اجرا نمی‌شود.");
        bridgeDescription.setTextSize(13);
        root.addView(bridgeDescription);

        endpointInput = new EditText(this);
        endpointInput.setSingleLine(true);
        endpointInput.setHint("نشانی سرور، مانند https://name.workers.dev");
        endpointInput.setText(BridgeConfig.endpoint(this));
        root.addView(endpointInput);

        tokenInput = new EditText(this);
        tokenInput.setSingleLine(true);
        tokenInput.setHint("توکن محرمانه دستگاه");
        tokenInput.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        root.addView(tokenInput);

        Button saveBridgeButton = new Button(this);
        saveBridgeButton.setText("ذخیره امن اطلاعات اتصال");
        saveBridgeButton.setOnClickListener(v -> saveBridgeSettings());
        root.addView(saveBridgeButton);

        Button startBridgeButton = new Button(this);
        startBridgeButton.setText("شروع اتصال پوکو");
        startBridgeButton.setOnClickListener(v -> startBridge());
        root.addView(startBridgeButton);

        Button stopBridgeButton = new Button(this);
        stopBridgeButton.setText("توقف اتصال");
        stopBridgeButton.setOnClickListener(v -> {
            stopService(new Intent(this, BridgeService.class));
            bridgeInfoView.setText("وضعیت پل: متوقف");
        });
        root.addView(stopBridgeButton);

        bridgeInfoView = new TextView(this);
        bridgeInfoView.setTextSize(13);
        bridgeInfoView.setPadding(0, dp(8), 0, dp(8));
        root.addView(bridgeInfoView);

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
        statusView.setText((enabled ? "دسترسی اعلان: فعال" : "دسترسی اعلان: خاموش")
                + "\n" + NotificationStore.diagnostics(this));
        renderMessages();
        refreshBridgeInfo();
    }

    private void saveBridgeSettings() {
        String endpoint = endpointInput.getText().toString().trim().replaceAll("/+$", "");
        String token = tokenInput.getText().toString().trim();
        if (!endpoint.startsWith("https://") || token.length() < 20) {
            Toast.makeText(this, "نشانی باید HTTPS باشد و توکن معتبر وارد شود.", Toast.LENGTH_LONG).show();
            return;
        }
        try {
            BridgeConfig.save(this, endpoint, token);
            tokenInput.setText("");
            Toast.makeText(this, "اطلاعات اتصال به‌صورت رمزگذاری‌شده ذخیره شد.", Toast.LENGTH_LONG).show();
            refreshBridgeInfo();
        } catch (Exception e) {
            Toast.makeText(this, "ذخیره امن انجام نشد.", Toast.LENGTH_LONG).show();
        }
    }

    private void startBridge() {\n        if (android.os.Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {\n            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 701);\n            return;\n        }
        if (BridgeConfig.endpoint(this).isEmpty() || BridgeConfig.token(this).isEmpty()) {
            Toast.makeText(this, "اول نشانی سرور و توکن را ذخیره کن.", Toast.LENGTH_LONG).show();
            return;
        }
        Intent intent = new Intent(this, BridgeService.class);
        if (android.os.Build.VERSION.SDK_INT >= 26) startForegroundService(intent);
        else startService(intent);
        bridgeInfoView.setText("وضعیت پل: سرویس در حال شروع است...");
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 701 && grantResults.length > 0
                && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            startBridge();
        } else if (requestCode == 701) {
            Toast.makeText(this, "برای نمایش وضعیت اتصال، مجوز اعلان لازم است.", Toast.LENGTH_LONG).show();
        }
    }

    private void refreshBridgeInfo() {
        String pending = getSharedPreferences("poco_bridge_inbox", MODE_PRIVATE)
                .getString("pending_command", "");
        bridgeInfoView.setText("اطلاعات اتصال: " + (BridgeConfig.endpoint(this).isEmpty() ? "تنظیم نشده" : "ذخیره شده")
                + "\\nفرمان دریافتی: " + (pending.isEmpty() ? "نداریم" : pending));
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
            messagesView.setText("هنوز متن اعلانی ذخیره نشده است.");
            return;
        }
        StringBuilder out = new StringBuilder("اعلان‌های ذخیره‌شده (جدیدترین اول):\n\n");
        DateFormat dateFormat = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT);
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
