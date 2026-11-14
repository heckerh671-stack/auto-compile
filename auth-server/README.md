# Zalard auth server (Cloudflare Worker)

Free accounts + server-held payload key. The jar ships only ciphertext; the key is handed out here to a
logged-in, non-banned account on its bound machine. Cracking the jar offline yields nothing — there is no
key in it. A leaked build is cut off by banning the account it traces to.

Free tier is plenty: Workers (100k req/day free) + KV (free allowance). No paid plan needed.

## Deploy (once)

1. Install the CLI and log in:
   ```
   npm i -g wrangler
   wrangler login
   ```
2. Create the KV namespace and paste its id into `wrangler.toml` (`id = "..."`):
   ```
   wrangler kv namespace create KV
   ```
3. Set the two secrets:
   ```
   # the payload key — tools/gen_guard.py prints this line; paste the 64 hex chars when prompted
   wrangler secret put PAYLOAD_KEY
   # a long random admin password you keep private
   wrangler secret put ADMIN_KEY
   ```
4. Deploy:
   ```
   wrangler deploy
   ```
   Note the URL it prints (e.g. `https://zalard-auth.<you>.workers.dev`). Put that in the client:
   set `AUTH_BASE` in `dev.zalard.auth.Auth`, or ship it in `zalard-auth.properties`.

## Rotating the key each release

`gen_guard.py` makes a fresh key per release and prints it. After building+sealing, update the secret so
the server hands out the new key:
```
wrangler secret put PAYLOAD_KEY   # paste the new 64-hex line
```
Old cracked payloads (sealed with the previous key) then stop decrypting.

## Admin

```
# ban (revoke) or restore an account
curl -X POST https://.../admin/ban   -d '{"admin":"<ADMIN_KEY>","username":"bob","banned":true}'
# clear a bound machine (user got a new PC)
curl -X POST https://.../admin/reset -d '{"admin":"<ADMIN_KEY>","username":"bob"}'
```

## What this does and doesn't do

- **Does:** keeps the key out of the jar; gates it behind a free login; binds an account to one machine;
  lets you ban a leaker so their build goes dark; rate-limits abuse.
- **Doesn't:** stop an authenticated user from capturing the key at runtime on their own machine and
  sharing the *decrypted* payload. That's a client-side fact of life — the answer is per-user watermarking
  + banning the traced account, not hiding the key better. Keep the native obfuscation (O-MVLL) on too.
