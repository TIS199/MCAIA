package com.mcaia.plugin.util;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class UpdateChecker {

    private static final long CHECK_INTERVAL_TICKS = 24L * 60L * 60L * 20L;
    private static final String GITHUB_RELEASES_API =
            "https://api.github.com/repos/TIS199/MCAIA/releases?per_page=100";
    private static final String HANGAR_VERSIONS_API =
            "https://hangar.papermc.io/api/v1/projects/TIS199/MCAIA/versions?platform=PAPER&limit=100";
    private static final Pattern VERSION_PATTERN = Pattern.compile(
            "^[vV]?(\\d+)\\.(\\d+)\\.(\\d+)(?:-([0-9A-Za-z.-]+))?(?:\\+[0-9A-Za-z.-]+)?$");

    private final JavaPlugin plugin;
    private final String installedVersion;
    private final SemanticVersion installed;
    private final OkHttpClient http = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.SECONDS)
            .build();

    private volatile Map<Source, AvailableUpdate> availableUpdates = Map.of();

    public UpdateChecker(JavaPlugin plugin) {
        this.plugin = plugin;
        this.installedVersion = Objects.requireNonNull(
                plugin.getPluginMeta().getVersion(), "Plugin version must be configured");
        this.installed = SemanticVersion.parse(installedVersion)
                .orElseThrow(() -> new IllegalStateException(
                        "Plugin version is not a supported semantic version: " + installedVersion));
    }

    public void start() {
        plugin.getServer().getScheduler().runTaskTimerAsynchronously(
                plugin, this::checkSources, 1L, CHECK_INTERVAL_TICKS);
    }

    public void notifyIfAvailable(Player player) {
        if (!player.hasPermission("mcaia.admin")) {
            return;
        }

        availableUpdates.values().stream()
                .sorted(Comparator.comparing(update -> update.source().ordinal()))
                .forEach(update -> sendReminder(player, update));
    }

    private void checkSources() {
        EnumMap<Source, Optional<AvailableUpdate>> results = new EnumMap<>(Source.class);
        List<Failure> failures = new ArrayList<>();

        check(Source.GITHUB, this::findGitHubUpdate, results, failures);
        check(Source.HANGAR, this::findHangarUpdate, results, failures);

        plugin.getServer().getScheduler().runTask(plugin, () -> publish(results, failures));
    }

    private void check(Source source, UpdateLookup lookup,
                       EnumMap<Source, Optional<AvailableUpdate>> results,
                       List<Failure> failures) {
        try {
            results.put(source, lookup.find());
        } catch (IOException | JsonParseException | IllegalStateException e) {
            failures.add(new Failure(source, e));
        }
    }

    private Optional<AvailableUpdate> findGitHubUpdate() throws IOException {
        JsonElement response = requestJson(GITHUB_RELEASES_API, true);
        if (!response.isJsonArray()) {
            throw new JsonParseException("GitHub releases response was not an array");
        }

        AvailableUpdate latest = null;
        for (JsonElement element : response.getAsJsonArray()) {
            if (!element.isJsonObject()) {
                throw new JsonParseException("GitHub release entry was not an object");
            }

            JsonObject release = element.getAsJsonObject();
            String tag = requiredString(release, "tag_name", "GitHub release");
            SemanticVersion parsed = parseRequiredVersion(tag, "GitHub release");
            boolean prerelease = release.has("prerelease") && release.get("prerelease").getAsBoolean();
            String url = requiredString(release, "html_url", "GitHub release");

            AvailableUpdate candidate = new AvailableUpdate(Source.GITHUB, tag, url, prerelease, parsed);
            if (latest == null || candidate.version().compareTo(latest.version()) > 0) {
                latest = candidate;
            }
        }

        return newerThanInstalled(latest);
    }

    private Optional<AvailableUpdate> findHangarUpdate() throws IOException {
        JsonElement response = requestJson(HANGAR_VERSIONS_API, false);
        if (!response.isJsonObject()) {
            throw new JsonParseException("Hangar versions response was not an object");
        }

        JsonElement result = response.getAsJsonObject().get("result");
        if (result == null || !result.isJsonArray()) {
            throw new JsonParseException("Hangar versions response did not contain a result array");
        }

        AvailableUpdate latest = null;
        for (JsonElement element : result.getAsJsonArray()) {
            if (!element.isJsonObject()) {
                throw new JsonParseException("Hangar version entry was not an object");
            }

            String version = requiredString(element.getAsJsonObject(), "name", "Hangar version");
            SemanticVersion parsed = parseRequiredVersion(version, "Hangar version");
            String url = "https://hangar.papermc.io/TIS199/MCAIA/versions/" + version;

            AvailableUpdate candidate = new AvailableUpdate(Source.HANGAR, version, url, false, parsed);
            if (latest == null || candidate.version().compareTo(latest.version()) > 0) {
                latest = candidate;
            }
        }

        return newerThanInstalled(latest);
    }

    private JsonElement requestJson(String url, boolean github) throws IOException {
        Request.Builder request = new Request.Builder()
                .url(url)
                .header("User-Agent", "MCAIA/" + installedVersion)
                .get();
        if (github) {
            request.header("Accept", "application/vnd.github+json");
        }

        try (Response response = http.newCall(request.build()).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Request to " + (github ? "GitHub" : "Hangar")
                        + " returned HTTP " + response.code());
            }

            ResponseBody body = response.body();
            if (body == null) {
                throw new IOException("Empty response from " + (github ? "GitHub" : "Hangar"));
            }
            return JsonParser.parseString(body.string());
        }
    }

    private Optional<AvailableUpdate> newerThanInstalled(AvailableUpdate update) {
        if (update == null || update.version().compareTo(installed) <= 0) {
            return Optional.empty();
        }
        return Optional.of(update);
    }

    private SemanticVersion parseRequiredVersion(String version, String source) {
        return SemanticVersion.parse(version)
                .orElseThrow(() -> new JsonParseException(
                        source + " version is not a supported semantic version: " + version));
    }

    private String requiredString(JsonObject object, String key, String source) {
        JsonElement value = object.get(key);
        if (value == null || !value.isJsonPrimitive()) {
            throw new JsonParseException(source + " response is missing " + key);
        }
        return value.getAsString();
    }

    private void publish(Map<Source, Optional<AvailableUpdate>> results, List<Failure> failures) {
        EnumMap<Source, AvailableUpdate> next = new EnumMap<>(Source.class);
        next.putAll(availableUpdates);
        List<AvailableUpdate> newlyAvailable = new ArrayList<>();

        results.forEach((source, result) -> {
            AvailableUpdate previous = next.get(source);
            if (result.isPresent()) {
                AvailableUpdate update = result.get();
                next.put(source, update);
                if (previous == null || !previous.versionName().equals(update.versionName())) {
                    newlyAvailable.add(update);
                }
            } else {
                next.remove(source);
            }
        });
        availableUpdates = Map.copyOf(next);

        for (Failure failure : failures) {
            plugin.getLogger().log(Level.WARNING,
                    "Could not check MCAIA updates on " + failure.source().displayName() + ".",
                    failure.cause());
        }

        for (AvailableUpdate update : newlyAvailable) {
            plugin.getLogger().warning("MCAIA update available: " + installedVersion + " -> "
                    + update.versionName() + " on " + update.source().displayName()
                    + ". Download: " + update.url());
            for (Player player : plugin.getServer().getOnlinePlayers()) {
                if (player != null && player.hasPermission("mcaia.admin")) {
                    sendReminder(player, update);
                }
            }
        }
    }

    private void sendReminder(Player player, AvailableUpdate update) {
        String versionLabel = update.versionName() + (update.prerelease() ? " (pre-release)" : "");
        Component message = Component.text("MCAIA update available: ", NamedTextColor.GOLD)
                .append(Component.text(Objects.requireNonNull(installedVersion), NamedTextColor.WHITE))
                .append(Component.text(" -> ", NamedTextColor.GRAY))
                .append(Component.text(versionLabel, NamedTextColor.GREEN))
                .append(Component.text(" [", NamedTextColor.GRAY))
                .append(Component.text(Objects.requireNonNull(update.source().linkText()), NamedTextColor.AQUA)
                        .clickEvent(ClickEvent.openUrl(Objects.requireNonNull(update.url()))))
                .append(Component.text("]", NamedTextColor.GRAY));
        player.sendMessage(message);
    }

    private enum Source {
        GITHUB("GitHub Releases", "GitHub"),
        HANGAR("Hangar", "Hangar");

        private final String displayName;
        private final String linkText;

        Source(String displayName, String linkText) {
            this.displayName = displayName;
            this.linkText = linkText;
        }

        String displayName() {
            return displayName;
        }

        String linkText() {
            return linkText;
        }
    }

    private record AvailableUpdate(Source source, String versionName, String url,
                                   boolean prerelease, SemanticVersion version) {}

    private record Failure(Source source, Exception cause) {}

    @FunctionalInterface
    private interface UpdateLookup {
        Optional<AvailableUpdate> find() throws IOException;
    }

    private record SemanticVersion(BigInteger major, BigInteger minor, BigInteger patch,
                                   List<String> prerelease) implements Comparable<SemanticVersion> {

        static Optional<SemanticVersion> parse(String value) {
            Matcher matcher = VERSION_PATTERN.matcher(value);
            if (!matcher.matches()) {
                return Optional.empty();
            }

            List<String> prerelease = matcher.group(4) == null
                    ? List.of()
                    : List.of(matcher.group(4).split("\\."));
            return Optional.of(new SemanticVersion(
                    new BigInteger(matcher.group(1)),
                    new BigInteger(matcher.group(2)),
                    new BigInteger(matcher.group(3)),
                    prerelease));
        }

        @Override
        public int compareTo(SemanticVersion other) {
            int comparison = major.compareTo(other.major);
            if (comparison == 0) comparison = minor.compareTo(other.minor);
            if (comparison == 0) comparison = patch.compareTo(other.patch);
            if (comparison != 0) return comparison;

            if (prerelease.isEmpty()) return other.prerelease.isEmpty() ? 0 : 1;
            if (other.prerelease.isEmpty()) return -1;

            int count = Math.min(prerelease.size(), other.prerelease.size());
            for (int index = 0; index < count; index++) {
                comparison = comparePrereleaseIdentifier(prerelease.get(index), other.prerelease.get(index));
                if (comparison != 0) return comparison;
            }
            return Integer.compare(prerelease.size(), other.prerelease.size());
        }

        private int comparePrereleaseIdentifier(String left, String right) {
            boolean leftNumeric = left.matches("\\d+");
            boolean rightNumeric = right.matches("\\d+");

            if (leftNumeric && rightNumeric) {
                return new BigInteger(left).compareTo(new BigInteger(right));
            }
            if (leftNumeric != rightNumeric) {
                return leftNumeric ? -1 : 1;
            }
            return left.compareTo(right);
        }
    }
}
