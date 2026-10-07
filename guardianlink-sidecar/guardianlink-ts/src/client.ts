/**
 * GuardianLink sidecar client — TypeScript.
 *
 * Thin wrapper over the sidecar's REST+JSON API. The governed flow is:
 *
 *   submit(action)          -> { actionId, rendering, impact, coolingWindowMs, serverTimeMs }
 *   getChallenge(actionId)  -> { challengeId, phrase }   (voice ceremony, step 1)
 *   speak(phrase)           -> base64 PCM of the person speaking the phrase
 *   sign(actionId, ...)     -> hex HMAC over (rendering, assentedAtMs, actionId)
 *   assent(..., audio)      -> the NineGates decision, via ConciergeInterface.execute
 *
 * The client never executes anything itself: execution happens only inside
 * the engine, behind the gates. Signing takes a caller-provided signer so
 * deployments can plug in HSMs, MPC, or real person ceremonies; the
 * default is the pre-shared HMAC signer matching the sidecar's demo-grade
 * verifier (see README.md for verifier posture). Voice capture is a
 * caller-provided audioProvider; demoVoice.ts ports the engine's synthetic
 * demo voice for microphone-free testing.
 *
 * No runtime dependencies — node:https and node:crypto only.
 */

import * as https from "node:https";
import * as http from "node:http";
import { createHmac } from "node:crypto";

export const ASSENT_DOMAIN = "v1|assent";
export const CALLER_DOMAIN = "v1|caller";

/** Byte-identical to the sidecar's assentMessage. Do not change. */
export function assentMessage(actionId: string, assentedAtMs: number, rendering: string): Buffer {
  return Buffer.from(`${ASSENT_DOMAIN}|${actionId}|${assentedAtMs}|${rendering}`, "utf-8");
}

export function callerMessage(keyMessage: Buffer): Buffer {
  return Buffer.concat([Buffer.from(`${CALLER_DOMAIN}|`, "utf-8"), keyMessage]);
}

export function hmacSign(secret: Buffer, message: Buffer): string {
  return createHmac("sha256", secret).update(message).digest("hex");
}

export type Action =
  | { type: "read"; recordId: string; fields: string[] }
  | { type: "write"; recordId: string; fields: Record<string, string> }
  | { type: "delete"; recordId: string }
  | { type: "sequence"; steps: Action[] }
  | { type: "guarded"; condition: Condition; then: Action; otherwise?: Action };

export type Condition =
  | { type: "fieldEquals"; recordId: string; field: string; expected: string }
  | { type: "and"; parts: Condition[] }
  | { type: "not"; inner: Condition };

export interface SubmittedAction {
  actionId: string;
  rendering: string;
  impact: string;
  coolingWindowMs: number;
  serverTimeMs: number;
}

export type Signer = (actionId: string, assentedAtMs: number, rendering: string) => string;

/** (challengeId, phrase) -> base64 float32-LE PCM at 16 kHz of the person speaking the phrase. */
export type AudioProvider = (challengeId: string, phrase: string) => string;

export interface VoiceChallenge {
  challengeId: string;
  phrase: string;
  expiresInMs: number;
  audioFormat: string;
}

export class GuardianLinkError extends Error {}

export class GuardianLinkClient {
  private baseUrl: string;
  private secret: Buffer;
  private timeoutMs: number;

  constructor(baseUrl: string, hmacSecretHex: string, timeoutMs = 30_000) {
    this.baseUrl = baseUrl.replace(/\/+$/, "");
    this.secret = Buffer.from(hmacSecretHex, "hex");
    this.timeoutMs = timeoutMs;
  }

  private request(method: string, path: string, body?: unknown): Promise<{ httpStatus: number; body: any }> {
    const url = new URL(this.baseUrl + path);
    const lib = url.protocol === "https:" ? https : http;
    const payload = body !== undefined ? JSON.stringify(body) : undefined;
    return new Promise((resolve, reject) => {
      const req = lib.request(
        {
          hostname: url.hostname,
          port: url.port || (url.protocol === "https:" ? 443 : 80),
          path: url.pathname + url.search,
          method,
          headers: payload
            ? { "Content-Type": "application/json", "Content-Length": Buffer.byteLength(payload) }
            : {},
          timeout: this.timeoutMs,
        },
        (res) => {
          const chunks: Buffer[] = [];
          res.on("data", (c) => chunks.push(c));
          res.on("end", () => {
            const raw = Buffer.concat(chunks).toString("utf-8");
            let parsed: any;
            try {
              parsed = JSON.parse(raw);
            } catch {
              parsed = { error: raw };
            }
            resolve({ httpStatus: res.statusCode ?? 0, body: parsed });
          });
        }
      );
      req.on("error", reject);
      req.on("timeout", () => req.destroy(new Error("request timed out")));
      if (payload) req.write(payload);
      req.end();
    });
  }

  /** Phase 1: parse + render. No execution. Returns the rendering to be signed. */
  async submit(action: Action): Promise<SubmittedAction> {
    const res = await this.request("POST", "/v1/actions/submit", { action });
    if (res.httpStatus !== 200) throw new GuardianLinkError(`submit failed: ${JSON.stringify(res.body)}`);
    return res.body as SubmittedAction;
  }

  /** Default signer: pre-shared HMAC. Replace with your ceremony. */
  signAssent(actionId: string, assentedAtMs: number, rendering: string): string {
    return hmacSign(this.secret, assentMessage(actionId, assentedAtMs, rendering));
  }

  signCaller(keyMessage: Buffer): string {
    return hmacSign(this.secret, callerMessage(keyMessage));
  }

  /**
   * Voice ceremony, step 1: fetch the single-use liveness challenge.
   * The person must speak `phrase`; the PCM goes back with the assent.
   * 404: unknown action. 409: another action's ceremony is live
   * (ceremonies are serial per sidecar instance).
   */
  async getChallenge(actionId: string): Promise<VoiceChallenge> {
    const res = await this.request("GET", `/v1/actions/${actionId}/challenge`);
    if (res.httpStatus !== 200) throw new GuardianLinkError(`challenge failed: ${JSON.stringify(res.body)}`);
    return res.body as VoiceChallenge;
  }

  /**
   * Phase 2: run the NineGates path. `assentAudioB64` is required: base64
   * of little-endian float32 mono PCM at 16 kHz of the person speaking the
   * challenged phrase. The engine verifies speaker + liveness at Gate 7
   * (theta_voice); missing audio or a failed ceremony is a rejection,
   * never a default-allow.
   */
  async assent(
    actionId: string,
    rendering: string,
    assentedAtMs: number,
    assentSignature: string,
    assentAudioB64: string,
    keySignature?: string,
    keyMessage?: Buffer
  ): Promise<{ httpStatus: number; body: any }> {
    const km = keyMessage ?? Buffer.from(actionId, "utf-8");
    return this.request("POST", `/v1/actions/${actionId}/assent`, {
      rendering,
      assentedAtMs,
      assentSignature,
      keySignature: keySignature ?? this.signCaller(km),
      assentAudio: assentAudioB64,
    });
  }

  /**
   * Full governed flow: submit -> challenge -> speak -> sign -> wait out
   * the cooling window -> assent. assentedAtMs is derived from the
   * server's clock (serverTimeMs + local elapsed) so client clock skew
   * can't push the assent into the future and trip Gate 7's
   * future-dated-assent halt.
   */
  async governed(
    action: Action,
    audioProvider: AudioProvider,
    signer?: Signer
  ): Promise<{ httpStatus: number; body: any }> {
    if (!audioProvider) {
      throw new GuardianLinkError(
        "governed() requires an audioProvider: the voice ceremony is mandatory. " +
          "Use demoVoice.speakPhrase + pcmToB64 for microphone-free testing, or wire real capture."
      );
    }
    const submitted = await this.submit(action);
    const localSubmit = Date.now();

    const challenge = await this.getChallenge(submitted.actionId);
    const audioB64 = audioProvider(challenge.challengeId, challenge.phrase);

    const sign = signer ?? this.signAssent.bind(this);
    // The person signs now; the seal must wait out the cooling window.
    const assentedAtMs = Math.floor(submitted.serverTimeMs + (Date.now() - localSubmit));
    const signature = sign(submitted.actionId, assentedAtMs, submitted.rendering);

    const waitMs = submitted.coolingWindowMs - (Date.now() - localSubmit);
    if (waitMs > 0) await new Promise((r) => setTimeout(r, waitMs + 500));

    return this.assent(submitted.actionId, submitted.rendering, assentedAtMs, signature, audioB64);
  }

  async ledgerVerify(): Promise<any> {
    return (await this.request("GET", "/v1/ledger/verify")).body;
  }

  async substrate(): Promise<any> {
    return (await this.request("GET", "/v1/substrate")).body;
  }
}
