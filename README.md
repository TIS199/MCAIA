# MCAIA

<p align="center">
  <img src="https://raw.githubusercontent.com/TIS199/MCAIA/main/MCAIA.png" alt="MCAIA" width="720">
</p>

<h3 align="center">Talk to your Minecraft server. MCAIA handles the command complexity.</h3>

<p align="center">
  <a href="https://github.com/TIS199/MCAIA/releases"><img src="https://img.shields.io/github/v/release/TIS199/MCAIA?include_prereleases&label=release" alt="Release"></a>
  <a href="https://hangar.papermc.io/TIS199/MCAIA"><img src="https://img.shields.io/badge/Hangar-PaperMC-fff?logo=papermc&logoColor=black" alt="Hangar"></a>
  <img src="https://img.shields.io/badge/Paper-26.2--26.3-fff?logo=papermc&logoColor=black" alt="Paper 26.2-26.3">
  <img src="https://img.shields.io/badge/Java-25-fff?logo=openjdk&logoColor=black" alt="Java 25">
  <a href="https://github.com/TIS199/MCAIA/blob/main/LICENSE"><img src="https://img.shields.io/github/license/TIS199/MCAIA" alt="License"></a>
</p>

> [!WARNING]
> **MCAIA is still under active development.** It can execute Minecraft commands with server-level access, and the command guard is a safeguard—not a complete security boundary. Test on a disposable server or keep reliable backups, review the command blocklist, and only grant AI access to people you trust.

## What is MCAIA?

**MCAIA (Minecraft AI Admin)** is an AI-powered administration assistant for Paper servers.

Instead of remembering a long list of commands, describe what you need in plain language:

```text
/ai how many players are online?
/ai what's the current TPS?
/ai show me information about Steve
/ai list the installed plugins
/ai give everyone Speed II for 5 minutes
```

MCAIA can inspect the live server, ask you for missing information, execute permitted commands, read the command result, and continue the task when another step is needed.

That makes it especially useful for **new server owners and admins** who know what they want to do, but do not yet know every Minecraft command, plugin command, or syntax detail.

## Why MCAIA?

Running a Minecraft server often means learning dozens of commands across vanilla Minecraft, permissions plugins, moderation tools, and other plugins.

MCAIA turns that into a conversation.

You can ask:

- **Questions** — "How many players are online?"
- **Diagnostics** — "Is the server TPS healthy?"
- **Lookups** — "Tell me about Steve."
- **Administration tasks** — "Give all players Speed II for 5 minutes."
- **Multi-step tasks** — "Find the nearest village and teleport me there." MCAIA can obtain command output first, then use the real result for the next step instead of guessing.
- **Follow-up questions** — the AI can ask for missing information and continue after your reply.

The goal is simple: **make server administration easier without pretending that AI should have unlimited control.**

## Features

### Natural-language server assistant

Use a configurable `/ai` command to talk to the assistant in plain language. The command can be renamed in `config.yml`.

### Live server awareness

MCAIA can query:

- Online player list
- Online player details: UUID, game mode, health, hunger, world, coordinates, OP status, and ping
- Worlds and player counts
- Installed plugins and their versions/status
- 1-minute, 5-minute, and 15-minute TPS
- Server version, Minecraft version, MOTD, online-mode state, player count, and player limit

### Multi-step AI workflows

MCAIA is built around a structured loop rather than a single text reply:

```text
Player request
      ↓
AI decision
      ↓
Server query or command
      ↓
Real server result
      ↓
AI decision again
      ↓
Final answer / next action
```

For example, a task that needs a real seed, location, or command result can execute a read command first and then use the captured output for the next step.

### Multiple AI providers

Configure one or several providers:

- Google Gemini
- Groq
- OpenRouter
- OpenAI
- Anthropic Claude
- xAI Grok

Provider order and model fallback lists are configurable in `models.yml`. With smart switching enabled, MCAIA can move through configured providers/models when requests fail, are rate-limited, or encounter retryable service errors.

### Permission-aware access

MCAIA supports two access styles:

- **Administrator mode** — `mcaia.use` gives the AI console-level command access for the player using `/ai`.
- **Player mode** — when enabled, `mcaia.player` allows a user to use the AI with the permissions they already have. MCAIA checks the command permission before dispatching it.

LuckPerms and Vault are detected automatically; without them, MCAIA can use an OP/configured-player fallback.

### Command safety guard

The plugin has multiple layers of command protection, including:

- Hard-blocked administrative and privilege-escalating command roots
- Custom `banned-commands.yml` entries
- Namespaced command handling
- Nested `execute` inspection
- Command-chain rejection (`;`, `|`, `&&`, etc.)
- Wrapper detection for commands such as `sudo`
- Player-mode permission checks before command dispatch
- A configurable per-player cooldown

The guard is designed to reduce dangerous mistakes and misuse. **It is not a sandbox and should not be treated as one.**

### Conversation context

Each player gets their own in-memory conversation history. MCAIA can:

- Keep recent turns for context
- Show recent history with `/ai history`
- Clear history with `/ai reset`
- Automatically expire idle conversations after 10 minutes
- Ask a private follow-up question whose next chat message becomes the answer

A player's old conversation state is cleared when they join the server again.

### Bedrock support

With Geyser + Floodgate installed, Bedrock players can be detected and optionally allowed to use `/ai`.

### Logging and monitoring

Optional administration features include:

- Daily rolling plugin logs
- Configurable Discord admin webhook events
- Runtime `/aiadmin status`
- `/aiadmin reload`
- `/aiadmin clearhistory <player|*>`
- `/aiadmin debug`
- Update notifications from GitHub Releases and Hangar
- Anonymous bStats metrics

## Example requests

These are the kinds of requests MCAIA is designed to understand:

```text
/ai how many players are online?
/ai what's our current TPS?
/ai list all installed plugins
/ai show me info about Steve
/ai give every online player Speed II for 5 minutes
/ai find the nearest village and teleport me there
/ai what's the server version and MOTD?
/ai ask me which player I want to target
```

MCAIA will refuse or block requests that violate its configured/hard-coded safety rules, such as attempts to use blocked administrative commands or privilege-escalating wrappers.

## Requirements

- **Paper 26.2–26.3**
- **Java 25**
- At least one API key from a supported AI provider

Optional integrations:

- LuckPerms
- Vault
- Geyser
- Floodgate

MCAIA does not require those optional plugins to operate.

## Installation

1. Download the latest MCAIA JAR from [GitHub Releases](https://github.com/TIS199/MCAIA/releases) or build it from source.
2. Put the JAR into your server's `plugins/` folder.
3. Start the server once so `plugins/MCAIA/` and its configuration files are created.
4. Open `plugins/MCAIA/config.yml`.
5. Review the Terms of Service and set:

   ```yaml
   tos-accepted: true
   ```

6. Add at least one AI provider API key, for example:

   ```yaml
   ai:
     providers:
       gemini:
         api-key: "YOUR_GEMINI_API_KEY"
   ```

7. Check `models.yml` and choose your provider/model order.
8. Restart the server.
9. Use:

   ```text
   /ai help
   ```

Keep API keys private. Never commit a populated `config.yml` to a public repository.

## Configuration files

MCAIA creates and uses:

| File | Purpose |
| --- | --- |
| `config.yml` | TOS acceptance, providers/API keys, command name, permissions, cooldown, logging, Geyser/Floodgate settings, messages |
| `models.yml` | Provider priority and per-provider model fallback order |
| `banned-commands.yml` | Additional command roots that the AI must never execute |
| `logs/` | Daily plugin logs when file logging is enabled |

Some of the most useful settings are:

```yaml
ai:
  max-tokens: 2048
  temperature: 0.3
  max-history-length: 10
  query-timeout-seconds: 60
  max-iterations: 8
  smart-switching: true

rate-limit:
  enabled: true
  cooldown-seconds: 15

permissions:
  fallback-require-op: true
  player-command-mode:
    enabled: false

logging:
  file-logging: true
  debug-mode: false
  admin-webhook-url: ""
```

See the generated configuration comments for the complete list of options.

> [!CAUTION]
> **Debug mode can expose AI request/response payloads in server logs.** Do not enable it casually on a production server.

## Commands

| Command | Description |
| --- | --- |
| `/ai <prompt>` | Ask a question or request an action |
| `/ai help` | Show usage and examples |
| `/ai history` | Show recent conversation history |
| `/ai reset` | Clear your conversation history |
| `/aiadmin` | Show admin command help |
| `/aiadmin status` | Show configuration, provider, model, and integration status |
| `/aiadmin reload` | Reload MCAIA configuration |
| `/aiadmin clearhistory <player|*>` | Clear AI history for one online player or all online players |
| `/aiadmin debug` | Toggle debug logging until the next restart/reload |

## Permissions

| Permission | Purpose | Default |
| --- | --- | --- |
| `mcaia.use` | Full console-level AI access | Operators |
| `mcaia.player` | Player-scoped AI access when player mode is enabled | Not granted |
| `mcaia.admin` | Access to `/aiadmin` | Operators |
| `mcaia.reload` | Declared reload permission node | Operators |
| `mcaia.bypass-rate-limit` | Bypass `/ai` cooldown | Operators |
| `mcaia.history` | View conversation history | Operators |
| `mcaia.*` | Grants all MCAIA permissions | Not granted |

## Security model

MCAIA is intentionally opinionated about server control.

The AI is told to use a structured JSON protocol and the plugin independently validates the action before dispatching it. The guard normalizes command text, rejects command chaining, checks namespaced aliases, inspects nested `execute` commands, and blocks configured/hard-coded dangerous command roots.

For administrator-level access, MCAIA dispatches commands through a console-compatible sender and can capture up to **8,000 characters** of command output for the next AI decision. When the first result is empty, it can wait a configurable number of ticks for asynchronous output.

That makes multi-step tasks possible, but it also means the AI can interact with real server state. Treat MCAIA like an automation tool with server privileges—not like a harmless chat bot.

## Data and privacy

MCAIA sends prompts and relevant conversation context to the AI provider you configure. Depending on the request and access mode, that conversation can also contain:

- Server context such as player count and TPS
- Live server query results
- Player information requested by the AI
- Captured command output
- Player-mode allowed command labels

Command output can contain player names, coordinates, configuration details, or other server data. Review your provider's data handling and retention policies before enabling the plugin.

MCAIA also initializes bStats for anonymous plugin/server metrics. See the [bStats privacy policy](https://bstats.org/getting-started/privacy).

## AI provider notes

Provider access and model availability change over time. Free tiers can be limited by account, region, quotas, or provider policy. Paid providers may charge for API usage.

Supported provider families:

- Gemini
- Groq
- OpenRouter
- OpenAI
- Anthropic Claude
- xAI Grok

MCAIA's provider/model selection is driven by `models.yml`, so you can change your preferred models without rewriting the plugin.

## Updating

MCAIA can check GitHub Releases and Hangar for newer versions and notify users with `mcaia.admin` when an update is available.

Always review the release notes and configuration changes before upgrading a live server.

## Building from source

MCAIA uses Gradle and Java 25.

```bash
./gradlew build
```

The shaded plugin JAR is produced under `build/libs/`.

On Windows:

```bat
gradlew.bat build
```

## Compatibility

MCAIA is currently built specifically for **Paper** and uses Paper APIs for command registration and lifecycle handling.

Optional integrations:

- **LuckPerms / Vault** — permission backend integration
- **Geyser / Floodgate** — Bedrock player detection and access
- **EssentialsX / ViaVersion** — detected for compatibility/status reporting

## Support and community

Need help, found a bug, or have an idea?

**Discord:** https://discord.gg/C4anUJdynJ
**GitHub:** https://github.com/TIS199/MCAIA

For bug reports, include your MCAIA version, Paper version, relevant server logs, and the exact request that caused the problem. Remove API keys, webhook URLs, and private player data before posting logs.

## Contributing

Contributions are welcome.

1. Fork the repository.
2. Create a focused branch for your change.
3. Test on a disposable Paper server.
4. Run `./gradlew build`.
5. Open a pull request with a clear summary and testing notes.

For larger changes, open an issue first so the approach can be discussed before implementation.

## License

MCAIA is licensed under **GNU GPL v3 only (`GPL-3.0-only`)**.

See [LICENSE](./LICENSE) for the full license text.

---
