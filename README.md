# ORISO AddonService

Connects ORISO with add-ons: optional services that bring their own backend and UI, are
discovered by the platform and enabled per Träger. Design: ADR-031 (draft,
[digisucht-oriso-kickoff](https://github.com/BITS-GmbH/digisucht-oriso-kickoff/blob/main/adr/ADR-031-addons-optional-services-with-their-own-ui.md)).

## What it does

| Route (behind `/service/addons`) | Who | What |
|---|---|---|
| `GET /registry` | logged-in users | Add-ons enabled in the request's context (see below), with only the apps whose slots the caller's role may see and the dynamic apps the add-on reports as visible |
| `/{name}/api/**` | logged-in users | Forwarded to the add-on's `/api/**` with a short-lived token signed by the AddonService instead of the Keycloak token |
| `GET /{name}/ui/**` | anyone | The add-on's bundle and assets (loaded with `import()`, which cannot send a token) |
| `GET /.well-known/jwks.json` | anyone | Public key add-ons use to verify the token |
| `GET /admin/addons?tenantId=` | admins of the Träger | Registered add-ons with their switches |
| `GET /admin/addons/{name}/tenants` | SuperAdmin | Every Träger with a switch or agency overrides for the add-on: `enabled`, `updatedAt`, `updatedBy`, `agenciesEnabled`, `agenciesDisabled` |
| `PUT /admin/addons/{name}/global` | SuperAdmin | Switch an add-on on or off for the whole platform (default off) |
| `PUT /admin/addons/{name}/tenants/{tenantId}` | SuperAdmin | Switch an add-on on or off for a Träger |
| `PUT`/`DELETE /admin/addons/{name}/tenants/{tenantId}/agencies/{agencyId}` | admins of the Träger | Override for one agency |
| `GET`/`PUT /admin/addons/{name}/settings?tenantId=&agencyId=` | admins of the Träger | Settings schema and stored values; PUT validates against the schema |
| `GET /admin/addons/{name}/stats?tenantId=` | admins of the Träger | The add-on's statistics with their labels |
| `GET /admin/audit?tenantId=&addon=&from=&to=&page=&size=` | admins | Audit entries of add-ons, newest first (see below) |
| `GET /calendar?from=&to=` | logged-in users | Calendar entries of the caller's add-ons (see below) |
| `GET /host/settings` | add-on backends | Effective settings (defaults, Träger, agency) for the add-on, Träger and agency of the add-on token |
| `POST /host/notifications` | add-on backends | Notification to the token's user or the session's client, delivered by UserService |
| `POST /host/audit` | add-on backends | Append-only audit entry |

ingress-nginx rewrites `/service/addons/...` to `/addons/...`, like the other services.

**Enablement:** off until a SuperAdmin switches an add-on on globally and enables it for a Träger. The global switch is off for newly discovered add-ons and turns the add-on off everywhere, whatever the Träger switches say. Within an enabled
Träger, agencies can be switched off (or on again). An agency switch alone has no effect.

**Agency context:** when the web app sends `X-Oriso-Agency-Id`, the AddonService checks with
UserService (`/users/data`, with the caller's token) that the caller belongs to that agency,
applies the agency override and passes `agencyId` in the add-on token. A counsellor's agencies
are `agencies[].id` of `/users/data`; a client's are `sessions[].agencyId` and
`consultingTypes.*.agency.id` (UserService 2.0.15 sends no `agencies` list for clients).

**Session context:** when a counsellor's request carries `X-Oriso-Session-Id`, the AddonService
asks UserService (`/users/consultants/sessions/{id}`, with the counsellor's token) whether the
counsellor may see the session, and passes `sessionId` and the client's `askerId` in the add-on
token. Without access the request gets 403. Clients' session headers are ignored.

**Token for add-ons** (RS256, audience = add-on name, 2 minutes): `sub`, `username`, `roles`,
`tenantId`, `agencyId`, `sessionId` and `askerId` (if any). Cookies, the Keycloak token and
client addresses are never forwarded. Add-on backends send this token to `/host/**`.

**What an add-on serves besides its manifest and `/api`, `/ui`:**
- `settings` (manifest): path of a JSON Schema for its settings. Supported: an object with
  `string` (`enum`, `maxLength`), `boolean`, `integer`/`number` (`minimum`, `maximum`)
  properties, `default`, `required`, `title`, `description`.
- `GET /stats?tenantId=`: called by the AddonService with a service token (role
  `addon-service`, `tenantId`), answers `{"values": {"<stat id>": <number>}}` for the stats
  declared in the manifest.
- `calendar` (manifest): path such as `/calendar`, see below.
- `GET /visibility`: needed when an app declares `"visibility": "dynamic"`, see Registry.

The full contract for notifications, audit entries and calendar entries is in
[docs/contract-v1-notifications-audit-calendar.md](docs/contract-v1-notifications-audit-calendar.md).

## Registry

`GET /registry` takes the same optional headers as the gateway and decides in three stages:

1. **Enablement.** With `X-Oriso-Session-Id` (counsellors only, verified with UserService) the
   session's agency decides and the agency header is not used; with `X-Oriso-Agency-Id` (verified
   membership) that agency decides; without either, the caller's own agencies decide and an
   add-on is listed if it is enabled for at least one of them. A caller without any agency
   (UserService reports none) sees what the Träger switch allows. If UserService cannot be asked
   for the caller's agencies, the answer is 502.
2. **Slot and role.** Only apps with a slot the caller's role can open, and only those slots.
3. **App state.** For every listed add-on with a `"visibility": "dynamic"` app the caller can
   see, the AddonService calls `GET <add-on>/visibility` in parallel with an add-on token for
   the caller (with `agencyId`, `sessionId` and `askerId` when the request had that context).
   The add-on answers `{"apps": {"goals": {"visible": true, "badge": 1}}}`. Dynamic apps
   missing from the answer or with `visible` other than `true` are hidden; on an error or after
   `addons.visibility.timeout` all dynamic apps of that add-on are hidden and its name is logged.
   A positive integer `badge` is passed on as `badge` of the app; static apps never have one.
   Answers are cached per user, add-on and context for `addons.visibility.cache-ttl`; failures
   are not cached. Forwarding a notification drops the cached answers of that add-on for the
   recipient, so a released tool appears on the next registry request.

## Host API

Add-on backends call these with the add-on token of the request they are handling.

**`POST /host/notifications`**: `recipient` is `self` (the token's `sub`) or `asker` (the
token's `askerId`; 403 without one). `title` (max 120) and `text` (max 500) are plain text per
locale with `de` required. `appId` must be an app of the add-on (404 otherwise) and
`target.slot` one of that app's slots that the recipient can open (`asker` means role `user`);
`consultant.session` and `consultant.asker-profile` need a `sessionId` in the token, which is
passed on. The AddonService forwards to UserService `POST /users/event-notifications/addon`
with a Keycloak client-credentials token of the backend-technical client (cached until 30 s
before it expires) and answers `202 {"id": ...}` with UserService's id, or 502 if UserService
or Keycloak fails.

**`POST /host/audit`**: `action` matches `[a-z0-9._-]{1,64}`, `summary` is plain text per locale
(`de` required, max 300), `subject` is `self`, `asker` (403 without `askerId` in the token) or
omitted, `appId` is optional (404 if unknown). The AddonService adds time, add-on, actor (id,
username, roles), Träger, agency, session and subject id from the token and stores the entry in
`addon_audit_entry`, which is never updated or deleted. Answers `201 {"id": ...}`.

## Audit log for admins

`GET /admin/audit` answers `{"items": [...], "page", "size", "total"}`, newest first; `size`
is 1 to 200 (default 50), `from` (inclusive) and `to` (exclusive) are ISO instants.
- SuperAdmin (`tenant-admin` of Träger 0): every Träger, or the one in `tenantId`.
- `tenant-admin` / `single-tenant-admin`: their own Träger only (another `tenantId` is 403).
- `agency-admin` / `restricted-agency-admin`: only entries written in their agencies, as
  UserService (`/users/data`) reports them.

## Calendar

`GET /calendar?from=&to=` (`from` before `to`, at most 92 days) asks every add-on that declares
`calendar`, is enabled for the caller's Träger (and agency, via `X-Oriso-Agency-Id`) and has an
app the caller's role can see, in parallel with a token for the caller (no session), 3 s each:
`GET <calendar>?from=&to=`. The add-on answers `{"entries": [...]}` with entries of the token's
user. The AddonService keeps the first 200 per add-on, drops entries that are invalid (unknown
app, kind other than `appointment`/`deadline`/`reminder`, `title.de` missing, bad `start`/`end`,
`link.slot` not a slot of that app the caller can see) or outside the range, adds `addon` and
answers `{"entries": [...], "errors": ["<add-on that failed or timed out>"]}` sorted by `start`.

## Discovery

- `ADDONS_DISCOVERY_KUBERNETES=true`: Services labelled `oriso.io/addon=true` in
  `ADDONS_DISCOVERY_NAMESPACE` (default `addons`). Port from the annotation
  `oriso.io/addon-port`, otherwise the first Service port. Needs `get`/`list` on Services
  in that namespace.
- `ADDONS_DISCOVERY_STATICADDONS`: comma-separated base URLs, for local development.

Each add-on serves `/.well-known/oriso-addon.json`. Invalid manifests are logged and ignored.

## Manifest

```json
{
  "name": "digisucht-tools",
  "version": "1.0.0",
  "contract": "oriso-addon/v1",
  "react": "^19",
  "ui": "/ui/entry.js",
  "calendar": "/calendar",
  "apps": [
    { "id": "goals", "title": { "de": "Ausgangssituation und Ziele" },
      "slots": ["asker.navigation", "consultant.session", "consultant.asker-profile"],
      "visibility": "dynamic" }
  ]
}
```

`visibility` is `static` (default: the app is shown wherever its slot and the enablement allow)
or `dynamic` (the add-on decides per user, see Registry).

Slots and who sees them:

| Slot | Roles |
|---|---|
| `asker.navigation` | `user` |
| `consultant.navigation`, `consultant.session`, `consultant.asker-profile` | `consultant` |
| `admin.page` | `tenant-admin`, `single-tenant-admin`, `agency-admin`, `restricted-agency-admin` |

## Configuration

| Variable | Default | |
|---|---|---|
| `SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_JWK_SET_URI` | | Keycloak certs, internal URL |
| `SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI` | | Public issuer as in the tokens |
| `SPRING_DATASOURCE_URL` / `_USERNAME` / `_PASSWORD` | `jdbc:mariadb://mariadb:3306/addonservice` | Schema created by Liquibase |
| `ADDONS_TOKEN_PRIVATEKEYPEM` | generated per start | PKCS#8 RSA key; set it, or tokens break on restart and differ between replicas |
| `ADDONS_USERSERVICE_BASEURL` | `http://userservice:8080` | |
| `ADDONS_TECHNICALCLIENT_TOKENURL` | | Keycloak token endpoint, internal URL, e.g. `http://keycloak:8080/auth/realms/<realm>/protocol/openid-connect/token`; needed for notifications |
| `ADDONS_TECHNICALCLIENT_CLIENTID` | `backend-technical` | Confidential client with the realm role `technical` |
| `ADDONS_TECHNICALCLIENT_CLIENTSECRET` | | Its secret |
| `ADDONS_CALENDAR_TIMEOUT` | `3s` | Per add-on |
| `ADDONS_VISIBILITY_TIMEOUT` | `2s` | `GET /visibility`, per add-on |
| `ADDONS_VISIBILITY_CACHETTL` | `30s` | Cache of `GET /visibility` answers per user, add-on and context |

## Demo add-on

`examples/demo-addon` is a complete add-on without dependencies: manifest, a UI bundle that
uses the host's React from `globalThis.__ORISO_ADDON_SHARED__`, and `/api/hello`, which
verifies the AddonService token against the JWKS and echoes its claims. It offers one app in
`asker.navigation`, `consultant.navigation` and `consultant.asker-profile`.

## Development

```sh
./mvnw verify
```

Images: `harbor.mybits.dev/digisucht/addonservice:<version>-<sha>` from `main`.
