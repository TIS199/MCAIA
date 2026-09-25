# MCAIA

**MCAIA** is a Paper server plugin that connects Minecraft chat to Google Gemini. Ask a question, check the server, or request an action in natural language. The AI can inspect live server data, ask follow-up questions, and propose console commands—basically a very clever assistant with access to the server console, so please read the safety notes before giving it the keys to the kingdom.

> [!WARNING]
> **MCAIA is still under active development.** Features, configuration, and behavior may change, and bugs (the less charming kind) or data loss are possible. Use it on a test server or with backups, and don't rely on it for production server administration just yet.

> [!WARNING]
> MCAIA can run commands as the server console. The configurable command blocklist is a safeguard, not a complete security boundary—and definitely not a tiny digital force field. Review AI-issued actions, configure the blocklist for your server, and only grant access to players you trust.
 
> **Supported Versions: PaperMC 26.2 , 26.3**

## Download

[![GitHub Release (including pre-releases)](https://img.shields.io/github/v/release/TIS199/MCAIA?include_prereleases)](https://github.com/TIS199/MCAIA/releases)
[![Hanger Release](https://img.shields.io/badge/Hanger-Release-blue?logo=papermc)](https://hangar.papermc.io/TIS199/MCAIA)

## Features


![MCAIA](./MCAIA.png)
- Natural-language requests through a configurable in-game command (default: `/ai`).
- Gemini-powered, multi-step interactions: answer players, query live server state, ask follow-up questions, or execute console commands.
- Live queries for online players, player details, worlds, installed plugins, TPS, and general server information.
- Per-player conversation context, history viewing/reset, and automatic expiry after inactivity.
- Configurable Gemini model priority list with automatic fallback on rate limits and high-demand responses.
- Command blocklist checked before AI-issued console commands are dispatched.
- Permission backends that use Bukkit permissions with LuckPerms/Vault detection and an OP/configured-player fallback.
- Optional Geyser/Floodgate Bedrock-player support.
- Per-player request cooldown, daily rolling file logs, optional admin Discord webhook notifications, and a configurable welcome message.
- Runtime admin tools for status, reload, history cleanup, and debug mode.

## Requirements

- A Paper server running **Java 25**.
- A Google Gemini API key.
- Paper runtime compatibility with the Paper API version used to build this project. The plugin metadata declares API version `1.21`; the build currently compiles against Paper `26.2`.

Vault, LuckPerms, Geyser, and Floodgate are optional. MCAIA can run without them.

## Installation

1. Download the MCAIA JAR from the project's GitHub Releases (or build it yourself; see [Building](#building)).
2. Place the JAR in your server's `plugins/` directory.
3. Start the server once to create `plugins/MCAIA/` and its default configuration files.
4. Open `plugins/MCAIA/config.yml`, set your Gemini API key, and explicitly accept the Terms of Service:

   ```yaml
   tos-accepted: true
   ai:
     gemini-api-key: "YOUR_GEMINI_API_KEY"
   ```

5. Restart the server. The plugin will not process `/ai` requests until `tos-accepted` is `true`.

Get a Gemini API key from [Google AI Studio](https://aistudio.google.com/apikey). Keep the key private and do not commit your server's populated `config.yml`.

## Commands

The player command defaults to `/ai`; its name can be changed with `command-name` in `config.yml`. A restart is required after changing it.

| Command | Description |
| --- | --- |
| `/ai <prompt>` | Ask a question or request a server action |
| `/ai help` | Show usage and examples |
| `/ai history` | View up to the last six stored conversation turns |
| `/ai reset` | Clear your conversation history and pending question |
| `/aiadmin` | Show admin command help |
| `/aiadmin status` | Show configuration, permission backend, and detected integrations |
| `/aiadmin reload` | Reload configuration files and relevant settings |
| `/aiadmin clearhistory <player\|*>` | Clear history for an online player, or all online players |
| `/aiadmin debug` | Toggle debug logging until the next restart/reload |

The console can use `/ai` too. When the AI asks a player a follow-up question, that player's next chat message is treated as a private reply and is not broadcast publicly.

## Permissions

| Permission | Purpose | Default |
| --- | --- | --- |
| `mcaia.use` | Use the player AI command | Operators |
| `mcaia.admin` | Use `/aiadmin` | Operators |
| `mcaia.reload` | Declared reload permission node; `/aiadmin reload` currently checks `mcaia.admin` | Operators |
| `mcaia.bypass-rate-limit` | Bypass the player cooldown | Operators |
| `mcaia.history` | View conversation history | Operators |
| `mcaia.*` | Grants all MCAIA permissions | Not granted |

When LuckPerms is installed, Bukkit permission checks use its permission system. Vault is detected as an alternative permission integration. Without either, MCAIA uses the configured OP/player-list fallback. In fallback mode, admin and rate-limit bypass actions require OP.

## Configuration

The plugin creates these files in `plugins/MCAIA/`:

| File | Purpose |
| --- | --- |
| `config.yml` | Terms acceptance, API key, command, permissions, cooldown, logging, Bedrock support, and messages |
| `models.yml` | Gemini model priority order used for API requests |
| `banned-commands.yml` | Console commands the AI is not allowed to execute |
| `logs/` | Daily plugin logs when file logging is enabled |

Notable `config.yml` settings include:

- `ai.max-tokens`, `ai.temperature`, `ai.max-history-length`, `ai.query-timeout-seconds`, and `ai.max-iterations`.
- `ai.smart-switching` and `ai.google-ai-subscription`.
- `permissions.fallback-require-op` and `permissions.permitted-players`.
- `rate-limit.enabled` and `rate-limit.cooldown-seconds`.
- `logging.file-logging`, `logging.debug-mode`, `logging.admin-webhook-url`, and `logging.log-events`.
- `geyser.allow-bedrock-players` and `geyser.strip-bedrock-prefix`.

See the generated configuration comments for the complete options and defaults. Avoid enabling debug mode on a production server: it can write full AI request and response payloads to logs.

## Data and privacy

Using MCAIA sends prompts and relevant conversation context to Google's Gemini API. The plugin can also include current server context and requested live server data in those API interactions.

**MCAIA also sends usage telemetry to a developer-controlled Discord webhook.** This telemetry is automatic and currently has no configuration switch. It includes the server name, player name, and action details; action details can include prompt summaries and full command text. The optional admin webhook is separate and can receive the events selected under `logging.log-events`.

Server owners should review this behavior and the Terms of Service in `config.yml` before enabling the plugin. Treat prompts, server logs, webhook events, API keys, and generated configuration as potentially sensitive.

## Building

The Gradle wrapper is included. With Java 25 installed, run:

```bash
./gradlew build
```

The distributable shaded plugin JAR is created under `build/libs/` (for example, `MCAIA-1.0.0.jar`). On Windows, use `gradlew.bat build`.

Optional local deployment task:

```bash
./gradlew copyToServer
```

This task copies the JAR to a developer-specific Paper plugins directory configured in `build.gradle.kts`; edit that destination before using it on another machine.

## Compatibility integrations

- **LuckPerms / Vault:** Permission integration. MCAIA checks standard Bukkit permission nodes.
- **Geyser / Floodgate:** Optional Bedrock player detection and access controls.
- **EssentialsX / ViaVersion:** Detected for status/logging; MCAIA does not require them.

## Join the adventure

Found a bug? Have an idea that would make MCAIA more useful (or less likely to turn your server into a crater)? Contributions are welcome!

- **Report bugs:** Open an issue in the repository. Include what you expected, what actually happened, steps to reproduce it, and relevant server logs. Please remove API keys, player data, webhook URLs, and other secrets before posting.
- **Suggest features:** Open an issue describing the use case and the problem you want solved. A good feature request helps explain the *why*, not just the shape of the shiny new button.
- **Send a pull request:** Fork the project, make your changes on a branch, and open a PR with a clear summary and testing notes. Keep changes focused, follow the existing code style, and run `./gradlew build` before submitting.
- **Help other contributors:** Review open issues and PRs, test changes on a disposable server, or improve the documentation. No creeper-defusing certification required.

For larger changes, opening an issue first is a great way to discuss the approach before you spend time building it. By contributing, you agree that your contribution is provided under this project's **GPL-3.0-only** license.

## License

MCAIA is licensed under **GNU GPL version 3 only** (`GPL-3.0-only`). See the [LICENSE](./LICENSE) file for the complete terms. Copyright (c) 2026 TIS199.

## Author

Created by [TIS199](https://github.com/TIS199) with Gemini and Claude. 
