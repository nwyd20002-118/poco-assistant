package ir.poco.assistant;

import android.app.Activity;
import android.os.Bundle;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ChatActivity extends Activity {
    private LinearLayout messagesLayout;
    private ScrollView scroll;
    private EditText composer;
    private Button sendButton;
    private JSONArray history;
    private final ExecutorService network = Executors.newSingleThreadExecutor();

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        history = loadHistory();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(14), dp(12), dp(14), dp(10));
        root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);

        TextView title = new TextView(this);
        title.setText("گفت‌وگو با پوکو");
        title.setTextSize(22);
        title.setTypeface(null, Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, 0, 0, dp(10));
        root.addView(title);

        scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        messagesLayout = new LinearLayout(this);
        messagesLayout.setOrientation(LinearLayout.VERTICAL);
        messagesLayout.setPadding(dp(2), dp(4), dp(2), dp(8));
        scroll.addView(messagesLayout);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout composerRow = new LinearLayout(this);
        composerRow.setOrientation(LinearLayout.HORIZONTAL);
        composer = new EditText(this);
        composer.setHint("پیامت رو بنویس...");
        composer.setMinLines(1);
        composer.setMaxLines(4);
        composer.setTextSize(16);
        composer.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        composerRow.addView(composer, new LinearLayout.LayoutParams(0, -2, 1));

        sendButton = new Button(this);
        sendButton.setText("ارسال");
        sendButton.setOnClickListener(v -> sendMessage());
        composerRow.addView(sendButton);
        root.addView(composerRow);
        setContentView(root);

        if (history.length() == 0) addBubble("سلام! من پوکو هستم. از اینجا می‌تونیم گفت‌وگوی واقعی‌مون رو شروع کنیم.", false);
        else renderHistory();
    }

    private void sendMessage() {
        String message = composer.getText().toString().trim();
        if (message.isEmpty()) return;
        if (BridgeConfig.endpoint(this).isEmpty() || BridgeConfig.token(this).isEmpty()) {
            Toast.makeText(this, "اول اطلاعات اتصال سرور را در صفحه اصلی ذخیره کن.", Toast.LENGTH_LONG).show();
            return;
        }
        composer.setText("");
        history.put(messageObject("user", message));
        addBubble(message, true);
        saveHistory();
        sendButton.setEnabled(false);
        sendButton.setText("...");
        final JSONArray requestHistory = recentMessages(history, 20);
        network.execute(() -> {
            String answer = null;
            String error = null;
            try { answer = askPoco(requestHistory); }
            catch (Exception e) {
                error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            }
            final String result = answer;
            final String problem = error;
            runOnUiThread(() -> {
                sendButton.setEnabled(true);
                sendButton.setText("ارسال");
                if (problem != null) {
                    addBubble("ارتباط برقرار نشد: " + problem + "\nلطفاً اتصال و تنظیمات سرور را بررسی کن.", false);
                } else {
                    history.put(messageObject("assistant", result));
                    saveHistory();
                    addBubble(result, false);
                }
            });
        });
    }

    private String askPoco(JSONArray messages) throws Exception {
        URL url = new URL(BridgeConfig.endpoint(this) + "/v1/device/chat");
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(20000);
        connection.setReadTimeout(90000);
        connection.setDoOutput(true);
        connection.setRequestProperty("Authorization", "Bearer " + BridgeConfig.token(this));
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        connection.setRequestProperty("Accept", "application/json");
        JSONObject body = new JSONObject();
        body.put("messages", messages);
        try (OutputStream out = connection.getOutputStream()) {
            out.write(body.toString().getBytes(StandardCharsets.UTF_8));
        }
        int status = connection.getResponseCode();
        InputStream stream = status >= 200 && status < 300 ? connection.getInputStream() : connection.getErrorStream();
        StringBuilder response = new StringBuilder();
        if (stream != null) {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) response.append(line);
            }
        }
        connection.disconnect();
        JSONObject data = new JSONObject(response.toString());
        if (status < 200 || status >= 300) {
            String detail = data.optString("detail", data.optString("error", "HTTP " + status));
            throw new IllegalStateException(detail);
        }
        String text = data.optString("text", "").trim();
        if (text.isEmpty()) throw new IllegalStateException("پاسخ خالی از سرور دریافت شد");
        return text;
    }

    private JSONObject messageObject(String role, String content) {
        JSONObject item = new JSONObject();
        try { item.put("role", role); item.put("content", content); } catch (Exception ignored) {}
        return item;
    }

    private JSONArray recentMessages(JSONArray source, int max) {
        JSONArray result = new JSONArray();
        int start = Math.max(0, source.length() - max);
        for (int i = start; i < source.length(); i++) {
            JSONObject item = source.optJSONObject(i);
            if (item != null) result.put(item);
        }
        return result;
    }

    private JSONArray loadHistory() {
        return ChatHistoryStore.load(this);
    }

    private void saveHistory() {
        if (!ChatHistoryStore.save(this, history)) {
            Toast.makeText(this, "ذخیره امن گفت‌وگو انجام نشد؛ متن در حافظه دائمی ذخیره نشد.", Toast.LENGTH_LONG).show();
        }
    }

    private void renderHistory() {
        messagesLayout.removeAllViews();
        for (int i = 0; i < history.length(); i++) {
            JSONObject item = history.optJSONObject(i);
            if (item != null) addBubble(item.optString("content"), "user".equals(item.optString("role")));
        }
        scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
    }

    private void addBubble(String text, boolean user) {
        TextView bubble = new TextView(this);
        bubble.setText(text);
        bubble.setTextSize(16);
        bubble.setTextColor(0xFF202124);
        bubble.setGravity(Gravity.RIGHT);
        bubble.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG_RTL);
        bubble.setPadding(dp(13), dp(10), dp(13), dp(10));
        bubble.setBackgroundColor(user ? 0xFFE1F0FF : 0xFFF0F1F3);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                user ? (int)(getResources().getDisplayMetrics().widthPixels * 0.82f) : (int)(getResources().getDisplayMetrics().widthPixels * 0.90f), -2);
        params.gravity = user ? Gravity.LEFT : Gravity.RIGHT;
        params.bottomMargin = dp(8);
        messagesLayout.addView(bubble, params);
        scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    @Override protected void onDestroy() {
        network.shutdown();
        super.onDestroy();
    }
}
