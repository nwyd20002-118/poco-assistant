package ir.poco.assistant;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public final class NotificationStore {
    private static final String PREFS = "poco_local_notification_store";
    private static final String KEY_ROWS = "telegram_rows";
    private static final int MAX_ROWS = 30;

    private NotificationStore() {}

    public static synchronized void add(Context context, String packageName,
                                         String title, String text, long time) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        JSONArray oldRows = read(context);
        JSONArray newRows = new JSONArray();
        try {
            JSONObject row = new JSONObject();
            row.put("package", packageName);
            row.put("title", title);
            row.put("text", text);
            row.put("time", time);
            newRows.put(row);
            for (int i = 0; i < oldRows.length() && i < MAX_ROWS - 1; i++) {
                JSONObject oldRow = oldRows.optJSONObject(i);
                if (oldRow != null) newRows.put(oldRow);
            }
            prefs.edit().putString(KEY_ROWS, newRows.toString()).apply();
        } catch (JSONException ignored) {
        }
    }

    public static synchronized void recordListenerState(Context context, boolean connected) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean("listener_connected", connected).apply();
    }

    public static synchronized void recordTelegramCallback(Context context, String packageName) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        prefs.edit()
                .putInt("telegram_callback_count", prefs.getInt("telegram_callback_count", 0) + 1)
                .putString("last_callback_package", packageName)
                .putLong("last_callback_time", System.currentTimeMillis())
                .apply();
    }

    public static synchronized String diagnostics(Context context) {
        SharedPreferences p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long time = p.getLong("last_callback_time", 0L);
        String last = time == 0L ? "هنوز هیچ اعلان تلگرامی به سرویس نرسیده"
                : android.text.format.DateFormat.getTimeFormat(context).format(new java.util.Date(time));
        return "اتصال سرویس: " + (p.getBoolean("listener_connected", false) ? "وصل" : "قطع/نامشخص")
                + "\nتعداد اعلان‌های دریافتی تلگرام: " + p.getInt("telegram_callback_count", 0)
                + "\nآخرین بسته: " + p.getString("last_callback_package", "—")
                + "\nزمان آخرین دریافت: " + last + "\n\n";
    }

    public static synchronized JSONArray read(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String raw = prefs.getString(KEY_ROWS, "[]");
        try {
            return new JSONArray(raw);
        } catch (JSONException e) {
            return new JSONArray();
        }
    }

    public static synchronized void clear(Context context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().remove(KEY_ROWS).apply();
    }
}
