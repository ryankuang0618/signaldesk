# Deploying SignalDesk (Neon + Render + LINE)

SignalDesk is a **LINE chat bot**: it ingests disclosed trades + news, generates AI briefings each
morning, and answers on demand in LINE. One Docker image runs the whole thing at a single URL.
Stack: **Neon** (Postgres, free) + **Render** (the app) + **LINE Messaging API** (the interface).

> ✅ Deploy on Render's **Starter** instance (~$7/mo). Unlike Free, it **never sleeps**, so the LINE
> webhook is always warm and reply tokens don't expire on a cold start. (Neon's free Postgres also
> never sleeps.) The Free instance's 15-min spin-down makes the bot miss or fail the first message
> after idle — don't use it for the webhook.

## 0. Secrets never go in git

Every secret is read from an environment variable and set in **Render's Environment panel**
(encrypted at rest). Nothing secret is committed. The app is password-protected in prod (basic
auth) **except** the LINE webhook, which is authenticated by its `X-Line-Signature` HMAC instead.

## 1. Database — Neon (free)

1. Sign up at <https://neon.tech> → **Create project** (region near you).
2. Copy **Host**, **Database name**, **User**, **Password**.
3. The JDBC URL for Render is `jdbc:postgresql://<host>/<database>?sslmode=require`.

Flyway creates the schema automatically on first boot.

## 2. LINE — Messaging API channel

1. <https://developers.line.biz/console/> → create a **Provider** → **Messaging API** channel.
2. From the channel settings, grab two secrets:
   - **Basic settings → Channel secret** → `LINE_CHANNEL_SECRET` (verifies inbound webhooks)
   - **Messaging API → Channel access token** (issue a long-lived one) → `LINE_CHANNEL_TOKEN`
3. **Messaging API tab:**
   - **Webhook URL** → `https://<name>.onrender.com/api/line/webhook` → **Verify** (do this after
     the app is deployed in step 3).
   - **Use webhook** → ON.
   - Turn **Auto-reply messages** and **Greeting messages** OFF (so they don't collide with the bot).
4. Add the bot as a friend (scan the channel's QR code). To get your own **`LINE_USER_ID`** (the
   push target for proactive alerts), message the bot once and read it from the webhook logs, or use
   the LINE console's "Your user ID" under Basic settings.

## 3. Push the code to GitHub

```bash
git add .
git commit -m "SignalDesk LINE bot"
git push
```

(Confirm no secrets are staged: `git grep -nE "sk-ant|Bearer|apikey" -- . ':!*.md'` should be empty.)

## 4. Deploy on Render

1. <https://render.com> → **New → Web Service** → connect your GitHub repo.
2. Render detects the **Dockerfile** — Runtime: **Docker**. Instance type: **Starter**.
3. Add **Environment Variables**:

   | Key | Value |
   |---|---|
   | `SPRING_PROFILES_ACTIVE` | `prod` |
   | `APP_SECURITY_USERNAME` | a username you choose |
   | `APP_SECURITY_PASSWORD` | a strong password you choose |
   | `SPRING_DATASOURCE_URL` | `jdbc:postgresql://<neon-host>/<db>?sslmode=require` |
   | `SPRING_DATASOURCE_USERNAME` | your Neon user |
   | `SPRING_DATASOURCE_PASSWORD` | your Neon password |
   | `ANTHROPIC_API_KEY` | your key |
   | `FINNHUB_API_KEY` | your key |
   | `FMP_API_KEY` | your key |
   | `ALPACA_API_KEY_ID` | your market-data key (optional; enables momentum/RS/liquidity) |
   | `ALPACA_API_SECRET_KEY` | your market-data secret |
   | `LINE_CHANNEL_TOKEN` | your token |
   | `LINE_CHANNEL_SECRET` | your channel secret |
   | `LINE_USER_ID` | your `U...` id (for proactive alerts) |
   | `BRIEFING_TIMEZONE` | e.g. `Asia/Taipei` (when the daily 08:00 run fires) |

4. **Create Web Service.** Render builds the image and deploys.
5. Back in the LINE console, set the **Webhook URL** to `https://<name>.onrender.com/api/line/webhook`
   and click **Verify** — it should succeed (returns 200).
6. Message your bot on LINE: send **`today`**, **`help`**, a ticker like **`NVDA`**, or **`stats`**.

## Notes

- `PORT` is provided by Render automatically; the app binds to it.
- The daily briefing run is `BRIEFING_CRON` (default `0 0 8 * * *`) in `BRIEFING_TIMEZONE`. Set
  `BRIEFING_AUTO_RUN=false` to disable auto-generation (then briefings only come from a manual
  `POST /api/briefings/generate`).
- Memory: the Dockerfile caps the JVM heap for the 512MB Starter instance. If logs show OOM kills
  during the morning generation run, move up to Standard (2GB) and raise `-XX:MaxRAMPercentage`.
- LINE quota: **reply** messages (answering the user) are free/unlimited; **push** (the proactive
  daily alert) is capped at ~200/month on the free LINE plan — one alert/day stays well under.
- Local dev needs no password (security is `prod`-profile only): `./run.sh`. To test the webhook
  locally, expose it with a tunnel (e.g. `ngrok http 8080`) and point the LINE Webhook URL at it.
