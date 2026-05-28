# Twitch OAuth setup for CricRelay Stream

Connect a club Twitch channel so volunteers can **Go Live** from the CricRelay app (same flow as YouTube OAuth). Volunteers can still use **Custom RTMP** with a manual stream key without logging into Twitch on the phone.

## 1. Create a Twitch application

1. Log in at [Twitch Developer Console](https://dev.twitch.tv/console) with the **club’s Twitch account** (or an admin account that manages the channel).
2. **Register Your Application** → create an app, for example:
   - **Name:** `CricRelay Stream`
   - **Client type:** **Confidential** (required — **Public** apps have no Client Secret; CricRelay runs OAuth on the server like YouTube)
   - **OAuth redirect URLs:** add exactly:
     ```
     https://cricrelay.co.uk/dashboard/twitch/callback
     ```
     (Use your real `PUBLIC_BASE_URL` if different, e.g. staging.)
   - **Category:** Broadcasting / Sports (or closest fit).
3. Open the app → copy **Client ID** and create **Client Secret** (one-time display — save it).

## 2. OAuth scopes CricRelay requests

| Scope | Purpose |
|--------|---------|
| `channel:read:stream_key` | Read RTMP stream key for ingest |
| `channel:manage:broadcast` | Set stream title before going live |

No chat or moderator scopes are required.

## 3. Server environment (EC2 / GitHub Actions)

Set these secrets or files on the server:

| Variable | Example |
|----------|---------|
| `TWITCH_CLIENT_ID` | From Developer Console |
| `TWITCH_CLIENT_SECRET` | From Developer Console |
| `TWITCH_REDIRECT_URI` | Optional; default `https://cricrelay.co.uk/dashboard/twitch/callback` |

**GitHub repository secrets** (for deploy workflow):

- `TWITCH_CLIENT_ID`
- `TWITCH_CLIENT_SECRET`

Optional: `TWITCH_REDIRECT_URI` if not using the default callback path.

Deploy runs `deploy/configure-twitch-env.sh` and restarts the `cricket` service.

**Manual on EC2:**

```bash
sudo mkdir -p /app/secrets
echo -n 'YOUR_CLIENT_ID' | sudo tee /app/secrets/twitch_client_id
echo -n 'YOUR_CLIENT_SECRET' | sudo tee /app/secrets/twitch_client_secret
sudo chmod 600 /app/secrets/twitch_client_*
sudo bash /app/deploy/configure-twitch-env.sh
sudo systemctl restart cricket
```

Verify:

```bash
curl -s https://cricrelay.co.uk/api/stream/setup | jq .twitch_oauth_configured
# should be true
```

## 4. Connect in the dashboard (club admin)

1. Sign in to [cricrelay.co.uk](https://cricrelay.co.uk) → **Streams**.
2. **Connect Twitch** → approve in Twitch.
3. You should see the channel name and “stream key API OK” in the app after refresh.

## 5. Go live from the app

1. Install the latest **CricRelay Stream** APK.
2. Log in → open a stream → **antenna icon** → **Twitch (OAuth)** (or stay on **Volunteer: Studio stream key** for manual RTMP).
3. **Go Live** → allow screen capture → wait for “connected”.
4. Watch on `https://twitch.tv/<your-login>`.

**RTMP ingest (automatic via OAuth):**

- Server: `rtmp://live.twitch.tv/app`
- Stream key: from Twitch API (not shown in app UI; handled server-side)

## 6. Troubleshooting

| Issue | What to do |
|--------|------------|
| `twitch_oauth_configured: false` | Set client ID/secret and restart `cricket` |
| Redirect URI mismatch | Callback in Twitch Console must match `TWITCH_REDIRECT_URI` exactly |
| Stream key access failed | Reconnect; ensure scopes were granted; account must be allowed to broadcast |
| App still shows HTML error on scoring | Deploy latest server; re-login in app |

## 7. Twitch vs volunteer stream key

| Method | Who signs in | Best for |
|--------|----------------|----------|
| **Twitch OAuth** | Club once (dashboard + optional app refresh) | Same club Twitch every week |
| **Custom RTMP** | Nobody on phone | Rotating volunteers; paste key from Twitch Dashboard → Settings → Stream |
