package com.mcaia.plugin.ai;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.ComponentLike;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.md_5.bungee.api.chat.BaseComponent;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.UUID;

/**
 * Forwards console command sender operations while capturing messages sent by the command.
 */
final class CommandOutputCapture implements InvocationHandler {

    private static final int MAX_OUTPUT_LENGTH = 8_000;
    private static final String TRUNCATION_MARKER = "\n[command output truncated]";
    private static final PlainTextComponentSerializer PLAIN_TEXT = PlainTextComponentSerializer.plainText();

    private final ConsoleCommandSender delegate;
    private final StringBuilder output = new StringBuilder();
    private final CommandSender.Spigot capturingSpigot;
    private boolean truncated;

    CommandOutputCapture(ConsoleCommandSender delegate) {
        this.delegate = delegate;
        this.capturingSpigot = new CapturingSpigot(delegate.spigot());
    }

    ConsoleCommandSender createSender() {
        return (ConsoleCommandSender) Proxy.newProxyInstance(
                ConsoleCommandSender.class.getClassLoader(),
                new Class<?>[]{ConsoleCommandSender.class},
                this
        );
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        if (method.getDeclaringClass() == Object.class) {
            return switch (method.getName()) {
                case "toString" -> delegate.toString();
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                default -> method.invoke(delegate, args);
            };
        }

        if (method.getName().equals("spigot")) {
            return capturingSpigot;
        }
        if (method.getName().equals("sendMessage")
                || method.getName().equals("sendRichMessage")
                || method.getName().equals("sendPlainMessage")) {
            capture(args);
        }

        try {
            return method.invoke(delegate, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    synchronized String getOutput() {
        if (!truncated) {
            return output.toString();
        }
        int contentLimit = Math.max(0, MAX_OUTPUT_LENGTH - TRUNCATION_MARKER.length());
        return output.substring(0, Math.min(output.length(), contentLimit)) + TRUNCATION_MARKER;
    }

    synchronized boolean isTruncated() {
        return truncated;
    }

    private void capture(Object[] args) {
        if (args == null) {
            return;
        }
        for (Object arg : args) {
            if (arg instanceof String text) {
                append(text);
            } else if (arg instanceof String[] messages) {
                for (String message : messages) {
                    append(message);
                }
            } else if (arg instanceof Component component) {
                append(PLAIN_TEXT.serialize(component));
            } else if (arg instanceof ComponentLike componentLike) {
                append(PLAIN_TEXT.serialize(componentLike.asComponent()));
            } else if (arg instanceof BaseComponent component) {
                append(net.md_5.bungee.api.ChatColor.stripColor(component.toLegacyText()));
            } else if (arg instanceof BaseComponent[] components) {
                for (BaseComponent component : components) {
                    append(net.md_5.bungee.api.ChatColor.stripColor(component.toLegacyText()));
                }
            }
        }
    }

    private synchronized void append(String message) {
        if (message == null || message.isEmpty()) {
            return;
        }

        int remaining = MAX_OUTPUT_LENGTH - output.length();
        if (remaining <= 0) {
            truncated = true;
            return;
        }
        if (output.length() > 0) {
            output.append('\n');
            remaining--;
        }
        if (remaining > 0) {
            output.append(message, 0, Math.min(message.length(), remaining));
        }
        if (message.length() > remaining) {
            truncated = true;
        }
    }

    @SuppressWarnings("deprecation")
    private final class CapturingSpigot extends CommandSender.Spigot {
        private final CommandSender.Spigot spigotDelegate;

        private CapturingSpigot(CommandSender.Spigot spigotDelegate) {
            this.spigotDelegate = spigotDelegate;
        }

        @Override
        public void sendMessage(BaseComponent component) {
            capture(new Object[]{component});
            spigotDelegate.sendMessage(component);
        }

        @Override
        public void sendMessage(BaseComponent... components) {
            capture(new Object[]{components});
            spigotDelegate.sendMessage(components);
        }

        @Override
        public void sendMessage(UUID sender, BaseComponent component) {
            capture(new Object[]{component});
            spigotDelegate.sendMessage(sender, component);
        }

        @Override
        public void sendMessage(UUID sender, BaseComponent... components) {
            capture(new Object[]{components});
            spigotDelegate.sendMessage(sender, components);
        }
    }
}
