# ahiropg-online

A Velocity plugin that does one thing: when a player connects, it decides whether
this login actually is a **premium (Mojang-authenticated) session** and hands the
correct UUID to the backend.

| Verdict | UUID forwarded to the backend | users.yml |
| --- | --- | --- |
| Premium session verified | the Mojang UUID | name recorded, `premium: true` |
| Not verified | Velocity's offline UUID | name recorded, `last-mode: 'offline'` |

Worked example with `NapCatt_owo`:

- premium UUID `5c89121b-4e93-46bd-80c9-3022a01c5bbd`
- offline UUID `0b5461e0-ea56-3019-8b05-6f4330752d8b`

> Casing matters. Offline UUIDs are derived from the name, and `NapCatt_owo`
> (capital C) is the canonical spelling; `Napcatt_owo` hashes to a different UUID
> (`7bea7cfe-…`). The plugin always uses the name the client logged in with and
> never normalises it.

---

## How "is this premium" is decided

### The client declares a UUID

Since 1.20.2 a client **always** sends its own UUID in LoginStart, which Velocity
exposes as `PreLoginEvent#getUniqueId()`:

| How the player logs in | Declared UUID |
| --- | --- |
| Premium launcher, premium login | the account's real Mojang UUID |
| Offline login / cracked launcher | the offline UUID it computed (`OfflinePlayer:<name>`) |
| Third-party auth (authlib sites) | that site's own UUID |

So "this client means to log in as premium" is decidable without guessing at names:

1. declared UUID **==** offline UUID → clearly an offline login → **allowed as offline**
2. declared UUID **==** the premium UUID of that name → **handed to Velocity for real
   verification**: passing yields the premium UUID, failing means an impostor and
   Velocity kicks him
3. anything else (old client with no UUID, third-party site UUID, name unknown to
   Mojang) → falls back per configuration, and by default nobody is kicked

### Verification itself is Velocity's job

Premium session verification (the encryption handshake plus a `hasJoined` call to
Mojang) **can only be done by Velocity**. The plugin never fakes it.

On an offline-mode proxy the plugin calls `forceOnlineMode()` on connections that
claim to be premium, which is the official Velocity switch for "verify this one
connection even though the proxy is offline". The result comes back through
`GameProfileRequestEvent#isOnlineMode()`.

The Mojang API (`api.mojang.com/users/profiles/minecraft/<name>`) is only used to
learn the canonical premium UUID for a name, to record it in `users.yml` and to
sanity-check the UUID the session produced. Lookups are asynchronous, so offline
connections never wait on them.

---

## What happens on a mixed-mode network

With an offline-mode proxy and the default `session-policy: HYBRID`:

| Situation | Verdict | Outcome |
| --- | --- | --- |
| Premium account, premium client | premium | Mojang UUID `5c89…` |
| Premium account, but logging in offline this time | offline | offline UUID `0b54…` plus a session-dropped notice |
| Regular player whose name matches a premium account | offline | offline UUID, joins normally, no notice |
| Third-party auth launcher | offline | offline UUID, joins normally |
| Cracked client forging a premium UUID | — | fails the handshake, kicked by Velocity |

**Name collisions are a non-issue.** Whether a name belongs to a premium account no
longer decides admission; what decides it is how the client declares it wants to log
in. A non-premium player using a premium account's name sends the offline UUID and
takes the offline path, indistinguishable from any other offline player.

**The notice** is only sent to players who have joined premium before (`premium: true`
in `users.yml`) and are offline this time, i.e. their session probably dropped
(expired token, launcher switched to offline login, new device). Pure offline players
who were never premium never see it.

---

## Policies

### `HYBRID` (default)

Per-connection decisions as described above. Three fine-grained switches under the
`hybrid` section of `config.yml`:

| Option | Effect |
| --- | --- |
| `allow-offline-connections` | whether offline connections are accepted. `false` = premium-only network; offline connections are rejected with `denied-message` |
| `legacy-client-policy` | what to do with clients 1.19.2 and older (they declare no UUID): `offline` / `premium` |
| `mojang-unavailable-policy` | which way to fall back when the Mojang lookup fails: `offline` (default, never kicks by mistake) / `premium` |

### `PROXY`

No interference at all; the login mode is entirely whatever `velocity.toml`
`online-mode` says. The plugin only records and forwards the UUID. Use this if the
network is already premium-only, or if another plugin manages login.

---

## How the backend gets the UUID

**Route 1: the login handshake (works out of the box).** When Velocity connects to a
backend it forwards the player's GameProfile, UUID included, and the plugin has
already set that UUID to its verdict during `GameProfileRequestEvent`
(`forward.override-uuid: true`). The backend therefore has the right UUID the moment
the player joins, with nothing extra to install.

> Why overwrite at all? On an offline proxy the UUID depends on what the client
> declared and on whether some other identity plugin rewrote the profile. Setting it
> explicitly guarantees the backend receives exactly the UUID we decided on.

**Route 2: a plugin message**, for backend plugins that want to double-check or
record the identity themselves. Once the player is on a backend, `ahiropg:online` is
pushed:

```
byte     protocol-version  currently 1
UTF      player-name
UTF      uuid              dashed, canonical form
boolean  premium           true = this login was a verified premium session
```

`UTF` is `DataOutputStream#writeUTF` (2 byte length + UTF-8 bytes). Backend side:

```java
public final class OnlineBridge extends JavaPlugin implements PluginMessageListener {

    private static final String CHANNEL = "ahiropg:online";

    @Override
    public void onEnable() {
        getServer().getMessenger().registerIncomingPluginChannel(this, CHANNEL, this);
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (!CHANNEL.equals(channel)) {
            return;
        }
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(message))) {
            byte version = in.readByte();
            String name = in.readUTF();
            UUID uuid = UUID.fromString(in.readUTF());
            boolean premium = in.readBoolean();
            getLogger().info("{} uuid={} premium={} (protocol {})", name, uuid, premium, version);
        } catch (IOException e) {
            getLogger().warning("Failed to parse an ahiropg:online message: " + e);
        }
    }
}
```

Keep the channel name in sync with `forward.channel` in `config.yml`.

---

## Installation

Grab `ahiropg-online-<version>.jar` from
[Releases](../../releases) and drop it into the proxy's `plugins/`, or build it
yourself:

1. Open the `online` directory (`pom.xml`) and run `mvn package`; the jar appears at
   `target/ahiropg-online-<version>.jar`
2. Drop it into the proxy's `plugins/`
3. Start once to generate `plugins/ahiropg-online/config.yml`
4. Adjust the config if needed and restart

Requirements: Velocity 3.x (built against `velocity-api 3.4.0`) and Java 17+.
For Velocity 4.x, bump `velocity.version` in `pom.xml`; the API surface this plugin
uses is identical in 3.x and 4.x.

### Releasing

The version is defined once, in the `<revision>` property of `pom.xml`. From there it
drives the jar name and the version inside `velocity-plugin.json` (a filtered
resource). The one exception is the `@Plugin` annotation, because annotation values
must be compile-time constants; if it ever drifts from the descriptor, the plugin
logs a warning at startup rather than reporting two different versions.

To cut a release:

```bash
# 1. bump <revision> in pom.xml and the version in @Plugin, then commit
# 2. tag and push
git tag v1.0.1
git push origin v1.0.1
```

Pushing a `v*` tag runs [`.github/workflows/release.yml`](.github/workflows/release.yml),
which builds the jar, fails if the tag and the pom version disagree, and publishes a
GitHub Release with the jar attached and generated release notes.

### users.yml

Written to `plugins/ahiropg-online/users.yml` and maintained automatically:

```yaml
players:
  'napcatt_owo':
    name: 'NapCatt_owo'
    premium: true
    premium-uuid: '5c89121b-4e93-46bd-80c9-3022a01c5bbd'
    offline-uuid: '0b5461e0-ea56-3019-8b05-6f4330752d8b'
    last-uuid: '0b5461e0-ea56-3019-8b05-6f4330752d8b'
    last-mode: 'offline'
    first-seen: '2026-11-07 19:51:37'
    last-seen: '2026-11-07 20:15:04'
```

- keys are lowercase names; `name` keeps the original casing (offline UUIDs depend on it)
- `premium` means the account has **ever** passed premium verification. It never goes
  back to `false`, and it is what decides whether the session-dropped notice is sent
- `last-mode` / `last-uuid` describe the most recent login only
- it is not written on every login: changes are flushed every 5 seconds and once on
  shutdown, via a temporary file plus an atomic move

---

## Log output

```
[ahiropg-online] Registered plugin messaging channel ahiropg:online
[ahiropg-online] ahiropg-online loaded: policy=HYBRID, proxy online-mode=false, records file=plugins/ahiropg-online/users.yml
[ahiropg-online] HYBRID: a client claiming to be premium (1.20.2+ always sends a UUID at login) is required to pass premium session verification; offline clients join with their offline UUID (allow-offline-connections=true)
[ahiropg-online] The proxy is in offline mode, so this plugin calls forceOnlineMode() on premium connections and lets Velocity run its own session verification.
[ahiropg-online] NapCatt_owo claims a premium client (UUID 5c89121b-…) -> handing over to Velocity for premium session verification
[ahiropg-online] NapCatt_owo passed premium session verification -> forwarding premium UUID 5c89121b-4e93-46bd-80c9-3022a01c5bbd
[ahiropg-online] NapCatt_owo did not pass premium session verification -> forwarding offline UUID 0b5461e0-ea56-3019-8b05-6f4330752d8b (this account used to join as premium)
```

---

## Known limitations

1. **Clients 1.19.2 and older declare no UUID**, so intent cannot be determined and
   `legacy-client-policy` decides. 1.20.2 shipped in September 2023, so this rarely
   matters.

2. **An impostor forging a premium UUID gets kicked, not admitted.** He could have
   joined honestly with his offline UUID; forging only gets him forced into an
   encryption handshake he cannot complete, and Velocity disconnects him.

3. **Third-party auth launchers count as offline.** They declare the site's UUID, so
   they get the offline UUID rather than being rejected for using a premium name. If
   you run such a setup, make sure the backend accepts offline UUIDs.

4. **The Mojang API is rate limited.** When a lookup fails the plugin falls back per
   `mojang-unavailable-policy` (offline by default, so nobody is kicked by mistake)
   and logs a warning. The session's own UUID still fills `users.yml`.

5. **`users.yml` is a historical ledger**, not a statement that a player is currently
   trustworthy. The only source of truth is the session verification of the current
   login.

---

## Project layout

```
online/
├── pom.xml
├── .github/workflows/release.yml            tag push -> build -> GitHub Release
└── src/main/
    ├── java/com/ahiropg/online/
    │   ├── AhiropgOnlinePlugin.java         entry point, wires the components together
    │   ├── auth/
    │   │   ├── Identity.java                the verdict for one login
    │   │   ├── ModeDecision.java            what to do with one connection
    │   │   ├── SessionPlanner.java          the pure-function decision table
    │   │   ├── MojangApi.java               async premium UUID lookup, cached and timed out
    │   │   └── OfflineUuids.java            Velocity's offline UUID rule
    │   ├── config/Settings.java             in-memory model of config.yml
    │   ├── forward/BackendMessenger.java    pushes the identity to the backend
    │   ├── listener/IdentityListener.java   main flow (PreLogin -> GameProfile -> post-login -> backend)
    │   ├── user/UserStore.java              users.yml reading and writing
    │   └── util/Texts.java                  config text to Component
    └── resources/
        ├── config.yml
        └── velocity-plugin.json
```

## License

MIT. See [LICENSE](LICENSE).
