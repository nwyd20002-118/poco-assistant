package ir.poco.assistant;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * User-started foreground relay client. This first milestone only pairs,
 * heartbeats and receives/records command envelopes; it never executes them.
 */
public class BridgeService extends Service {
    public static final String ACTION_STOP = "ir.poco.assistant.BRIDGE_STOP";
    private static final String CHANNEL = "poco_bridge_status";
    private ScheduledExecutorService executor;
    private volatile long lastHeartbeat = 0L;
    private volatile boolean pollingScheduled = false;

    @Override public void onCreate() {
        super.onCreate();
        createChannel();
        startForeground(72, buildNotification("پل ارتباطی آماده راه‌اندازی است"));
        executor = Executors.newSingleThreadScheduledExecutor();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (executor != null && executor.isShutdown()) executor = Executors.newSingleThreadScheduledExecutor();
        if (executor != null && !pollingScheduled) {
            pollingScheduled = true;
            executor.scheduleWithFixedDelay(this::pollSafely, 0, 15, TimeUnit.SECONDS);
        }
        return START_STICKY;
    }

    private void pollSafely() {
        try {
            String endpoint = BridgeConfig.endpoint(this);
            String token = BridgeConfig.token(this);
            if (endpoint.isEmpty() || token.isEmpty()) {
                updateNotification("اطلاعات اتصال تنظیم نشده است");
                return;
            }
            long now = System.currentTimeMillis();
            if (now - lastHeartbeat > 60000L) {
                JSONObject device = new JSONObject();
                device.put("app_version", getPackageManager().getPackageInfo(getPackageName(), 0).versionName);
                device.put("android_version", Build.VERSION.RELEASE);
                device.put("device_model", Build.MANUFACTURER + " " + Build.MODEL);
                request(endpoint + "/v1/device/heartbeat", "POST", token, device.toString());
                lastHeartbeat = now;
            }
            String response = request(endpoint + "/v1/device/commands/next", "GET", token, null);
            getSharedPreferences("poco_bridge_inbox", MODE_PRIVATE).edit().remove("bridge_error").apply();
            JSONObject envelope = new JSONObject(response);
            JSONObject command = envelope.optJSONObject("command");
            if (command != null) {
                getSharedPreferences("poco_bridge_inbox", MODE_PRIVATE).edit()
                        .putString("pending_command", command.toString()).apply();
                updateNotification("یک فرمان جدید منتظر بررسی شماست");
            } else {
                updateNotification("متصل؛ در انتظار فرمان");
            }
        } catch (Exception e) {
            String detail = e.getClass().getSimpleName();
            String message = e.getMessage();
            if (message != null && !message.trim().isEmpty()) {
                message = message.replaceAll("[\\r\\n]+", " ").trim();
                if (message.length() > 110) message = message.substring(0, 110);
                detail += ": " + message;
            }
            getSharedPreferences("poco_bridge_inbox", MODE_PRIVATE).edit()
                    .putString("bridge_error", detail).apply();
            updateNotification("خطای اتصال: " + detail);
        }
    }

    private String request(String address, String method, String token, String body) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(12000);
        connection.setReadTimeout(12000);
        connection.setRequestProperty("Authorization", "Bearer " + token);
        connection.setRequestProperty("Accept", "application/json");
        if (body != null) {
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            try (OutputStream out = connection.getOutputStream()) {
                out.write(body.getBytes(StandardCharsets.UTF_8));
            }
        }
        int status = connection.getResponseCode();
        InputStream stream = status >= 200 && status < 300 ? connection.getInputStream() : connection.getErrorStream();
        StringBuilder result = new StringBuilder();
        if (stream != null) {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) result.append(line);
            }
        }
        connection.disconnect();
        if (status < 200 || status >= 300) throw new IllegalStateException("HTTP " + status + ": " + result);
        return result.toString();
    }

    private Notification buildNotification(String text) {
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("Poco Bridge")
                .setContentText(text)
                .setOngoing(true)
                .build();
    }

    private void updateNotification(String text) {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) manager.notify(72, buildNotification(text));
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(CHANNEL, "Poco Bridge",
                    NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("وضعیت ارتباط پوکو با پل امن");
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
    }

    @Override public void onDestroy() {
        pollingScheduled = false;
        if (executor != null) executor.shutdownNow();
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
