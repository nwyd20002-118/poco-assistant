/**
 * Poco Bridge relay — Cloudflare Worker + Durable Object.
 *
 * Required Worker secrets:
 *   ASSISTANT_TOKEN: secret used by the trusted assistant connector
 *   DEVICE_TOKEN:    separate secret used only by the paired Android app
 *
 * Required binding:
 *   POCO_BRIDGE: Durable Object namespace bound to PocoBridgeState
 *
 * This relay queues allow-listed commands. It does NOT itself control Android.
 * The Android agent must validate every command and require local approval
 * for sensitive actions before execution.
 */
const JSON_HEADERS = { "content-type": "application/json; charset=utf-8", "cache-control": "no-store" };
const ACTIONS = new Set([
  "device.get_status",
  "notifications.list",
  "apps.list_allowed",
  "app.launch_allowed",
  "telegram.prepare_reply",
  "telegram.send_approved",
  "files.list_allowed"
]);

function json(data, status = 200) {
  return new Response(JSON.stringify(data), { status, headers: JSON_HEADERS });
}
function bearer(request) {
  const value = request.headers.get("authorization") || "";
  return value.match(/^Bearer\s+(.+)$/i)?.[1] || "";
}
function equalSecret(a, b) {
  if (!a || !b || a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return diff === 0;
}
async function forward(env, path, init = {}) {
  const id = env.POCO_BRIDGE.idFromName("primary");
  return env.POCO_BRIDGE.get(id).fetch("https://poco-bridge.internal" + path, init);
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    const path = url.pathname;
    const isAssistant = path.startsWith("/v1/assistant/");
    const expected = isAssistant ? env.ASSISTANT_TOKEN : env.DEVICE_TOKEN;
    if (!equalSecret(bearer(request), expected)) return json({ error: "unauthorized" }, 401);

    if (request.method === "POST" && path === "/v1/device/chat") {
      if (!env.OPENAI_API_KEY) return json({ error: "ai_not_configured" }, 503);
      let body;
      try { body = await request.json(); } catch { return json({ error: "invalid_json" }, 400); }
      if (!Array.isArray(body.messages) || body.messages.length < 1 || body.messages.length > 24)
        return json({ error: "messages_must_be_array", max_messages: 24 }, 400);
      const messages = [];
      let totalChars = 0;
      for (const item of body.messages) {
        if (!item || !["user", "assistant"].includes(item.role) || typeof item.content !== "string")
          return json({ error: "invalid_message" }, 400);
        totalChars += item.content.length;
        if (!item.content.trim() || item.content.length > 6000 || totalChars > 24000)
          return json({ error: "conversation_too_long" }, 413);
        messages.push({ role: item.role, content: item.content });
      }
      let aiResponse;
      try {
        aiResponse = await fetch("https://api.openai.com/v1/responses", {
          method: "POST",
          headers: { "authorization": "Bearer " + env.OPENAI_API_KEY, "content-type": "application/json" },
          body: JSON.stringify({
            model: env.OPENAI_MODEL || "gpt-6-astra",
            instructions: "You are Poco, the user's practical Persian-speaking assistant. Your conversational intelligence is provided by OpenAI. Reply naturally in the user's language, remember and use the conversation context included in this request, and be concise but useful. You do not have direct access to the phone. Never claim an action was performed unless a separate device operation has actually completed. Ask for user approval before sensitive actions. Never request or reveal API keys, passwords, or authentication tokens.",
            input: messages,
            max_output_tokens: 900,
            store: false
          })
        });
      } catch {
        return json({ error: "ai_upstream_unavailable" }, 502);
      }
      const aiData = await aiResponse.json().catch(() => ({}));
      if (!aiResponse.ok) return json({ error: "ai_request_failed", upstream_status: aiResponse.status, detail: aiData.error?.message || "OpenAI API request failed" }, 502);
      const outputText = aiData.output_text || (aiData.output || [])
        .filter(item => item.type === "message")
        .flatMap(item => item.content || [])
        .filter(part => part.type === "output_text")
        .map(part => part.text).join("\\n");
      return json({ ok: true, text: outputText, response_id: aiData.id || null, model: aiData.model || env.OPENAI_MODEL || "gpt-6-astra" });
    }

    if (request.method === "POST" && path === "/v1/assistant/chat") {
      if (!env.OPENAI_API_KEY) return json({ error: "ai_not_configured" }, 503);
      let body;
      try { body = await request.json(); } catch { return json({ error: "invalid_json" }, 400); }
      if (typeof body.input !== "string" || !body.input.trim()) return json({ error: "input_required" }, 400);
      if (body.input.length > 6000) return json({ error: "input_too_long", max_chars: 6000 }, 413);

      let aiResponse;
      try {
        aiResponse = await fetch("https://api.openai.com/v1/responses", {
          method: "POST",
          headers: {
            "authorization": "Bearer " + env.OPENAI_API_KEY,
            "content-type": "application/json"
          },
          body: JSON.stringify({
            model: env.OPENAI_MODEL || "gpt-6-astra",
            instructions: "You are Poco, a practical Persian-speaking assistant running as a companion on the user's Android phone. Reply in the language used by the user, be concise and accurate. You cannot claim to have performed actions on the phone unless a separate device command has actually completed. Never request or reveal API keys, passwords, or authentication tokens.",
            input: body.input,
            max_output_tokens: 700,
            store: false
          })
        });
      } catch {
        return json({ error: "ai_upstream_unavailable" }, 502);
      }

      const aiData = await aiResponse.json().catch(() => ({}));
      if (!aiResponse.ok) {
        return json({
          error: "ai_request_failed",
          upstream_status: aiResponse.status,
          detail: aiData.error?.message || "OpenAI API request failed"
        }, 502);
      }
      const outputText = aiData.output_text || (aiData.output || [])
        .filter(item => item.type === "message")
        .flatMap(item => item.content || [])
        .filter(part => part.type === "output_text")
        .map(part => part.text)
        .join("\\n");
      return json({ ok: true, text: outputText, response_id: aiData.id || null, model: aiData.model || env.OPENAI_MODEL || "gpt-6-astra" });
    }

    if (request.method === "GET" && path === "/v1/assistant/status")
      return forward(env, "/state/status");

    if (request.method === "POST" && path === "/v1/assistant/commands") {
      let body;
      try { body = await request.json(); } catch { return json({ error: "invalid_json" }, 400); }
      if (!ACTIONS.has(body.action)) return json({ error: "action_not_allowed" }, 403);
      if (typeof body.payload !== "object" || body.payload === null || Array.isArray(body.payload))
        return json({ error: "payload_must_be_object" }, 400);
      return forward(env, "/state/commands", {
        method: "POST",
        headers: { "content-type": "application/json" },
        body: JSON.stringify({
          action: body.action,
          payload: body.payload,
          requires_user_approval: true,
          created_at: new Date().toISOString()
        })
      });
    }

    if (request.method === "GET" && path === "/v1/device/commands/next")
      return forward(env, "/state/commands/next");

    if (request.method === "POST" && path === "/v1/device/heartbeat") {
      let body;
      try { body = await request.json(); } catch { return json({ error: "invalid_json" }, 400); }
      return forward(env, "/state/heartbeat", {
        method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify({
          app_version: String(body.app_version || "").slice(0, 40),
          android_version: String(body.android_version || "").slice(0, 40),
          device_model: String(body.device_model || "").slice(0, 80),
          at: new Date().toISOString()
        })
      });
    }

    if (request.method === "POST" && /^\/v1\/device\/commands\/[a-zA-Z0-9_-]+\/result$/.test(path)) {
      let body;
      try { body = await request.json(); } catch { return json({ error: "invalid_json" }, 400); }
      const id = path.split("/")[4];
      return forward(env, "/state/commands/" + id + "/result", {
        method: "POST", headers: { "content-type": "application/json" },
        body: JSON.stringify({ status: body.status, result: body.result ?? null, at: new Date().toISOString() })
      });
    }
    return json({ error: "not_found" }, 404);
  }
};

export class PocoBridgeState {
  constructor(ctx) { this.ctx = ctx; }

  async fetch(request) {
    const url = new URL(request.url);
    if (request.method === "GET" && url.pathname === "/state/status") {
      const device = await this.ctx.storage.get("device") || null;
      const commands = await this.ctx.storage.list({ prefix: "cmd:" });
      const pending = [...commands.values()].filter(x => x.status === "queued" || x.status === "awaiting_approval").length;
      return json({ paired: !!device, device, pending_commands: pending });
    }
    if (request.method === "POST" && url.pathname === "/state/heartbeat") {
      const body = await request.json();
      await this.ctx.storage.put("device", body);
      return json({ ok: true });
    }
    if (request.method === "POST" && url.pathname === "/state/commands") {
      const body = await request.json();
      const id = crypto.randomUUID();
      const command = {
        id, action: body.action, payload: body.payload,
        requires_user_approval: body.requires_user_approval,
        status: "queued", created_at: body.created_at
      };
      await this.ctx.storage.put("cmd:" + id, command);
      return json({ accepted: true, command_id: id, status: command.status }, 202);
    }
    if (request.method === "GET" && url.pathname === "/state/commands/next") {
      const commands = await this.ctx.storage.list({ prefix: "cmd:" });
      const next = [...commands.values()]
        .filter(x => x.status === "queued")
        .sort((a, b) => a.created_at.localeCompare(b.created_at))[0];
      if (!next) return json({ command: null });
      next.status = next.requires_user_approval ? "awaiting_approval" : "delivered";
      await this.ctx.storage.put("cmd:" + next.id, next);
      return json({ command: next });
    }
    const match = url.pathname.match(/^\/state\/commands\/([a-zA-Z0-9_-]+)\/result$/);
    if (request.method === "POST" && match) {
      const key = "cmd:" + match[1];
      const command = await this.ctx.storage.get(key);
      if (!command) return json({ error: "command_not_found" }, 404);
      const body = await request.json();
      if (!["approved", "rejected", "completed", "failed"].includes(body.status))
        return json({ error: "invalid_status" }, 400);
      command.status = body.status;
      command.result = body.result;
      command.updated_at = body.at;
      await this.ctx.storage.put(key, command);
      return json({ ok: true });
    }
    return json({ error: "not_found" }, 404);
  }
}
