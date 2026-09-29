package com.mcaia.plugin.logging;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import okhttp3.*;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Logger;

/**
 * Sends log events to Discord webhooks.
 *
 * The admin webhook is configured in config.yml and sends event logs to server admins.
 */
public class WebhookLogger {

    private static final MediaType JSON_TYPE = MediaType.get("application/json; charset=utf-8");

    private final JavaPlugin    plugin;
    private final Logger        log;
    private final OkHttpClient  http;
    private final ExecutorService executor;

    private String       adminWebhookUrl = "";
    private List<String> enabledEvents   = List.of();

    public WebhookLogger(JavaPlugin plugin) {
        this.plugin   = plugin;
        this.log      = plugin.getLogger();
        this.http     = new OkHttpClient();
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "MCAIA-WebhookLogger");
            t.setDaemon(true);
            return t;
        });
    }

    public void initialize() {
        adminWebhookUrl = plugin.getConfig().getString("logging.admin-webhook-url", "");
        enabledEvents   = plugin.getConfig().getStringList("logging.log-events");
    }

    /** Reload webhook config values. */
    public void reload() { initialize(); }

    /** Shut down the executor cleanly. */
    public void shutdown() { executor.shutdownNow(); }

    // ── Public event methods ──────────────────────────────────────────────────

    public void logCommandUsed(String playerName, String prompt) {
        if (isEventEnabled("AI_COMMAND_USED")) {
            sendAdminEmbed("🤖 AI Command Used", 0x3498db,
                field("Player", playerName, true),
                field("Prompt", truncate(prompt, 300), false)
            );
        }
    }

    public void logCommandExecuted(String playerName, String command, boolean success, String error) {
        if (isEventEnabled("COMMAND_EXECUTED")) {
            int colour = success ? 0x2ecc71 : 0xe74c3c;
            String title = success ? "✅ Command Executed" : "❌ Command Failed";
            if (error != null) {
                sendAdminEmbed(title, colour,
                    field("Player",  playerName, true),
                    field("Command", "`" + command + "`", false),
                    field("Error",   truncate(error, 300), false)
                );
            } else {
                sendAdminEmbed(title, colour,
                    field("Player",  playerName, true),
                    field("Command", "`" + command + "`", false)
                );
            }
        }
    }

    public void logCommandRefused(String playerName, String reason) {
        if (isEventEnabled("COMMAND_REFUSED")) {
            sendAdminEmbed("🚫 Command Refused", 0xf39c12,
                field("Player", playerName, true),
                field("Reason", truncate(reason, 300), false)
            );
        }
    }

    public void logCommandBlocked(String playerName, String command) {
        if (isEventEnabled("COMMAND_BLOCKED")) {
            sendAdminEmbed("🔒 Blacklisted Command Blocked", 0xe74c3c,
                field("Player",  playerName, true),
                field("Command", "`" + command + "`", false)
            );
        }
    }

    public void logError(String context, String error) {
        if (isEventEnabled("ERRORS")) {
            sendAdminEmbed("⚠️ Plugin Error", 0xe74c3c,
                field("Context", context, false),
                field("Error",   truncate(error, 500), false)
            );
        }
    }

    public void logRateLimited(String playerName) {
        if (isEventEnabled("RATE_LIMITED")) {
            sendAdminEmbed("⏳ Rate Limited", 0x95a5a6,
                field("Player", playerName, true)
            );
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private boolean isEventEnabled(String event) {
        return !adminWebhookUrl.isBlank() && enabledEvents.contains(event);
    }

    @SafeVarargs
    private void sendAdminEmbed(String title, int colour, JsonObject... fields) {
        if (adminWebhookUrl.isBlank()) return;
        post(adminWebhookUrl, buildPayload(title, colour, fields));
    }

    private JsonObject buildPayload(String title, int colour, JsonObject[] fields) {
        JsonObject embed = new JsonObject();
        embed.addProperty("title", title);
        embed.addProperty("color", colour);
        if (fields.length > 0) {
            JsonArray arr = new JsonArray();
            for (JsonObject f : fields) arr.add(f);
            embed.add("fields", arr);
        }
        JsonArray embeds = new JsonArray();
        embeds.add(embed);
        JsonObject payload = new JsonObject();
        payload.add("embeds", embeds);
        return payload;
    }

    private JsonObject field(String name, String value, boolean inline) {
        JsonObject f = new JsonObject();
        f.addProperty("name",   name);
        f.addProperty("value",  value.isBlank() ? "—" : value);
        f.addProperty("inline", inline);
        return f;
    }

    private void post(String url, JsonObject payload) {
        executor.submit(() -> {
            RequestBody body = RequestBody.create(payload.toString(), JSON_TYPE);
            Request req = new Request.Builder().url(url).post(body).build();
            try (Response resp = http.newCall(req).execute()) {
                if (!resp.isSuccessful() && resp.code() != 204) {
                    log.warning("[MCAIA] Webhook POST failed: HTTP " + resp.code());
                }
            } catch (IOException e) {
                log.warning("[MCAIA] Webhook POST error: " + e.getMessage());
            }
        });
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) + "…" : s;
    }
}
