package ir.poco.assistant;

import android.app.Activity;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.text.DateFormat;
import java.util.Date;

public class MainActivity extends Activity {
    private TextView connectionView;
    private TextView diagnosticsView;
    private EditText endpointInput;
    private EditText tokenInput;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refreshTask = new Runnable() {
        @Override public void run() {
            refreshDashboard();
            handler.postDelayed(this, 3000);
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        int pad = dp(18);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("پوکو | مرکز کنترل پل");
        title.setTextSize(23);
        title.setPadding(0, 0, 0, dp(8));
        root.addView(title);

        TextView intro = new TextView(this);
        intro.setText("هدف این نسخه: اتصال امن و پایدار گوشی به سرور دستیار. در این مرحله فقط وضعیت ارتباط و دریافت پاکت فرمان آزمایش می‌شود؛ هیچ فرمانی روی گوشی اجرا نمی‌شود.");
        intro.setTextSize(14);
        intro.setPadding(0, 0, 0, dp(14));
        root.addView(intro);

        connectionView = new TextView(this);
        connectionView.setTextSize(17);
        connectionView.setPadding(0, dp(10), 0, dp(10));
        root.addView(connectionView);

        Button refresh = new Button(this);
        refresh.setText("بررسی وضعیت اتصال");
        refresh.setOnClickListener(v -> refreshDashboard());
        root.addView(refresh);

        TextView endpointLabel = new TextView(this);
        endpointLabel.setText("نشانی سرور");
        endpointLabel.setTextSize(14);
        root.addView(endpointLabel);

        endpointInput = new EditText(this);
        endpointInput.setSingleLine(true);
        endpointInput.setHint("https://your-worker.workers.dev");
        endpointInput.setText(BridgeConfig.endpoint(this));
        root.addView(endpointInput);

        TextView tokenLabel = new TextView(this);
        tokenLabel.setText("توکن محرمانه دستگاه");
        tokenLabel.setTextSize(14);
        root.addView(tokenLabel);

        tokenInput = new EditText(this);
        tokenInput.setSingleLine(true);
        tokenInput.setHint("توکن دستگاه را وارد کن");
        tokenInput.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        root.addView(tokenInput);

        Button save = new Button(this);
        save.setText("ذخیره رمزگذاری‌شده اطلاعات اتصال");
        save.setOnClickListener(v -> saveBridgeSettings());
        root.addView(save);

        Button start = new Button(this);
        start.setText("شروع اتصال پوکو");
        start.setOnClickListener(v -> startBridge());
        root.addView(start);

        Button stop = new Button(this);
        stop.setText("توقف اتصال");
        stop.setOnClickListener(v -> {
            stopService(new Intent(this, BridgeService.class));
            connectionView.setText("وضعیت سرویس: متوقف‌شده");
        });
        root.addView(stop);

        TextView note = new TextView(this);
        note.setText("امنیت: توکن با Android Keystore رمزگذاری می‌شود. این نسخه به میکروفون، دوربین، مخاطبان، پیامک یا دسترسی‌پذیری نیاز ندارد. اجرای فرمان‌ها تا زمان تکمیل تأیید محلی غیرفعال می‌ماند.");
        note.setTextSize(13);
        note.setPadding(0, dp(14), 0, dp(10));
        root.addView(note);

        diagnosticsView = new TextView(this);
        diagnosticsView.setTextSize(13);
        diagnosticsView.setTextIsSelectable(true);
        root.addView(diagnosticsView);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        setContentView(scroll);
        refreshDashboard();
    }

    @Override protected void onResume() {
        super.onResume();
        handler.removeCallbacks(refreshTask);
        handler.post(refreshTask);
    }

    @Override protected void onPause() {
        handler.removeCallbacks(refreshTask);
        super.onPause();
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
            refreshDashboard();
        } catch (Exception e) {
            Toast.makeText(this, "ذخیره امن انجام نشد؛ دوباره تلاش کن.", Toast.LENGTH_LONG).show();
        }
    }

    private void startBridge() {
        if (BridgeConfig.endpoint(this).isEmpty() || BridgeConfig.token(this).isEmpty()) {
            Toast.makeText(this, "ابتدا نشانی سرور و توکن دستگاه را ذخیره کن.", Toast.LENGTH_LONG).show();
            return;
        }
        Intent intent = new Intent(this, BridgeService.class);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent);
        else startService(intent);
        Toast.makeText(this, "درخواست شروع پل ارسال شد.", Toast.LENGTH_SHORT).show();
        refreshDashboard();
    }

    private void refreshDashboard() {
        android.content.SharedPreferences p = getSharedPreferences("poco_bridge_inbox", MODE_PRIVATE);
        String error = p.getString("bridge_error", "");
        String state = p.getString("bridge_state", "unknown");
        long last = p.getLong("bridge_last_success", 0L);
        String status;
        if ("connected".equals(state) || "command_received".equals(state)) status = "متصل";
        else if ("error".equals(state)) status = "خطای اتصال";
        else status = "هنوز اتصال موفق ثبت نشده";
        connectionView.setText("● " + status
                + "\nسرور: " + (BridgeConfig.endpoint(this).isEmpty() ? "تنظیم نشده" : "تنظیم شده")
                + "\nآخرین ارتباط موفق: " + (last == 0L ? "نداریم" : DateFormat.getDateTimeInstance().format(new Date(last))));
        String pending = p.getString("pending_command", "");
        String commandSummary = "فرمان در انتظار: هیچ";
        if (!pending.isEmpty()) {
            try {
                JSONObject command = new JSONObject(pending);
                commandSummary = "فرمان دریافت‌شده: " + command.optString("action", "نامشخص")
                        + "\nشناسه: " + command.optString("id", "نامشخص")
                        + "\nوضعیت: " + command.optString("status", "نامشخص");
            } catch (Exception ignored) {
                commandSummary = "یک پاکت فرمان دریافت شده است.";
            }
        }
        diagnosticsView.setText("جزئیات فنی\n"
                + "وضعیت داخلی: " + state
                + "\nخطای آخر: " + (error.isEmpty() ? "ثبت نشده" : error)
                + "\n" + commandSummary
                + "\n\nنکته: دریافت فرمان به معنی اجرای آن نیست. اجرای فرمان تا آماده‌شدن سازوکار تأیید امن غیرفعال است.");
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
