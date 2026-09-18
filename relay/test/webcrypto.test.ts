import { expect, it } from "vitest";
import vector from "../../app/src/test/resources/device-protocol-vector.json";

it("WebCrypto decrypts the same HKDF/AES-GCM vector as Android", async () => {
  const decode = (s: string) => Uint8Array.from(atob(s.replaceAll("-", "+").replaceAll("_", "/")), c => c.charCodeAt(0));
  const utf8 = new TextEncoder();
  const e = vector.envelope;
  const input = await crypto.subtle.importKey("raw", decode(vector.secret), "HKDF", false, ["deriveKey"]);
  const key = await crypto.subtle.deriveKey({ name: "HKDF", hash: "SHA-256",
    salt: utf8.encode(`HyperLPA/pair/v1/${e.pair}`), info: utf8.encode(`${e.from}/${e.to}`),
  }, input, { name: "AES-GCM", length: 256 }, false, ["decrypt"]);
  const plaintext = await crypto.subtle.decrypt({ name: "AES-GCM", iv: decode(e.nonce), tagLength: 128,
    additionalData: utf8.encode([e.v, e.id, e.from, e.to, e.pair, e.issued, e.expires].join("\n")),
  }, key, decode(e.body));
  expect(new TextDecoder().decode(plaintext)).toBe(vector.plaintext);
});
