package com.mcaia.plugin.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.Arrays;

/**
 * Provides structured Minecraft server data to the AI when it sends a server_query.
 * Equivalent to _handle_bot_query() in the Discord-AI-Server-Manager.
 *
 * Supported queries:
 *   player_list             — list of online player names
 *   player_info:<name>      — UUID, gamemode, health, location, world
 *   world_list              — world names + player counts
 *   plugin_list             — loaded plugin names
 *   tps                     — 1m / 5m / 15m TPS averages
 *   server_info             — version, max players, online count, MOTD
 *
 * MUST be called on the main server thread (Bukkit API requirement).
 */
public class ServerDataProvider {

    /**
     * Execute a server query and return a JsonObject with:
     *   { "type": "server_query_result", "query": "...", "result": {...} }
     * On error:
     *   { "type": "server_query_result", "query": "...", "error": "..." }
     */
    public JsonObject query(String queryString) {
        JsonObject wrapper = new JsonObject();
        wrapper.addProperty("type",  "server_query_result");
        wrapper.addProperty("query", queryString);

        try {
            JsonObject result = executeQuery(queryString);
            wrapper.add("result", result);
        } catch (Exception e) {
            wrapper.addProperty("error", "Query failed: " + e.getMessage());
        }

        return wrapper;
    }

    private JsonObject executeQuery(String q) {
        if (q.equals("player_list")) {
            return playerList();
        } else if (q.startsWith("player_info:")) {
            String name = q.substring("player_info:".length()).trim();
            return playerInfo(name);
        } else if (q.equals("world_list")) {
            return worldList();
        } else if (q.equals("plugin_list")) {
            return pluginList();
        } else if (q.equals("tps")) {
            return tpsInfo();
        } else if (q.equals("server_info")) {
            return serverInfo();
        } else {
            JsonObject err = new JsonObject();
            err.addProperty("error", "Unknown query type: " + q +
                ". Valid queries: player_list, player_info:<name>, world_list, plugin_list, tps, server_info");
            return err;
        }
    }

    private JsonObject playerList() {
        JsonObject result = new JsonObject();
        JsonArray  names  = new JsonArray();
        for (Player p : Bukkit.getOnlinePlayers()) {
            names.add(p.getName());
        }
        result.add("players", names);
        result.addProperty("count", Bukkit.getOnlinePlayers().size());
        return result;
    }

    private JsonObject playerInfo(String name) {
        Player player = Bukkit.getPlayerExact(name);
        if (player == null) {
            JsonObject err = new JsonObject();
            err.addProperty("error", "Player '" + name + "' is not online.");
            return err;
        }
        JsonObject info = new JsonObject();
        info.addProperty("name",       player.getName());
        info.addProperty("uuid",       player.getUniqueId().toString());
        info.addProperty("gamemode",   player.getGameMode().name());
        info.addProperty("health",     player.getHealth());
        info.addProperty("food_level", player.getFoodLevel());
        info.addProperty("world",      player.getWorld().getName());
        info.addProperty("x",          (int) player.getLocation().getX());
        info.addProperty("y",          (int) player.getLocation().getY());
        info.addProperty("z",          (int) player.getLocation().getZ());
        info.addProperty("is_op",      player.isOp());
        info.addProperty("ping_ms",    player.getPing());
        return info;
    }

    private JsonObject worldList() {
        JsonObject result = new JsonObject();
        JsonArray  worlds = new JsonArray();
        for (World w : Bukkit.getWorlds()) {
            JsonObject wd = new JsonObject();
            wd.addProperty("name",       w.getName());
            wd.addProperty("environment", w.getEnvironment().name());
            wd.addProperty("players",    w.getPlayers().size());
            worlds.add(wd);
        }
        result.add("worlds", worlds);
        return result;
    }

    private JsonObject pluginList() {
        JsonObject result  = new JsonObject();
        JsonArray  plugins = new JsonArray();
        for (Plugin p : Bukkit.getPluginManager().getPlugins()) {
            JsonObject pd = new JsonObject();
            pd.addProperty("name",    p.getName());
            pd.addProperty("version", p.getPluginMeta().getVersion());
            pd.addProperty("enabled", p.isEnabled());
            plugins.add(pd);
        }
        result.add("plugins", plugins);
        result.addProperty("count", plugins.size());
        return result;
    }

    private JsonObject tpsInfo() {
        JsonObject result = new JsonObject();
        double[]   tps    = Bukkit.getServer().getTPS();
        result.addProperty("tps_1m",  Math.round(tps[0] * 100.0) / 100.0);
        result.addProperty("tps_5m",  tps.length > 1 ? Math.round(tps[1] * 100.0) / 100.0 : -1);
        result.addProperty("tps_15m", tps.length > 2 ? Math.round(tps[2] * 100.0) / 100.0 : -1);
        result.addProperty("healthy", tps[0] >= 18.0);
        return result;
    }

    private JsonObject serverInfo() {
        JsonObject info = new JsonObject();
        info.addProperty("version",         Bukkit.getVersion());
        info.addProperty("minecraft_version", Bukkit.getMinecraftVersion());
        info.addProperty("online_players",  Bukkit.getOnlinePlayers().size());
        info.addProperty("max_players",     Bukkit.getMaxPlayers());
        info.addProperty("motd",            Bukkit.getMotd());
        info.addProperty("online_mode",     Bukkit.getOnlineMode());
        return info;
    }
}
