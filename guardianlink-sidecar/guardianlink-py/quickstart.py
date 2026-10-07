"""GuardianLink sidecar quickstart — Python.

Runs the full governed flow against a local sidecar, including the voice
ceremony (spoken here by the demo-voice synthesis port — a real
deployment captures microphone audio instead):

    GUARDIANLINK_HMAC_SECRET=<hex> python3 quickstart.py

Expected output: the challenge is issued, the write executes, the
receipt carries the seal root, and the anchor chain verifies intact.
"""

import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from guardianlink_client import GuardianLinkClient  # noqa: E402
from demo_voice import speak_phrase, pcm_to_b64  # noqa: E402


def main() -> None:
    secret = os.environ.get("GUARDIANLINK_HMAC_SECRET")
    if not secret:
        print("set GUARDIANLINK_HMAC_SECRET to the sidecar's pre-shared key", file=sys.stderr)
        sys.exit(1)
    base_url = os.environ.get("GUARDIANLINK_URL", "http://localhost:8080")
    client = GuardianLinkClient(base_url, secret)

    action = {"type": "write", "recordId": "profile", "fields": {"name": "Neo"}}
    print(f"action:    {json.dumps(action)}")

    def audio_provider(challenge_id: str, phrase: str) -> str:
        print(f"challenge: {challenge_id} — \"{phrase}\"")
        return pcm_to_b64(speak_phrase(challenge_id))

    result = client.governed(action, audio_provider=audio_provider)
    body = result["body"]
    print(f"http:      {result['http_status']}")
    print(f"status:    {body.get('status')}")
    if body.get("status") == "executed":
        print(f"seal root: {body['sealRoot']}")
        print(f"anchored:  {body['anchorReceipt']['location']}")
        print(f"substrate: {json.dumps(body['substrate'])}")
    else:
        print(f"rejected at gate {body.get('atGate')}: {body.get('reason')}")
        sys.exit(2)

    verify = client.ledger_verify()
    print(f"ledger:    merkle_ok={verify['merkle']['ok']} "
          f"anchor_ok={verify['anchor']['ok']} "
          f"entries={verify['merkle']['entries']}")


if __name__ == "__main__":
    main()
