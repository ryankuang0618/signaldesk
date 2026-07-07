# Deploying SignalDesk (free: Neon + Render)

One Docker image serves **both** the dashboard and the API at a single URL, behind a password.
Free stack: **Neon** (Postgres) + **Render** (the app). URL: `https://<name>.onrender.com`.

> ⚠️ Free Render web services **sleep after ~15 min idle** and cold-start (~30s) on the next
> request. Scheduled ingestion pauses while asleep and resumes when the app is hit. Fine for a
> personal/portfolio deployment. (Neon does not sleep — your data is always there.)

## 0. Secrets never go in git

Every secret is read from an environment variable and set in **Render's Environment panel**
(encrypted at rest). Nothing secret is committed — `.gitignore` excludes `.env` and local config.
The whole app is password-protected in prod (basic auth), so nobody with the URL can spend your
API credits.

## 1. Database — Neon (free)

1. Sign up at <https://neon.tech> → **Create project** (pick a region near you).
2. Copy the connection details. You need three values for Render:
   - **Host** (e.g. `ep-xxx.us-east-2.aws.neon.tech`)
   - **Database name** (e.g. `neondb`)
   - **User** and **Password**
3. The JDBC URL you'll set on Render is:
   `jdbc:postgresql://<host>/<database>?sslmode=require`

Flyway creates the schema automatically on first boot.

## 2. Push the code to GitHub

From the project root:

```bash
git init
git add .
git commit -m "SignalDesk"
git branch -M main
git remote add origin https://github.com/<you>/signaldesk.git
git push -u origin main
```

(Confirm no secrets are staged: `git grep -nE "sk-ant|Bearer|apikey" -- . ':!*.md'` should be empty.)

## 3. Deploy on Render

1. <https://render.com> → **New → Web Service** → connect your GitHub repo.
2. Render detects the **Dockerfile** — Runtime: **Docker**. Instance type: **Free**.
3. Add **Environment Variables** (the encrypted panel):

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
   | `LINE_CHANNEL_TOKEN` | your token |
   | `LINE_USER_ID` | your `U...` id |

4. **Create Web Service.** Render builds the Docker image and deploys.
5. Open `https://<name>.onrender.com` → the browser prompts for the username/password you set →
   the dashboard loads. Done.

## Notes

- `PORT` is provided by Render automatically; the app binds to it.
- To wake a sleeping free instance on a schedule, you can ping the URL with a free uptime monitor —
  but that also keeps ingestion running, so only do it if you want that.
- Custom domain (e.g. `signaldesk.com`) is a paid domain (~$10/yr) you can later attach in Render.
- Local dev is unchanged and needs no password (security is `prod`-profile only):
  `./run.sh` + `cd frontend && npm run dev`.
