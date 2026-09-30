package com.mcaia.plugin.ai;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Checks command text against configured bans and commands unsafe to dispatch.
 */
public final class CommandGuard {

    private static final int MAX_WRAPPER_DEPTH = 32;
    private static final Set<String> ALWAYS_BLOCKED = Set.of(
            "stop", "restart", "restartserver", "reload", "rl", "reloadconfirm",
            "op", "deop", "whitelist", "ban-ip", "pardon-ip",
            "lp", "luckperms", "pex", "permissions",
            "plugman", "plugmanx", "pluginmanager", "function", "schedule", "sudo"
    );

    private CommandGuard() {
    }

    /**
     * Returns the blocked root label (or a malformed-wrapper reason), or {@code null} if safe.
     */
    public static String findBlockedCommand(String command, List<String> configuredBans) {
        Set<String> bans = new HashSet<>();
        if (configuredBans != null) {
            for (String configuredBan : configuredBans) {
                List<String> banTokens = tokenize(normalize(configuredBan));
                if (banTokens != null && !banTokens.isEmpty()) {
                    bans.add(rootLabel(banTokens.get(0)));
                }
            }
        }
        return inspect(command, bans, 0);
    }

    public static List<String> hardBlockedCommands() {
        return ALWAYS_BLOCKED.stream().sorted().collect(Collectors.toUnmodifiableList());
    }

    private static String inspect(String command, Set<String> bans, int depth) {
        if (depth >= MAX_WRAPPER_DEPTH) {
            return "malformed wrapper";
        }
        if (containsCommandSeparator(command)) {
            return "chained commands are not allowed";
        }

        List<String> tokens = tokenize(normalize(command));
        if (tokens == null || tokens.isEmpty()) {
            return "malformed command";
        }

        String root = rootLabel(tokens.get(0));
        if (root.isEmpty()) {
            return "malformed command";
        }
        tokens.set(0, root);

        if (ALWAYS_BLOCKED.contains(root) || bans.contains(root)) {
            return root;
        }

        if (root.equals("execute")) {
            int runIndex = -1;
            for (int i = 1; i < tokens.size(); i++) {
                if (rootLabel(tokens.get(i)).equals("run")) {
                    runIndex = i;
                    break;
                }
            }
            if (runIndex < 0 || runIndex + 1 >= tokens.size()) {
                return "malformed execute";
            }
            return inspect(join(tokens, runIndex + 1), bans, depth + 1);
        }

        if (root.equals("sudo")) {
            return "sudo wrapper";
        }

        if (root.equals("cmi") && tokens.size() > 1
                && rootLabel(tokens.get(1)).equals("sudo")) {
            return "cmi sudo wrapper";
        }

        return null;
    }

    private static boolean containsCommandSeparator(String command) {
        char quote = 0;
        boolean escaping = false;
        for (int i = 0; i < command.length(); i++) {
            char c = command.charAt(i);
            if (escaping) {
                escaping = false;
                continue;
            }
            if (c == '\\' && quote != 0) {
                escaping = true;
                continue;
            }
            if (quote != 0) {
                if (c == quote) quote = 0;
                continue;
            }
            if (c == '"' || c == '\'') {
                quote = c;
            } else if (c == ';' || c == '\n' || c == '\r' || c == '|'
                    || (c == '&' && i + 1 < command.length() && command.charAt(i + 1) == '&')) {
                return true;
            }
        }
        return quote != 0 || escaping;
    }

    private static String normalize(String command) {
        if (command == null) {
            return "";
        }

        String text = Normalizer.normalize(command, Normalizer.Form.NFKC);
        text = stripFormatting(text);
        StringBuilder normalized = new StringBuilder(text.length());
        boolean pendingSpace = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c) || Character.isSpaceChar(c)) {
                pendingSpace = normalized.length() > 0;
                continue;
            }
            if (pendingSpace) {
                normalized.append(' ');
                pendingSpace = false;
            }
            normalized.append(mapHomoglyph(Character.toLowerCase(c)));
        }
        return normalized.toString();
    }

    private static String stripFormatting(String text) {
        StringBuilder result = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if ((c == '\u00a7' || c == '&') && i + 1 < text.length()
                    && isLegacyFormatCode(text.charAt(i + 1))) {
                i++;
                continue;
            }
            if (c == '<') {
                int end = text.indexOf('>', i + 1);
                if (end >= 0) {
                    i = end;
                    continue;
                }
            }
            result.append(c);
        }
        return result.toString();
    }

    private static boolean isLegacyFormatCode(char c) {
        char lower = Character.toLowerCase(c);
        return (lower >= '0' && lower <= '9')
                || (lower >= 'a' && lower <= 'f')
                || "klmnorx".indexOf(lower) >= 0;
    }

    private static char mapHomoglyph(char c) {
        return switch (c) {
            case '\u0430' -> 'a'; // а
            case '\u0432' -> 'b'; // в
            case '\u0441' -> 'c'; // с
            case '\u0435' -> 'e'; // е
            case '\u043d' -> 'h'; // н
            case '\u0456' -> 'i'; // і
            case '\u0458' -> 'j'; // ј
            case '\u043a' -> 'k'; // к
            case '\u043c' -> 'm'; // м
            case '\u043e' -> 'o'; // о
            case '\u0440' -> 'p'; // р
            case '\u0455' -> 's'; // ѕ
            case '\u0442' -> 't'; // т
            case '\u0443' -> 'y'; // у
            case '\u0445' -> 'x'; // х
            default -> c;
        };
    }

    private static List<String> tokenize(String command) {
        List<String> tokens = new ArrayList<>();
        StringBuilder token = new StringBuilder();
        char quote = 0;
        boolean escaping = false;

        for (int i = 0; i < command.length(); i++) {
            char c = command.charAt(i);
            if (escaping) {
                token.append(c);
                escaping = false;
            } else if (c == '\\' && quote != 0) {
                escaping = true;
            } else if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                } else {
                    token.append(c);
                }
            } else if (c == '"' || c == '\'') {
                quote = c;
            } else if (Character.isWhitespace(c) || Character.isSpaceChar(c)) {
                addToken(tokens, token);
            } else {
                token.append(c);
            }
        }
        if (escaping || quote != 0) {
            return null;
        }
        addToken(tokens, token);
        return tokens;
    }

    private static void addToken(List<String> tokens, StringBuilder token) {
        if (token.length() > 0) {
            tokens.add(token.toString());
            token.setLength(0);
        }
    }

    private static String rootLabel(String token) {
        if (token == null) {
            return "";
        }
        String label = normalize(token);
        while (label.startsWith("/")) {
            label = label.substring(1);
        }
        int colon = label.lastIndexOf(':');
        if (colon > 0 && isNamespace(label.substring(0, colon))) {
            label = label.substring(colon + 1);
        }
        return label.toLowerCase(Locale.ROOT);
    }

    private static boolean isNamespace(String value) {
        if (value.isEmpty()) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '_' || c == '-' || c == '.')) {
                return false;
            }
        }
        return true;
    }

    private static String join(List<String> tokens, int start) {
        StringBuilder command = new StringBuilder();
        for (int i = start; i < tokens.size(); i++) {
            if (command.length() > 0) {
                command.append(' ');
            }
            command.append(tokens.get(i));
        }
        return command.toString();
    }
}
