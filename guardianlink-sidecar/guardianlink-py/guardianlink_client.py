"""GuardianLink sidecar client — Python.

Thin wrapper over the sidecar's REST+JSON API. The governed flow is:

    submit(action)          -> {actionId, rendering, impact, coolingWindowMs, serverTimeMs}
    get_challenge(actionId)-> {challengeId, phrase}   (voice ceremony, step 1)
    speak(phrase)           -> base64 PCM of the person speaking the phrase
    sign(action_id, ...)    -> hex HMAC over (rendering, assented_at_ms, action_id)
    assent(..., audio)      -> the NineGates decision, via ConciergeInterface.execute

The client never executes anything itself: execution happens only inside
the engine, behind the gates. Signing is a caller-provided callable so
deployments can plug in HSMs, MPC, or real person ceremonies; the
default is the pre-shared HMAC signer matching the sidecar's demo-grade
verifier (see README.md for verifier posture). Voice capture is a
caller-provided audio_provider callable; demo_voice.py ports the
engine's synthetic demo voice for microphone-free testing.

Only the standard library is used.
"""

import hashlib
import hmac
import json
import time
import urllib.request
import urllib.error

ASSENT_DOMAIN = "v1|assent"
CALLER_DOMAIN = "v1|caller"


def assent_message(action_id: str, assented_at_ms: int, rendering: str) -> bytes:
    """Byte-identical to the sidecar's assentMessage. Do not change."""
    return f"{ASSENT_DOMAIN}|{action_id}|{assented_at_ms}|{rendering}".encode("utf-8")


def caller_message(key_message: bytes) -> bytes:
    return CALLER_DOMAIN.encode("utf-8") + b"|" + key_message


def hmac_sign(secret: bytes, message: bytes) -> str:
    return hmac.new(secret, message, hashlib.sha256).hexdigest()


class GuardianLinkError(Exception):
    pass


class GuardianLinkClient:
    def __init__(self, base_url: str, hmac_secret_hex: str, timeout: float = 30.0):
        self.base_url = base_url.rstrip("/")
        self.secret = bytes.fromhex(hmac_secret_hex)
        self.timeout = timeout

    # ---- transport ----

    def _request(self, method: str, path: str, body: dict | None = None) -> dict:
        data = json.dumps(body).encode("utf-8") if body is not None else None
        req = urllib.request.Request(
            self.base_url + path, data=data, method=method,
            headers={"Content-Type": "application/json"},
        )
        try:
            with urllib.request.urlopen(req, timeout=self.timeout) as res:
                return {"http_status": res.status, "body": json.loads(res.read().decode("utf-8"))}
        except urllib.error.HTTPError as e:
            raw = e.read().decode("utf-8", errors="replace")
            try:
                parsed: dict = json.loads(raw)
            except json.JSONDecodeError:
                parsed = {"error": raw}
            return {"http_status": e.code, "body": parsed}

    # ---- phase 1: submit ----

    def submit(self, action: dict) -> dict:
        """Parse + render. No execution. Returns the rendering to be signed."""
        res = self._request("POST", "/v1/actions/submit", {"action": action})
        if res["http_status"] != 200:
            raise GuardianLinkError(f"submit failed: {res['body']}")
        return res["body"]

    # ---- signing (caller-provided ceremony goes here) ----

    def sign_assent(self, action_id: str, assented_at_ms: int, rendering: str) -> str:
        """Default signer: pre-shared HMAC. Replace with your ceremony."""
        return hmac_sign(self.secret, assent_message(action_id, assented_at_ms, rendering))

    def sign_caller(self, key_message: bytes) -> str:
        return hmac_sign(self.secret, caller_message(key_message))

    # ---- voice ceremony ----

    def get_challenge(self, action_id: str) -> dict:
        """Step 1 of the voice ceremony: fetch the single-use liveness
        challenge. Returns {challenge_id, phrase, expires_in_ms,
        audio_format}. The person must speak `phrase`; the PCM goes back
        with the assent. 404: unknown action. 409: another action's
        ceremony is live (ceremonies are serial per sidecar instance)."""
        res = self._request("GET", f"/v1/actions/{action_id}/challenge")
        if res["http_status"] != 200:
            raise GuardianLinkError(f"challenge failed: {res['body']}")
        return res["body"]

    # ---- phase 2: assent ----

    def assent(
        self,
        action_id: str,
        rendering: str,
        assented_at_ms: int,
        assent_signature: str,
        assent_audio_b64: str,
        key_signature: str | None = None,
        key_message: bytes | None = None,
    ) -> dict:
        """Phase 2: run the NineGates path. `assent_audio_b64` is required:
        base64 of little-endian float32 mono PCM at 16 kHz of the person
        speaking the challenged phrase. The engine verifies speaker +
        liveness at Gate 7 (theta_voice); missing audio or a failed
        ceremony is a rejection, never a default-allow."""
        km = key_message if key_message is not None else action_id.encode("utf-8")
        res = self._request("POST", f"/v1/actions/{action_id}/assent", {
            "rendering": rendering,
            "assentedAtMs": assented_at_ms,
            "assentSignature": assent_signature,
            "keySignature": key_signature or self.sign_caller(km),
            "assentAudio": assent_audio_b64,
        })
        return res

    # ---- composed flow ----

    def governed(
        self,
        action: dict,
        signer=None,
        audio_provider=None,
        poll_interval: float = 0.5,
    ) -> dict:
        """Full governed flow: submit -> challenge -> speak -> sign ->
        wait out the cooling window -> assent.

        `signer` is a caller-provided callable
        (action_id, assented_at_ms, rendering) -> hex signature.
        `audio_provider` is a caller-provided callable
        (challengeId, phrase) -> base64 float32-LE PCM at 16 kHz of the
        person speaking the phrase. assented_at_ms is derived from the
        server's clock (serverTimeMs + local elapsed) so client clock
        skew can't push the assent into the future and trip Gate 7's
        future-dated-assent halt.
        """
        if audio_provider is None:
            raise GuardianLinkError(
                "governed() requires an audio_provider: the voice ceremony is "
                "mandatory. Use demo_voice.speak_phrase + pcm_to_b64 for "
                "microphone-free testing, or wire real capture."
            )
        submitted = self.submit(action)
        action_id = submitted["actionId"]
        rendering = submitted["rendering"]
        cooling_ms = submitted["coolingWindowMs"]
        local_submit = time.time() * 1000.0
        server_submit = submitted["serverTimeMs"]

        challenge = self.get_challenge(action_id)
        audio_b64 = audio_provider(challenge["challengeId"], challenge["phrase"])

        sign = signer or self.sign_assent
        # The person signs now; the seal must wait out the cooling window.
        assented_at_ms = int(server_submit + (time.time() * 1000.0 - local_submit))
        signature = sign(action_id, assented_at_ms, rendering)

        wait_ms = cooling_ms - (time.time() * 1000.0 - local_submit)
        if wait_ms > 0:
            time.sleep(wait_ms / 1000.0 + poll_interval)

        return self.assent(action_id, rendering, assented_at_ms, signature, audio_b64)

    # ---- inspection ----

    def ledger_verify(self) -> dict:
        return self._request("GET", "/v1/ledger/verify")["body"]

    def substrate(self) -> dict:
        return self._request("GET", "/v1/substrate")["body"]
