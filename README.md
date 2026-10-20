# TGXCode

A native Android client for the [claude-sessions](../claude-sessions) bridge.

It is the third client of that bridge, alongside the desktop web UI and the phone
web UI at `/m`. It reimplements the phone surface natively — same screens, same
decisions, same colours — because there is one thing a web page structurally cannot
do, and it happens to be the important one:

> `pool.hasViewer = () => clients.size > 0`. With no SSE client attached, an ask is
> **denied immediately** — there is nobody to ask.
> — `claude-sessions/docs/api.md`

A backgrounded browser tab is not reliably alive; `web/mobile.js` even tears its own
`EventSource` down on `visibilitychange`. So the moment the phone goes in a pocket,
sessions that ask for permission get auto-denied. A native app can hold the socket,
which means it needs no push infrastructure at all — **the app is the push channel**.

This project adds no code to `claude-sessions`. Anything it needs and cannot get from
`docs/api.md` is a gap in that contract, to be raised there rather than worked around
here.

## Setting up the toolchain

WSL starts with no JDK, no Android SDK and no Gradle. Everything below is native to
WSL — the build never crosses `/mnt/c`, which is 10–50× slower for the many-small-file
access that `aapt2` and `d8` do.

```bash
# JDK 21 (matches Android Studio's bundled JBR; required by Gradle 9 / AGP 9).
# A .tar.gz, so the missing `unzip` never comes up.
mkdir -p ~/.local/jdk && cd ~/.local/jdk
curl -fL -o t.tar.gz "https://api.adoptium.net/v3/binary/latest/21/ga/linux/x64/jdk/hotspot/normal/eclipse"
tar xzf t.tar.gz && rm t.tar.gz

# Android SDK. commandlinetools IS a zip — python3 has an extractor.
export ANDROID_HOME="$HOME/Android/Sdk"
mkdir -p "$ANDROID_HOME/cmdline-tools" "$ANDROID_HOME/licenses" && cd /tmp
curl -fLO https://dl.google.com/android/repository/commandlinetools-linux-15859902_latest.zip
python3 -m zipfile -e commandlinetools-linux-*.zip "$ANDROID_HOME/cmdline-tools/"
mv "$ANDROID_HOME/cmdline-tools/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest"
# python3 -m zipfile does NOT preserve the executable bit.
chmod +x "$ANDROID_HOME/cmdline-tools/latest/bin/"*
# Licences are already accepted on the Windows side; copying skips the prompt.
cp "/mnt/c/Users/Dylan Hays/AppData/Local/Android/Sdk/licenses/android-sdk-license" \
   "$ANDROID_HOME/licenses/"
sdkmanager --sdk_root="$ANDROID_HOME" \
    "platform-tools" "platforms;android-37.1" "build-tools;37.0.0"
```

Then, in the project:

```bash
. ./env.sh          # JAVA_HOME, ANDROID_HOME, PATH — project-local, not ~/.bashrc
./gradlew assembleDebug
```

`local.properties` (gitignored) carries `sdk.dir`.

## Getting it onto the phone

WSL runs in `networkingMode=mirrored`, so it sits directly on the LAN and the Linux
`adb` reaches the phone with no `/mnt/c` APK shuffling.

```bash
# Phone: Settings -> Developer options -> Wireless debugging -> Pair device with pairing code
adb mdns services                         # finds both ports; mDNS works from WSL
adb pair 10.11.64.40:<pairing-port> <6-digit code>
adb connect 10.11.64.40:<connect-port>    # a DIFFERENT port from the pairing one
./gradlew installDebug
```

Two things to expect. The pairing port and the connect port differ and both change
whenever Wireless debugging is toggled. And pairing is bound to the adb server's key
(`~/.android/adbkey`) — pairing from Windows `adb.exe` does not pair the Linux one.

If the Windows adb server has grabbed the device, `adb.exe kill-server` on that side.

## Pointing it at a bridge

The app skips the `/pair` cookie handshake entirely — `docs/api.md` says a native
client should store the token and send `Authorization: Bearer`. Pairing is therefore
just "get the token onto the device", and the friendliest way is to paste the whole
pairing link the desktop already generates.

Three ways in, all landing on the Settings form so the origin is visible before it is
trusted:

- **Paste** the link into the first field.
- **Share** it from the phone's browser (`ACTION_SEND`) — the path that works on every
  Android version today.
- An **App Link** on `/pair`, one exact host per origin, which lies dormant until the
  bridge serves `/.well-known/assetlinks.json` (still a 404 on both). Worth one static
  file upstream. It used to name `*.ts.net`, which was dormant twice over: Digital Asset
  Links verifies per host and has no wildcard form, so that filter could never have
  verified however many files upstream served.

Two origins are useful:

| Origin | When |
|---|---|
| `https://tgxcode.com` | **No VPN needed.** A named Cloudflare tunnel (`cloudflared` as a systemd service in WSL) with a real Let's Encrypt cert, so it works from any network without Tailscale installed. The bridge classes it as **remote**, so `bypassPermissions`/`dontAsk` are dropped and terminals are refused — correct, and the app agrees rather than pretending. Measured: `hello` at 0.29s, 36 chunks in 75s — it carries SSE perfectly well. May have Cloudflare Access in front; see below. |
| `http://127.0.0.1:45888` + `adb reverse tcp:45888 tcp:45888` | The dev loop. The phone's own loopback reaches the bridge over the adb transport, so the bridge stays bound to 127.0.0.1 and nothing needs LAN reachability. |

`https://tg-dylanh.taild5c420.ts.net` (`tailscale serve` in front of 45888) was the
remote origin before the tunnel. It is an **old machine** — don't pair against it.

Only the loopback row needs a cleartext exception. The tunnel has a publicly trusted
cert, so release builds reach it with no network-security config at all.

### Behind Cloudflare Access

Access puts a gate at Cloudflare's edge, in front of the tunnel. With a **Service
Auth** policy on it, every request has to carry a service token as two headers, and
the app sends them on every request: `/api/*`, health, the `/api/events` stream and
a notification's Allow/Deny alike.

```
CF-Access-Client-Id:     <client id>.access
CF-Access-Client-Secret: <client secret>
```

These are **in addition to** the bridge's own token, not instead of it. Access proves
who you are to Cloudflare, and the bearer token proves it to the bridge. The pairing
link carries only the bridge token, so the Access fields are entered separately and
pasting a link never touches them.

Getting the token:

1. Zero Trust dashboard → **Access controls → Service credentials → Service Tokens →
   Create Service Token**. Name it after the phone and pick a duration. Copy the
   **Client Secret** straight away, because it is shown only once.
2. In the Access application for `tgxcode.com`, add a policy with Action **Service
   Auth** that includes that token.
3. In the app: **Settings → Cloudflare Access (optional)**. Paste the Client ID and
   the Client Secret, then **Test connection**. The secret is stored the same way as
   the bridge token, encrypted with a KeyStore-bound key through `TokenCipher`, and it
   is masked on screen.
4. Only then switch Access on. Doing it the other way round locks the phone out with
   a response the bridge never sees, so it never logs it either.

Both fields empty means off. One field on its own is refused at Save, because Access
treats half a pair the same as none. Sending the headers to an origin with no Access
in front of it does no harm, since the bridge ignores them.

**When Access says no**, the app shows *Cloudflare Access refused this request*, a
screen of its own that is neither "not paired" nor "offline". The foreground
notification says so too. The stream and the poll both stop instead of retrying,
because nothing about the next attempt would differ. This matters more than it
looks: a blocked `/api/events` means the bridge has no viewer and is auto-denying
every ask, so the one thing the app must not do is sit on a dot that looks almost
connected. Fix the token in Settings and **Test connection** or **Save** reconnects
straight away.

A response counts as Access, not the bridge, if any one of these holds. They match
`docs/api.md` §*Cloudflare Access, when it is in front*, and the code is in
`AccessChallenge.kt`:

- a **3xx** whose `Location` is on `*.cloudflareaccess.com`, under `/cdn-cgi/access/`,
  or on any other host;
- a **401/403** carrying a `cf-access-*` header, or a `WWW-Authenticate` that names
  Cloudflare;
- a **401/403 on `/api/` that isn't JSON**. The bridge's own 401/403 are always
  `application/json`, so its `401 {"error":"unauthorized"}` still means *not paired*;
- **`text/html` on `/api/`** below 500, which is a login page served as a 200.

A **5xx HTML page** is the exception. Cloudflare's `530` "error 1033" means the tunnel
has no `cloudflared` behind it, which is reported as the bridge being unreachable,
not as a token problem.

**Redirects are off** on every client (`followRedirects(false)`), for two reasons.
OkHttp strips only `Authorization` when a redirect changes host, so following Access's
302 would hand the client secret to `cloudflareaccess.com`. And a followed redirect
lands on a 200 login page, which hides the refusal. Nothing legitimate is lost:
`docs/api.md` lists no `/api/` route that redirects (only `/pair`, which this app
never calls), and there is one `OkHttpClient` in the app. `api`, both stream flavours
and the notification client are all `newBuilder()`s of it, so the interceptor and the
redirect setting reach every request.

Release builds permit **no cleartext at all**; the debug variant overlays
`app/src/debug/res/xml/network_security_config.xml` with a short allowlist. That has to
be a source-set overlay — `<debug-overrides>` configures trust anchors only and cannot
enable cleartext.

## Staying connected in the background

Off by default, behind a toggle in Settings, because holding a socket open costs
battery and the user should opt into it knowing what it buys them.

When on, a foreground service takes a second lease on the *same* connection — the
bridge still sees one client, not two — and turns asks into notifications.

**Why `specialUse` and not `dataSync`.** `dataSync` is the semantic near-fit, but on
Android 15+ it is capped at six hours per 24, and the quota only refills when the app
is brought to the foreground. That defeats the exact case this exists for: a phone on
a table overnight. It also cannot be started from `BOOT_COMPLETED` at API 35+.
`specialUse` costs a Play Store review of the declared justification, which does not
apply to an app installed over adb. `onTimeout()` is implemented anyway as insurance.

Which asks can be answered without opening the app:

| Kind | Inline actions | Why |
|---|---|---|
| tool | **Allow**, **Deny** | `{requestId, decision}` is the complete answer; there is nothing to read first. |
| plan | **Keep planning** only | An *approve* must carry `mode`, or it agrees to the work and then blocks every edit in it. A deny needs no mode and is always safe. |
| question | none | Answers are keyed by exact question text and cannot fit on a notification. |

Three things have to agree about whether a notification should still exist, and the
third is the one that is easy to miss:

1. `permission-request` raises it.
2. `permission-resolved` cancels it — somebody answered at the desk.
3. **Reconnect reconciliation.** Nothing replays, so a resolution that fired into a
   closed socket is gone forever. After every board refresh, any notification the
   board no longer lists as blocked is cancelled. Without this, a phone that lost
   signal for ten minutes offers to allow something decided long ago — worse than no
   notification at all.

The `PendingIntent` trap worth naming: **its equality ignores extras**, and two asks
in one session share a notification id, so a reused intent would answer the
*previous* `requestId`. Each action uses `requestCode = (requestId + decision).hashCode()`
with `FLAG_UPDATE_CURRENT`.

## Phone installs

Agents on the desktop build APKs, and installing them over wireless debugging keeps
failing because Wireless debugging switches itself off. The app already holds an
authenticated connection to the bridge from any network, so the bridge keeps a
**phone installs** queue and the app installs from it with `PackageInstaller`. Developer
mode is not needed.

How it goes, all in `data/install/PhoneInstaller.kt`:

1. The bridge files a `pending` record and pushes it as `phone_install`. The app also
   reads `GET /api/phone-installs` on every (re)connect, at start, and every 20s while
   polling, because nothing is replayed to a client that was away.
2. **Claim** with `POST …/status {state: "downloading"}`. A `409` means another phone has
   it, so the app drops it. Claims are keyed on `X-TGXCode-Viewer`, a random id this
   install makes once and keeps. It is sent on that route only, because on any other
   route the header means "count this poller as a viewer".
3. **Download** to the cache, hashing as it goes, then check the size, the sha256, and
   the bridge's `X-SHA256` against the record. Any mismatch reports `failed`.
4. Read the package name and version from the APK, report `installing`, and commit a
   `PackageInstaller` session with `USER_ACTION_NOT_REQUIRED` (API 31+) and
   `setRequestUpdateOwnership(true)` (API 34+).
5. The session result comes back to `InstallResultReceiver` and is reported as
   `installed`, `needs_action` or `failed`. A quiet notification on **Install results**
   says how it ended.

**One tap per app, once.** A silent install only works for an update to a package
TGXCode is already the installer of record for, which includes TGXCode itself. The first
install of anything else, and any app last installed by adb or the Play Store, comes back
asking for confirmation. Background apps may not start activities, so that request
becomes a **Tap to install …** notification on **Installs to confirm**. If the app is on
screen the dialog opens straight away. After that one tap TGXCode owns the package, and
later builds go on silently. Android also needs the app being updated to target a recent
API level, and the minimum goes up with each Android release.

**Install unknown apps.** Settings has a switch, **Install apps sent from the desktop**
(on by default; when it is off, records are ignored), and a row showing whether TGXCode
is allowed to install apps, with a button that opens the system screen. If an install
arrives without that permission, the APK is downloaded and checked first. Then the
record goes to `needs_action` with a message saying what to turn on, and a notification
opens the setting. The install carries on by itself when the app next resumes with the
permission granted.

**Signature mismatch.** An APK signed with a different key from the installed copy
cannot be installed over it. A debug build from another machine is the usual cause.
`INSTALL_FAILED_UPDATE_INCOMPATIBLE` is reported as *already installed with a different
signing key — uninstall it on the phone first*. Nothing on this side can get around it.

**Updating TGXCode itself** kills the process that committed the install, so
`installing` is reported before the commit. Every claim is kept in DataStore. On the
next start the app compares any claim still `installing` with the package's
`lastUpdateTime` and `versionName`, then reports `installed`. A claim that is still
`installing` after 30 minutes with no evidence is reported `failed`.

Notifications need the notification permission, which the app asks for when *Stay
connected in the background* is turned on. Without it, installs still happen, but an
app that needs a tap waits silently.

## When the network will not carry a stream

SSE is best-effort; polling is the guaranteed path. Some transports buffer server-sent
events instead of passing them through, and the failure is **silent**: HTTP 200, the
right `Content-Type`, and then nothing at all until the connection closes. Measured on
`/api/events` against the same bridge:

| | Loopback | Named tunnel (`tgxcode.com`) | Quick tunnel (`*.trycloudflare.com`) |
|---|---|---|---|
| `hello` arrives | 0.04s | 0.29s | **never** |
| Pings at 25s/50s | on time | on time | never |
| Chunks in ~75s | 9 | 36 | **0** |

Cloudflare strips the `X-Accel-Buffering: no` header the bridge sets, so there is no
header or config that fixes it from either end.

**`hello` is the only way to detect it.** The bridge writes it the instant the stream
opens, before the ping timer even exists, so on any transport that works it lands in
well under a second. Silence cannot be used instead: `: ping` is an SSE *comment* and
`okhttp-sse` discards it without a callback, so an idle-but-healthy stream is
indistinguishable from a dead one. The decision is therefore made once, up front, on a
6s timeout — never by watching for silence later.

After that the app polls the delta routes the API already has: `GET /api/overview` for
the board every 5s, `GET /api/sessions/:id?tail=0` for liveness and
`GET /api/sessions/:id/since?offset=` for new events every 2.5s. Through the same tunnel
those measured ~62ms and 7KB for the board and **42 bytes** for an empty delta, so the
only real cost is that liveness becomes a couple of seconds granular instead of instant
— which for "has it finished, does it need me" is a distinction without a difference.

The connection dot becomes a **ring** rather than a filled circle, with a banner saying
the network will not carry a live stream, because an unexplained two-second lag reads as
a broken app.

**`?tail=0` needs a bridge on post-#31 `main`.** It landed in that PR; before it, the
guard was `want > 0`, under which `tail=0` falls through to a *full* read — the entire
transcript, no `truncated`, which polled every 2.5s is half a megabyte a tick and the
most expensive call on the route rather than the cheapest. Restart the everyday bridge
after pulling. Verify the shape against a dev bridge rather than 45888, as that repo's
CLAUDE.md asks.

### The socket is held open anyway, and that is the whole trick

`web/mobile.js` closes its `EventSource` before it starts polling. Doing that here would
have cost the app the one thing it exists for.

`clients.set(id, client)` happens at `bridge/server.js:813`, the instant `/api/events` is
handled and **before** the client has read a byte — the buffering is in Cloudflare,
downstream of the origin. So on a buffering transport the bridge still counts us as a
connected client and `pool.hasViewer()` is still true. Close that socket and `dropClient`
fires, `clients.size` goes to 0, and `bridge/runner.js:734` auto-denies every ask
immediately. The app would hold a perfectly current board and transcript while silently
losing every permission request.

So the stream stays open purely to be counted, and everything is read by polling. Two
consequences worth naming:

- The held socket uses `readTimeout(0)`, deliberately undoing the 40s dead-peer detector
  that the ordinary stream client argues for at length. On a buffering transport zero
  bytes is the *expected* steady state, so that timeout would close the load-bearing
  socket every 40 seconds. The poll replaces it as the liveness check, and notices a
  failure in 2.5s rather than 40.
- Backoff resets on the socket *opening* rather than on `hello`, which never comes.
  Otherwise every network blip would climb to the 30s ceiling while the socket is the
  only thing keeping asks answerable.

**Polling is not gated on the app being foregrounded.** `web/mobile.js` returns early
when its tab is hidden; copying that would undo the reason this app exists, since a phone
in a pocket is exactly when nobody is at the desk to answer instead.

Two smaller divergences, both because this client has surfaces `/m` does not: the
sessions list and the task badge get a slower 20s tier of their own (they normally ride
on `sessions-changed`, and nothing pushes in this mode), and the board is compared with
`at` nulled out before it is published, because `at` is the time the payload was built
and changes on every read — keying on it would rebuild the Sessions list under the
reader's thumb every few seconds. Recovery from the sticky decision is also better than
`/m`'s, which needs a page reload: changing the origin re-tests the transport, since a
different origin is genuinely a different question.

Reproducing a buffering transport takes one command and touches no bridge code:

```bash
cloudflared tunnel --url http://localhost:45888    # a *.trycloudflare.com origin
```

`PollingFallbackTest` covers the same ground without a network, using MockWebServer's
`bodyDelay` to hold a `text/event-stream` body open — which is exactly what the quick
tunnel does.

## Debugging a long-lived socket

```bash
adb logcat -s TGXCode:V
```

One tag for the whole app. A connection that reconnects on its own is close to
undebuggable without it — "it says offline" is not something you can act on. The log
covers lease acquisition, every `/api/events` attempt, `hello`, stream closures with
their cause, non-2xx responses, and the pairing probe's verdict.

The line that matters most on a new network is the watchdog's:

```
no hello in 6000ms — this transport is buffering the stream; polling instead
```

Its absence is the confirmation that the transport carries SSE. Every `/api/events`
attempt also logs `held=true|false`, which says which mode the socket is in.

## Things found while building this

Verified against the live bridge, and worth reporting upstream:

1. **`X-Claude-Sessions-Client: 1` is mandatory on every non-GET `/api/` route**
   *(Since closed: documented in `docs/api.md`, and renamed `X-TGXCode-Client` with
   the old name still accepted. `BridgeClients.kt` sends both.)*
   (`bridge/server.js:287`) — a CSRF guard that is not in `docs/api.md`. Without it
   the entire write surface 403s: subscribe, send, permission, create.
2. **`user.command` is an object**, `{name, args}`, not a string
   (`bridge/transcript.js:590`). `web/mobile.js:715` interpolates it into a template
   string, so a slash command renders there as `/[object Object]`.
3. **`stripEnvelope` does not strip the `<command-*>` tags**, so a slash-command user
   message is *entirely* bookkeeping XML in `text`. This client strips them and falls
   back to the parsed command.
4. **`result.patch` is a structured diff**, an array of hunks with pre-prefixed lines —
   `docs/api.md` lists it beside `text` and `stdout` as though it were another string.
5. **`/api/sessions` never includes `runner.pendingPermission`** (only
   `{state, activity, queued}`), so `mobile.js:525`'s ask branch can never fire and the
   web client's Sessions rows never show the ask dot. This client cross-references the
   board, which it already holds for the tab badge.
6. **`web/sw.js:54` answers a plan ask with no `mode`** — precisely what `docs/api.md`
   says is wrong, since the session is *in* plan mode while the card is up.
7. **`web/mobile.js` drops the `tool-result` event**, and this one is not cosmetic.
   `bridge/transcript.js:485` says it plainly — *"The call itself was in an earlier
   chunk — this happens on every live tail. Emit a patch the client applies to the
   tool block it already rendered."* `web/app.js:970` handles it; the phone client has
   no case for it, so its `default: return null` swallows it. A full transcript read
   merges results into the `tool` event, which is why an already-finished session
   looks fine — but **while you watch a session live, every tool keeps its spinner and
   never resolves.**
8. **Two more "documented as a string, sent as an object"**: `tool.agent` is a
   subagent descriptor (`{agentId, agentType, description, spawnDepth, …}`), and the
   send/create responses' `status` is a whole runner status. With `user.command` and
   `result.patch` that makes four, which is enough to suggest `docs/api.md`'s event
   table would benefit from types rather than field names.
9. **`web/mobile.js` has no suggestion, drafts or task surface at all**, so a
   follow-up an agent files is invisible from a phone browser unless you happen to have
   the conversation that raised it open. `renderSuggestion` draws the event; nothing
   collects them.
10. **A polling client is not a viewer, so falling back re-arms auto-deny.** The new
    "SSE is best-effort" section tells a client to fall back to polling, but not that
    dropping the stream removes it from `clients` — making `pool.hasViewer()` false and
    auto-denying every ask (`bridge/runner.js:734`). `web/mobile.js` closes its
    `EventSource` before `startPolling()`, so on a buffering transport `/m` loses every
    ask while looking healthy. Because the bridge registers the client at
    `bridge/server.js:813` before the first byte is read, holding the buffered socket
    keeps `hasViewer()` true, which is what this app does. But holding a socket that will
    never deliver anything is a workaround: the real fix is upstream, letting a polling
    client register as a viewer.
11. **`?tail=0` is documented in the wrong place.** PR #31 implemented it and explains it
    thoroughly in `bridge/server.js`, but the only mention in `docs/api.md` is inside the
    live-channel polling section. The `?tail=N` paragraph — where an implementer looks
    when building the call — still says only "returns only the last N events and adds
    `truncated`", from which `N=0` reads as "no limit" rather than "none of them". What it
    actually returns (`events: []`, `truncated`, the *same* `offset` as a full read, the
    full `runner` including `pendingPermission`) is pinned by `test/gate.test.js` and
    written down nowhere a client would find it.
12. **A `tail=0` response reports `truncated: {dropped: total, total}`.** Literally true,
    and misleading for the liveness call it was added for: a client that merges the
    response into an open conversation shows "N earlier events — load all" over a
    transcript it already holds, claiming every event is missing. Worth saying that only
    `runner` and `offset` should be read from a liveness read.
13. **The polling advice assumes a foreground tab.** `/m` gates its poll on
    `visibilityState`, and `docs/api.md` inherits the assumption. A client that has to
    stay answerable while backgrounded has no such gate, and the standing cost — a 7KB
    board GET every 5s, indefinitely — belongs beside the 62ms and 42-byte figures.
14. **A newly created session is not readable for ~3.5s.** `POST /api/sessions` returns
    a `sessionId` as soon as the process spawns, but `GET /api/sessions/:id` 404s until
    Claude Code has written its transcript. Navigating straight to the id — the obvious
    thing to do, and what this app did — reports "session not found" about a session
    being created perfectly well. Worth a sentence in `docs/api.md` under
    `POST /api/sessions`.

## Two surfaces `/m` does not have

`/m` has no task board and no drafts, and renders a `suggestion` only as a row inside
the conversation that raised it. So these two are not ports of anything — the shape is
this client's to choose, and both are chosen for a phone rather than shrunk from a desk.

**Tasks.** Suggested follow-ups: work an agent noticed but was not asked to do. The
desktop shows them in a panel beside the transcript *and* as a column on its task board;
neither fits here, so they get a tab, and the conversation still draws its own inline
where you meet an offer while it is being made. Starting one uses `plan`, as
`web/app.js:2004` does, and for the reason that matters: an agent-written prompt has had
no human read it as an instruction.

The chips — Open, Started, Dismissed — exist for one reason. **Undo needs somewhere to
happen.** A dismissed task is gone from the default view by definition, so without a way
to look at the dismissed ones, "dismiss" would be irreversible in practice however
reversible the bridge makes it.

**Drafts**, on the New tab rather than a board of their own. A draft *is* the body of
that form (`docs/api.md:695`), so a saved one is that screen with its fields already
filled; a list anywhere else would be a second place claiming to be where a session is
set up. `docs/api.md:121` names the case it exists for — *"setting work up at the desk
and releasing it from a phone when quota frees up"* — and this is the releasing half.

One thing worth knowing about the edit path. The phone has no title field, so
`PATCH /api/drafts/:id` is sent **without `title`**. The route is a genuine partial, so
omitting it preserves whatever was set at the desk. `model` and `effort` *are* sent now
that the form has a picker for each, with `inherit` as `""`, which the bridge stores as
`null`. That is how a phone clears a desk pick; a Kotlin `null` would be dropped from the
body by `explicitNulls = false` and change nothing. Covered by `DraftDecodeTest`.

`GET /api/taskboard` is deliberately unused. Its `suggested` column is
`GET /api/suggestions?status=open` verbatim, and its other three are a second answer to
what the Sessions tab already says — so subscribing to its push would send several
hundred cards every three seconds to keep one column fresh. The cost is that a newly
filed task is not pushed; `docs/api.md:855` names the substitute, and this client refetches
on `sessions-changed`, on `suggestion-changed`, on `hello` and on pull-to-refresh.

## Caught up with the desktop

The desktop moved on after this client was first written. These are its features as
they now appear here, and where to find each one:

| Feature | Where | Notes |
|---|---|---|
| Model and effort | the composer's pickers; the New tab; drafts | effort is always sent, `""` meaning inherit, because it is the one send field where absent and null differ |
| Edit and resend / Edit and branch | long-press one of your own turns | a branch sends `fork` + `fromUuid` (the turn's own id); the first turn becomes a new session in the same folder |
| Queue | the "N queued" chip | drop and reorder; a `handed` message keeps its place |
| Pin, archive, rename, delete | long-press a session row | an Archived chip lists the archived sessions |
| Standing line, stalled | session rows, Needs-you cards | a stall also raises a notification while the service runs; Stop on a stalled turn is a hard stop |
| Quota | the pill in the top bar | each reading's age is measured on the bridge's clock and greyed past 30 minutes |
| History | the bell | the notification log, with read watermarks |
| Checklist | the bar above the transcript | from `task-list` |
| Long transcripts | "N earlier turns — load more"; the turn list in the top bar | `?turns=8` and `/range`, holding any result whose call is not loaded yet |
| Subagents | "Open the subagent's transcript ›" on an Agent call | followed live with `subscribe {agent: {toolUseId, offset}}` |
| Changes, diffs, scratchpad | the conversation's ⋮ menu | |
| Send later | long-press Send | the mode is stored per message and never defaulted |
| Snippets | chips above the composer; Settings → Snippets | `{{params}}`, the three insert modes and auto-submit, by `popover.js`'s rules |
| Schedules | the calendar icon on the Tasks tab | only changed fields are PATCHed, so a `dontAsk` schedule can still be paused from here |
| Slash commands | type `/` | |
| Project colours | row stripe, card dot, a rule under the conversation's top bar | read-only: `PUT /api/prefs` is refused to remote callers |
| Attachments | the + beside the message box, and on the New tab; images and 📎 files in the transcript | Photo (the system photo picker) or File (the document picker), up to five a message; needs a bridge with [TGXCode#206](https://github.com/TheYearIsTwentyGX/TGXCode/pull/206), which lets a remote caller upload at a 10MB cap. Photos larger than 2048px or not PNG/JPEG/WebP are re-encoded as JPEG first, so a HEIC arrives as something the model can see. Drafts cannot hold files, so the New tab's Save refuses while any are attached |
| Orchestrators | an orchestrator's conversation opens on **Overview** (beside a Conversation tab); "Start as an orchestrator" and "Send to orchestrator…" on the New tab and on each draft; ⋮ → Make it an orchestrator / Orchestrator settings | see *Orchestrators* below |

### Orchestrators

`web/orchestrator-page.js` in one column: the goal and progress strip, usage meters
against the cutoff, **Needs you** (a pause with Resume, the brief's decisions answered
with their option chips or a free reply, workers stuck on an ask, failed workers),
**Tasks from you** with Take back, the brief, the workers with their six-stage track,
the merge order, and the inbox under Activity with Dismiss. Its rules —
`needsYou`, `workerStage`, `mergeOrder`, `progress` — are ported from
`web/orchestrator-model.js` into `ui/orchestrator/OrchestratorModel.kt` and pinned by
`OrchestratorTest`.

An answer to a decision is sent first, as `Re: <question>` in the orchestrator's own
mode, and recorded second, so nothing reads as answered unless it is on its way.
Settings save field by field as they change, with a `null` limit sent as `null`.

Workers are folded under their orchestrator: off the Sessions list (a search still finds
them), as rows inside its Needs-you card, and with a banner on their own conversation
leading back. A worker's ask its orchestrator still holds is notified silently and said
aloud once the board calls it stuck.

Left out, because the bridge refuses them to a remote caller: the worker drawer's
"So far" digest (`…/orchestrator/read`) and a task's worktree hint. Per-orchestrator
defaults live in prefs, which a phone cannot write either.

`StatusStore` (quota, history, colours), `WorkspaceStore` (scheduled messages,
snippets, schedules) and `OrchestratorStore` (payloads, the orchestrator list) hang off `BridgeRepository`'s `events`, `resyncs` and `pollRounds`
flows. So each feature owns its own state, and none of them adds a branch to the
repository's stream handler.

## Three tabs, and where "Needs you" went

Needs-you is a **section at the top of Sessions** rather than a tab of its own. "What
needs me" and "what else is there" are the same question at two zoom levels, and a tab
you have to leave to see the second half is a tab you check twice. The waiting badge
moved to Sessions with the cards — a badge pointing at a section of another tab sends you
to the wrong place. It hides the moment the filter has anything in it: a card that is on
the board because something is blocked is not a search result.

That leaves **Sessions · Tasks · New**.

## Deliberate divergences from `/m`

Everything else is parity. These four are improvements, and each fixes a real edge:

- **An expanded tool stays expanded when its result arrives.** `patchTool` in the web
  client replaces the DOM node wholesale, so a tool collapses at exactly the moment you
  wanted to read it. Here expansion lives outside the event list, so the result fills
  in underneath your finger.
- **`permission-resolved` compares `requestId`** before clearing the visible card.
- **The board is fetched once on connect.** `overview` is only *pushed* when it changes,
  which is right for bandwidth and wrong for a client that just attached — on a quiet
  minute the landing screen would sit on "waiting for the bridge" while six sessions run.
- **The buffered-stream socket is held open rather than closed.** The one divergence that
  is not a nicety: closing it, as `/m` does, makes the bridge stop counting a viewer and
  auto-deny every ask. See *When the network will not carry a stream* above, along with
  the three smaller differences in that mode.

## Not in scope

These are refused by the bridge for remote callers, so a button for them could never
work:
- the terminal and project commands;
- dev-server and DevBrowser control;
- bridge shutdown and restart;
- `bypassPermissions` and `dontAsk`;
- starting a session in a new worktree;
- editing prefs, including project colours;
- editing Claude Code's settings and `CLAUDE.md`.

Also out, by choice:
- slash-command completion on the New tab, which would use the `?cwd=` form;
- one-time (`at`) schedules and the pull-request gate's options in the schedule editor;
- the live board as a queue, and the task board's other columns, for the reason given
  above.

