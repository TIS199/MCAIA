package com.mcaia.plugin.logging;

import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.logging.Logger;

/**
 * Writes daily rolling log files to plugins/MCAIA/logs/mcaia-YYYY-MM-DD.log
 */
public class FileLogger {

    private static final DateTimeFormatter DATE_FMT      = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter DATETIME_FMT  = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final JavaPlugin plugin;
    private final Logger     log;
    private final File       logsDir;
    private boolean          enabled;

    public FileLogger(JavaPlugin plugin) {
        this.plugin  = plugin;
        this.log     = plugin.getLogger();
        this.logsDir = new File(plugin.getDataFolder(), "logs");
    }

    public void initialize() {
        enabled = plugin.getConfig().getBoolean("logging.file-logging", true);
        if (enabled) {
            if (!logsDir.exists() && !logsDir.mkdirs()) {
                log.warning("[MCAIA] Failed to create logs directory.");
                enabled = false;
            }
        }
    }

    /**
     * Append a line to today's log file.
     * Format: [YYYY-MM-DD HH:mm:ss] [LEVEL] message
     */
    public void log(String level, String message) {
        if (!enabled) return;

        String timestamp = LocalDateTime.now().format(DATETIME_FMT);
        String line      = "[" + timestamp + "] [" + level + "] " + message;

        File todayLog = new File(logsDir, "mcaia-" + LocalDate.now().format(DATE_FMT) + ".log");
        try (PrintWriter pw = new PrintWriter(new FileWriter(todayLog, true))) {
            pw.println(line);
        } catch (IOException e) {
            log.warning("[MCAIA] Failed to write to log file: " + e.getMessage());
        }
    }

    public void info(String msg)  { log("INFO",  msg); }
    public void warn(String msg)  { log("WARN",  msg); }
    public void error(String msg) { log("ERROR", msg); }
    public void debug(String msg) {
        if (plugin.getConfig().getBoolean("logging.debug-mode", false)) {
            log("DEBUG", msg);
        }
    }
}
