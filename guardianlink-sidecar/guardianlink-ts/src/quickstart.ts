/**
 * GuardianLink sidecar quickstart — TypeScript.
 *
 *   GUARDIANLINK_HMAC_SECRET=<hex> npm run quickstart
 *
 * Runs the full governed flow against a local sidecar, including the voice
 * ceremony (spoken here by the demo-voice synthesis port — a real
 * deployment captures microphone audio instead).
 */
import { GuardianLinkClient, Action } from "./client";
import { speakPhrase, pcmToB64 } from "./demoVoice";

async function main(): Promise<void> {
  const secret = process.env.GUARDIANLINK_HMAC_SECRET;
  if (!secret) {
    console.error("set GUARDIANLINK_HMAC_SECRET to the sidecar's pre-shared key");
    process.exit(1);
  }
  const baseUrl = process.env.GUARDIANLINK_URL ?? "http://localhost:8080";
  const client = new GuardianLinkClient(baseUrl, secret);

  const action: Action = { type: "write", recordId: "profile", fields: { name: "Neo" } };
  console.log(`action:    ${JSON.stringify(action)}`);

  const result = await client.governed(action, (challengeId, phrase) => {
    console.log(`challenge: ${challengeId} — "${phrase}"`);
    return pcmToB64(speakPhrase(challengeId));
  });
  const body = result.body;
  console.log(`http:      ${result.httpStatus}`);
  console.log(`status:    ${body.status}`);
  if (body.status === "executed") {
    console.log(`seal root: ${body.sealRoot}`);
    console.log(`anchored:  ${body.anchorReceipt.location}`);
    console.log(`substrate: ${JSON.stringify(body.substrate)}`);
  } else {
    console.log(`rejected at gate ${body.atGate}: ${body.reason}`);
    process.exit(2);
  }

  const verify = await client.ledgerVerify();
  console.log(
    `ledger:    merkle_ok=${verify.merkle.ok} anchor_ok=${verify.anchor.ok} entries=${verify.merkle.entries}`
  );
}

main().catch((e) => {
  console.error(e);
  process.exit(1);
});
