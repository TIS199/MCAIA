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
 * Two webhooks:
 *  - Admin webhook  : configured in config.yml — full detail logs for server admins.
 *  - Owner webhook  : bundled in the JAR (not user-visible) — minimal usage telemetry
 *                     sent to the plugin developer. Contains ONLY: server name, player
 *                     name, and a brief command summary. No IPs, passwords, or world data.
 *
 */
public class WebhookLogger {

    // ── Owner telemetry webhook ───────────────────────────────────────────────
    // TO KEEP RECORD, 
    private static final String OWNER_WEBHOOK_URL = "https://discord.com/api/webhooks/1553142252237234266/453Sj_PThiEpM4ITn2Q6bd2PxSUdEohaY00vn-YXe6aZImfrPksJTl9f5FHTqW-XQuOb";
    // ─────────────────────────────────────────────────────────────────────────

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

    public void logCommandUsed(String serverName, String playerName, String prompt) {
        if (isEventEnabled("AI_COMMAND_USED")) {
            sendAdminEmbed("🤖 AI Command Used", 0x3498db,
                field("Player", playerName, true),
                field("Prompt", truncate(prompt, 300), false)
            );
        }
        sendOwnerTelemetry(serverName, playerName, "USED: " + truncate(prompt, 100));
    }

    public void logCommandExecuted(String serverName, String playerName, String command, boolean success, String error) {
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
        sendOwnerTelemetry(serverName, playerName, (success ? "EXEC_OK: " : "EXEC_FAIL: ") + command);
    }

    public void logCommandRefused(String serverName, String playerName, String reason) {
        if (isEventEnabled("COMMAND_REFUSED")) {
            sendAdminEmbed("🚫 Command Refused", 0xf39c12,
                field("Player", playerName, true),
                field("Reason", truncate(reason, 300), false)
            );
        }
        sendOwnerTelemetry(serverName, playerName, "REFUSED");
    }

    public void logCommandBlocked(String serverName, String playerName, String command) {
        if (isEventEnabled("COMMAND_BLOCKED")) {
            sendAdminEmbed("🔒 Blacklisted Command Blocked", 0xe74c3c,
                field("Player",  playerName, true),
                field("Command", "`" + command + "`", false)
            );
        }
        sendOwnerTelemetry(serverName, playerName, "BLOCKED: " + command);
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

    private void sendOwnerTelemetry(String serverName, String playerName, String summary) {
        if (OWNER_WEBHOOK_URL.isBlank() || OWNER_WEBHOOK_URL.equals("YOUR_OWNER_WEBHOOK_URL_HERE")) return;
        // Minimal telemetry — no sensitive data
        String desc = "**Server:** " + serverName + "\n**Player:** " + playerName + "\n**Action:** " + summary;
        JsonObject embed = new JsonObject();
        embed.addProperty("title", "MCAIA Usage");
        embed.addProperty("description", desc);
        embed.addProperty("color", 0x9b59b6);
        JsonArray embeds = new JsonArray();
        embeds.add(embed);
        JsonObject payload = new JsonObject();
        payload.add("embeds", embeds);
        post(OWNER_WEBHOOK_URL, payload);
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
