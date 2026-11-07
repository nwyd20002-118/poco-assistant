# Poco Bridge — architecture and first implementation

## Goal

Turn the existing Android app into a permission-controlled device agent. ChatGPT (through a separately connected, authenticated assistant tool) can request actions; the Android app polls the relay, displays the requested action, asks for local approval, executes only implemented allow-listed actions, and reports the result. The phone agent must also retain a small set of offline/local functions.

## Important boundary

This repository does not make the current ChatGPT conversation automatically connected to the phone. That requires BOTH:
1. A deployed relay and an Android client configured with its device credential.
2. A ChatGPT-side custom connector/tool (for example, an MCP server or a supported custom app/action) configured with the assistant credential.

Until both are configured and tested, there is no live connection. Do not describe the assistant as already controlling the phone.

## Components

- `app/`: Android agent. Existing notification listener is retained as one optional capability.
- `server/worker.js`: Cloudflare Worker relay with a Durable Object command queue.
- `server/wrangler.toml`: deployment configuration.
- Assistant and device credentials are separate Cloudflare secrets. Never commit credentials, API keys, Telegram session strings, or personal notification content.

## Relay API (prototype)

All requests use HTTPS and a bearer token. Assistant endpoints require `ASSISTANT_TOKEN`; device endpoints require `DEVICE_TOKEN`.

| Method | Path | Purpose |
|---|---|---|
| GET | `/v1/assistant/status` | Relay/device heartbeat and pending count |
| POST | `/v1/assistant/commands` | Queue an allow-listed command |
| POST | `/v1/device/heartbeat` | Report app/Android/device model |
| GET | `/v1/device/commands/next` | Fetch next queued command |
| POST | `/v1/device/commands/{id}/result` | Report approval/rejection/result |

Every queued command is marked as requiring local approval. The Android app must show a clear confirmation screen and must not execute a command just because it arrived over the network.

Initial action names are deliberately narrow:
- `device.get_status`
- `notifications.list`
- `apps.list_allowed`
- `app.launch_allowed`
- `telegram.prepare_reply`
- `telegram.send_approved`
- `files.list_allowed`

The Android agent must independently enforce this allow-list. Unsupported actions must fail closed. Sending a Telegram message, deleting/modifying files, or changing device settings must never happen without explicit user confirmation on the phone.

## Deploying the relay (not yet done)

A Cloudflare account and the Wrangler CLI are required. From the `server/` directory:

1. Run `npx wrangler login`.
2. Create the Worker secrets using long random values:
   - `npx wrangler secret put ASSISTANT_TOKEN`
   - `npx wrangler secret put DEVICE_TOKEN`
3. Deploy with `npx wrangler deploy`.
4. Save the resulting HTTPS Worker URL privately. Do not paste either token into chat or commit them to GitHub.
5. Configure the Android app with the Worker URL and the device token using Android Keystore-backed storage (implementation is a subsequent milestone).

## ChatGPT-side connection

The assistant endpoint is intended to be exposed through a supported custom ChatGPT connector/tool. A plain URL or an installed Android app does not grant this chat access. Connector setup and authorization are a separate milestone; until then, this chat cannot send commands to the relay.

## Milestones

1. **Relay foundation (current):** API contract, Worker command queue, separate credentials, local approval required.
2. Android pairing screen + secure credential storage + heartbeat + command polling.
3. On-device approval inbox and safe command dispatcher.
4. ChatGPT connector exposing only status, read-only queries, and command submission.
5. Add Telegram/file/app capabilities one at a time, with tests and permission review.

## Current limitations

The relay is a prototype, not a deployed service. The Android app does not yet poll it. No ChatGPT connector is connected. The existing notification listener remains local and is not an unrestricted Telegram API. Android/MIUI background execution and permission constraints still apply.
