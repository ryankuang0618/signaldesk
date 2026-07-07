# SignalDesk

Personal stock-signal dashboard: tracks what proven traders are buying/selling, cross-references
company news, and uses Claude to surface daily research signals. Backend: Spring Boot + PostgreSQL.

> ⚠️ Research tool only. AI output is **not** financial advice, and disclosed-trade data is delayed by design.

## What's here

**Phase 1 — foundation**
- Spring Boot 3.4 app (Java 21, Maven)
- PostgreSQL schema via Flyway (`src/main/resources/db/migration`)
- JPA entities + repositories for the full data model:
  `trade_signal`, `context_event`, `news_item`, `briefing`, `alert`, `portfolio_position`, `tracked_actor`
- Seeded tracked actors (Buffett, Burry, Ackman, Dalio, two Congress members)
- A health endpoint that proves the whole stack is wired up

**Phase 2 — insider (Form 4) ingestion**
- `tracked_issuer` watchlist (AAPL, MSFT, NVDA, TSLA, AMZN, META)
- `EdgarClient` (throttled, SEC User-Agent) + `Form4Parser` (namespace-free XPath)
- Scheduled poller: pulls recent Form 4 filings on startup, then every 30 min
- Normalizes each filing into a deduplicated `trade_signal` (BUY/SELL + confidence),
  where open-market purchases/sales (codes P/S) score higher than grants/withholding
- `/api/signals` endpoints (see below)

**Phase 3 — Congress-trades ingestion**
- `CongressClient` → FMP `stable/senate-latest` + `house-latest` (free tier: `limit` ≤ 25, `page` = 0)
- Normalizes each disclosed trade into the same `trade_signal` shape (Purchase→BUY, Sale→SELL),
  deduped on a SHA-256 of `link|symbol|date|type|name` (the source has no per-trade id)
- Scheduled poller (startup + every 30 min); **disabled gracefully if no FMP key is set**
- Requires a free FMP API key (see Config below)

**Phase 9 — LINE alerts**
- High-confidence BUY/SELL briefings (≥ threshold) become `alert`s and are pushed to LINE via
  the Messaging API; deduped one per ticker+signal+day
- Alerts are recorded even without LINE configured (viewable in `/api/alerts` and the dashboard);
  LINE is just the delivery channel. `LineClient` + `AlertService`, runs after each briefing
- Dashboard **Alerts** panel with a LINE on/off badge and pushed/queued state

**Phase 7 — enrichment (context sources)**
- Populates `context_event` with the validation layer the AI weighs against signals:
  analyst recommendations, earnings surprises, and fundamentals (Finnhub, free) + 8-K material
  events (SEC EDGAR, free) — deduped by `(type, ref)`
- Wired into the briefing prompt as a Context section — measurably sharpens the AI's read
- `/api/context` endpoints (see below); `EnrichmentService` + poller (startup + every 6h)

**Phase 8 — AI briefing (Claude)**
- `ClaudeBriefingClient` (Anthropic Java SDK, **`claude-opus-4-8`**) + `BriefingService`
- For the most active tickers, gathers recent signals + news, asks Claude to weigh them, and
  stores a labeled `Briefing` (BUY/SELL/HOLD + confidence + summary) — **research, not advice**
- Manual-trigger only (no auto-run) to avoid surprise API spend; **disabled gracefully without
  an `ANTHROPIC_API_KEY`**
- `/api/briefings/*` endpoints (see below); dashboard has an AI panel + Generate button

**Phase 6 — live push (WebSocket)**
- STOMP-over-WebSocket endpoint at `/ws`; server broadcasts ingestion results to `/topic/updates`
- Every poller and the manual refresh publish `{source, newCount, at}` via `LiveUpdatePublisher`
- Dashboard connects with `@stomp/stompjs`, shows a **Live** indicator, and auto-refetches when
  new items land — no manual refresh needed

**Phase 5 — company news + first dashboard**
- `FinnhubNewsClient` + `NewsIngestionService` — fetches company news for tracked/active tickers,
  deduped by URL; **disabled gracefully without a Finnhub key**
- `/api/news` endpoints (see below)
- **React dashboard** (`frontend/`, Vite) — trade-signals feed (filter by source), tracked-funds
  panel with expandable holdings, and a news panel. Talks to the backend via a Vite dev proxy.

**Phase 4 — 13F hedge-fund holdings**
- `fund_holding` table + `ThirteenFClient`/`ThirteenFParser` (SEC EDGAR, namespace-aware info table)
- Ingests the two most recent quarters per fund, then **diffs them** to derive change signals
  (NEW/INCREASE→BUY, EXIT/DECREASE→SELL), stored as low-confidence (0.30) `THIRTEEN_F` signals
- 13F reports by CUSIP (no ticker), so changed positions are mapped **CUSIP→ticker via OpenFIGI**
  (free, no key) and cached in `cusip_ticker`
- Slow poller (startup + every 6h — 13F only changes quarterly)
- `/api/funds` + `/api/funds/{cik}/holdings` (see below)
- *Known limitation:* holdings show tickers only for positions that changed (we don't map every
  CUSIP, to respect OpenFIGI limits) — stable holdings show issuer name only.

## Prerequisites

PostgreSQL is already installed. You also need Maven and **JDK 21 specifically**:

```bash
brew install maven openjdk@21
```

> ⚠️ **Use JDK 21, not a newer JDK.** Homebrew's default `openjdk` (currently 26)
> silently breaks Lombok — getters/setters never get generated and every entity
> serializes as `{}`. `run.sh` pins `JAVA_HOME` to `openjdk@21` for you.

## One-time database setup

```bash
brew services start postgresql@14   # if it isn't already running
createdb signaldesk
```

Connection defaults (in `src/main/resources/application.yml`) assume the Homebrew setup:
host `localhost`, db `signaldesk`, user = your macOS username, empty password.
Override with `DB_HOST` / `DB_PORT` / `DB_NAME` / `DB_USER` / `DB_PASSWORD` env vars if needed.

## Config — FMP API key (for Congress data)

Two optional free API keys enable extra sources. Set them as env vars — **never commit them**:

```bash
export FMP_API_KEY=your_key_here        # Phase 3 — Congress trades (financialmodelingprep.com)
export FINNHUB_API_KEY=your_key_here    # Phase 5 — company news (finnhub.io)
export ANTHROPIC_API_KEY=your_key_here  # Phase 8 — AI briefing (console.anthropic.com)
export LINE_CHANNEL_TOKEN=your_token    # Phase 9 — LINE alerts (developers.line.biz)
export LINE_USER_ID=your_user_id        # Phase 9 — your LINE user id (push target)
```

Without a key, the app runs fine and simply skips that feature (logs a notice). Insider Form 4
and 13F need no key (SEC EDGAR is free).

## Run

**Backend** (terminal 1):
```bash
export FMP_API_KEY=your_key_here       # optional; enables Congress
export FINNHUB_API_KEY=your_key_here   # optional; enables news
./run.sh                               # pins JDK 21, then `mvn spring-boot:run`
```

**Dashboard** (terminal 2):
```bash
cd frontend
npm install      # first time only
npm run dev      # → http://localhost:5173  (proxies /api to :8080)
```

On startup, Flyway creates the schema and seeds the actors. Then:

```bash
curl localhost:8080/api/health
# {"status":"ok","trackedActors":6}

curl localhost:8080/api/actors
# the seeded funds + congress members

# Phase 2/3 — trade signals (insider Form 4 + Congress), pulled live on startup:
curl localhost:8080/api/signals                       # latest across all sources
curl "localhost:8080/api/signals?ticker=AAPL"
curl "localhost:8080/api/signals?source=CONGRESS"     # or INSIDER_FORM4 / THIRTEEN_F
curl -X POST localhost:8080/api/signals/refresh       # run all three ingestions now

# Phase 4 — 13F funds + holdings:
curl localhost:8080/api/funds                          # tracked funds + latest period
curl localhost:8080/api/funds/0001067983/holdings      # Berkshire's latest 13F holdings

# Phase 5 — company news (needs FINNHUB_API_KEY):
curl localhost:8080/api/news
curl "localhost:8080/api/news?ticker=AAPL"
curl -X POST localhost:8080/api/news/refresh

# Phase 7 — context events (analyst ratings, earnings, 8-K, fundamentals):
curl localhost:8080/api/context
curl "localhost:8080/api/context?ticker=NVDA"
curl -X POST localhost:8080/api/context/refresh

# Phase 8 — AI briefings (needs ANTHROPIC_API_KEY):
curl localhost:8080/api/briefings/status              # {enabled, model}
curl -X POST localhost:8080/api/briefings/generate    # generate for active tickers
curl localhost:8080/api/briefings/today

# Phase 9 — alerts (LINE delivery needs LINE_CHANNEL_TOKEN + LINE_USER_ID):
curl localhost:8080/api/alerts
curl localhost:8080/api/alerts/status        # {lineConfigured}
curl -X POST localhost:8080/api/alerts/process
```

## Layout

```
src/main/java/com/signaldesk/
├── domain/            # JPA entities + enums/
├── repository/        # Spring Data JPA repositories
└── web/rest/          # REST controllers
src/main/resources/
├── application.yml
└── db/migration/      # Flyway V1 (schema) + V2 (seed)
```

## Next: Phase 10 — Alpaca paper trading

Portfolio + safe fake-money buy/sell via Alpaca's paper API. Then Phase 11 (eToro / retail
sentiment). See the [build plan](https://claude.ai/code/artifact/0dc4f246-c8cd-474e-af96-6ba4f1b91cf1).
