package com.mcaia.plugin.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mcaia.plugin.MCAIAPlugin;
import com.mcaia.plugin.util.FoliaTasks;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/** Server snapshots scheduled on the global region or the owning player region. */
public final class ServerDataProvider {

    public record PlayerRef(UUID uuid, String name, Player player) {}

    private final MCAIAPlugin plugin;
    private final Map<UUID, PlayerRef> playersById = new ConcurrentHashMap<>();
    private final Map<String, UUID> playersByName = new ConcurrentHashMap<>();

    public ServerDataProvider(MCAIAPlugin plugin) {
        this.plugin = plugin;
    }

    /** Called from PlayerJoinEvent on the player's owning region. */
    public void playerJoined(Player player) {
        PlayerRef ref = new PlayerRef(player.getUniqueId(), plugin.getPluginCompat().getCleanName(player), player);
        playersById.put(ref.uuid(), ref);
        playersByName.put(ref.name().toLowerCase(Locale.ROOT), ref.uuid());
    }

    /** Called from PlayerQuitEvent on the player's owning region. */
    public void playerQuit(Player player) {
        PlayerRef ref = playersById.remove(player.getUniqueId());
        if (ref != null) playersByName.remove(ref.name().toLowerCase(Locale.ROOT), ref.uuid());
    }

    public List<PlayerRef> playersSnapshot() {
        return List.copyOf(playersById.values());
    }

    public List<String> onlinePlayerNames() {
        return playersById.values().stream().map(PlayerRef::name).sorted(String.CASE_INSENSITIVE_ORDER).toList();
    }

    public int onlinePlayerCount() {
        return playersById.size();
    }

    public UUID findOnlinePlayerUuid(String name) {
        return name == null ? null : playersByName.get(name.toLowerCase(Locale.ROOT));
    }

    /** Global-owned server context; never reads entity state here. */
    public void initialContext(Consumer<JsonObject> callback) {
        FoliaTasks.global(plugin, () -> {
            JsonObject context = new JsonObject();
            context.addProperty("online_players", onlinePlayerCount());
            double[] tps = Bukkit.getTPS();
            context.addProperty("tps_1m", tps.length == 0 ? -1 : Math.round(tps[0] * 10.0) / 10.0);
            context.addProperty("server_version", Bukkit.getMinecraftVersion());
            callback.accept(context);
        });
    }

    public void query(String queryString, Consumer<JsonObject> callback) {
        if ("player_list".equals(queryString)) {
            JsonObject result = new JsonObject();
            JsonArray names = new JsonArray();
            onlinePlayerNames().forEach(names::add);
            result.add("players", names);
            result.addProperty("count", names.size());
            callback.accept(wrap(queryString, result));
            return;
        }
        if (queryString != null && queryString.startsWith("player_info:")) {
            String name = queryString.substring("player_info:".length()).trim();
            UUID uuid = findOnlinePlayerUuid(name);
            PlayerRef ref = uuid == null ? null : playersById.get(uuid);
            if (ref == null) {
                callback.accept(error(queryString, "Player '" + name + "' is not online."));
                return;
            }
            FoliaTasks.entity(plugin, ref.player(), () -> {
                if (!ref.player().isOnline()) {
                    callback.accept(error(queryString, "Player '" + name + "' is no longer online."));
                    return;
                }
                JsonObject info = new JsonObject();
                info.addProperty("name", ref.name());
                info.addProperty("uuid", ref.player().getUniqueId().toString());
                info.addProperty("gamemode", ref.player().getGameMode().name());
                info.addProperty("health", ref.player().getHealth());
                info.addProperty("food_level", ref.player().getFoodLevel());
                info.addProperty("world", ref.player().getWorld().getName());
                info.addProperty("x", (int) ref.player().getLocation().getX());
                info.addProperty("y", (int) ref.player().getLocation().getY());
                info.addProperty("z", (int) ref.player().getLocation().getZ());
                info.addProperty("is_op", ref.player().isOp());
                info.addProperty("ping_ms", ref.player().getPing());
                callback.accept(wrap(queryString, info));
            }, () -> callback.accept(error(queryString, "Player '" + name + "' is no longer online.")));
            return;
        }
        if ("world_list".equals(queryString)) {
            worldList(queryString, callback);
            return;
        }
        FoliaTasks.global(plugin, () -> callback.accept(queryOnGlobal(queryString)));
    }

    private void worldList(String query, Consumer<JsonObject> callback) {
        FoliaTasks.global(plugin, () -> {
            List<String> worlds = Bukkit.getWorlds().stream().map(World::getName).sorted().toList();
            Map<String, String> environments = new ConcurrentHashMap<>();
            for (World world : Bukkit.getWorlds()) {
                environments.put(world.getName(), world.getEnvironment().name());
            }
            List<PlayerRef> snapshot = playersSnapshot();
            Map<String, AtomicInteger> counts = new ConcurrentHashMap<>();
            if (snapshot.isEmpty()) {
                callback.accept(buildWorldResult(query, worlds, environments, counts));
                return;
            }
            AtomicInteger remaining = new AtomicInteger(snapshot.size());
            Runnable completeOne = () -> {
                if (remaining.decrementAndGet() == 0) {
                    callback.accept(buildWorldResult(query, worlds, environments, counts));
                }
            };
            for (PlayerRef ref : snapshot) {
                FoliaTasks.entity(plugin, ref.player(), () -> {
                    if (ref.player().isOnline()) {
                        counts.computeIfAbsent(ref.player().getWorld().getName(), ignored -> new AtomicInteger())
                                .incrementAndGet();
                    }
                    completeOne.run();
                }, completeOne);
            }
        });
    }

    private JsonObject buildWorldResult(String query, List<String> worlds,
                                        Map<String, String> environments,
                                        Map<String, AtomicInteger> counts) {
        JsonObject result = new JsonObject();
        JsonArray worldArray = new JsonArray();
        for (String name : worlds) {
            JsonObject world = new JsonObject();
            world.addProperty("name", name);
            world.addProperty("environment", environments.getOrDefault(name, "UNKNOWN"));
            AtomicInteger count = counts.get(name);
            world.addProperty("players", count == null ? 0 : count.get());
            worldArray.add(world);
        }
        result.add("worlds", worldArray);
        return wrap(query, result);
    }

    private JsonObject queryOnGlobal(String query) {
        JsonObject result = new JsonObject();
        if ("plugin_list".equals(query)) {
            JsonArray plugins = new JsonArray();
            for (Plugin found : Bukkit.getPluginManager().getPlugins()) {
                JsonObject item = new JsonObject();
                item.addProperty("name", found.getName());
                item.addProperty("version", found.getPluginMeta().getVersion());
                item.addProperty("enabled", found.isEnabled());
                plugins.add(item);
            }
            result.add("plugins", plugins);
            result.addProperty("count", plugins.size());
        } else if ("tps".equals(query)) {
            double[] tps = Bukkit.getTPS();
            result.addProperty("tps_1m", tps.length > 0 ? Math.round(tps[0] * 100.0) / 100.0 : -1);
            result.addProperty("tps_5m", tps.length > 1 ? Math.round(tps[1] * 100.0) / 100.0 : -1);
            result.addProperty("tps_15m", tps.length > 2 ? Math.round(tps[2] * 100.0) / 100.0 : -1);
            result.addProperty("healthy", tps.length > 0 && tps[0] >= 18.0);
        } else if ("server_info".equals(query)) {
            result.addProperty("version", Bukkit.getVersion());
            result.addProperty("minecraft_version", Bukkit.getMinecraftVersion());
            result.addProperty("online_players", onlinePlayerCount());
            result.addProperty("max_players", Bukkit.getMaxPlayers());
            result.addProperty("motd", Bukkit.getMotd());
            result.addProperty("online_mode", Bukkit.getOnlineMode());
        } else {
            result.addProperty("error", "Unknown query type: " + query
                    + ". Valid queries: player_list, player_info:<name>, world_list, plugin_list, tps, server_info");
        }
        return wrap(query, result);
    }

    private JsonObject error(String query, String message) {
        JsonObject result = new JsonObject();
        result.addProperty("error", message);
        return wrap(query, result);
    }

    private JsonObject wrap(String query, JsonObject result) {
        JsonObject wrapper = new JsonObject();
        wrapper.addProperty("type", "server_query_result");
        wrapper.addProperty("query", query == null ? "" : query);
        wrapper.add("result", result);
        return wrapper;
    }
}
