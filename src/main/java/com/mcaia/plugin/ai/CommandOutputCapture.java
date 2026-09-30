package com.mcaia.plugin.ai;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.jetbrains.annotations.NotNull;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;

import java.util.Objects;

/**
 * Creates Paper's vanilla-compatible console-level sender and captures its feedback.
 */
final class CommandOutputCapture {

    private static final int MAX_OUTPUT_LENGTH = 8_000;
    private static final String TRUNCATION_MARKER = "\n[command output truncated]";
    private static final PlainTextComponentSerializer PLAIN_TEXT = PlainTextComponentSerializer.plainText();

    private final StringBuilder output = new StringBuilder();
    private final CommandSender sender;
    private boolean truncated;

    CommandOutputCapture(ConsoleCommandSender console) {
        this.sender = console.getServer().createCommandSender(this::append);
    }

    CommandSender createSender() {
        return sender;
    }

    synchronized String getOutput() {
        if (!truncated) {
            return output.toString();
        }
        return output + TRUNCATION_MARKER;
    }

    synchronized boolean isTruncated() {
        return truncated;
    }

    private void append(@NotNull Component message) {
        append(PLAIN_TEXT.serialize(Objects.requireNonNull(message)));
    }

    private synchronized void append(String message) {
        if (message == null || message.isEmpty() || truncated) {
            return;
        }

        int separatorLength = output.length() == 0 ? 0 : 1;
        int remaining = MAX_OUTPUT_LENGTH - output.length();
        if (separatorLength > 0) remaining--;

        int markerLength = TRUNCATION_MARKER.length();
        if (message.length() <= remaining) {
            if (separatorLength > 0) output.append('\n');
            output.append(message);
            return;
        }

        truncated = true;
        int contentLimit = MAX_OUTPUT_LENGTH - markerLength;
        if (output.length() > contentLimit) {
            output.setLength(contentLimit);
        }
        remaining = contentLimit - output.length();
        if (separatorLength > 0 && remaining > 0) {
            output.append('\n');
            remaining--;
        }
        if (remaining > 0) {
            output.append(message, 0, Math.min(message.length(), remaining));
        }
    }
}
