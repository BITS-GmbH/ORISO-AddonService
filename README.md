# ORISO AddonService

Connects ORISO with add-ons: optional services that bring their own backend and UI, are
discovered by the platform and enabled per Träger. Design: ADR-031 (draft,
[digisucht-oriso-kickoff](https://github.com/BITS-GmbH/digisucht-oriso-kickoff/blob/main/adr/ADR-031-addons-optional-services-with-their-own-ui.md)).

## What it does

| Route (behind `/service/addons`) | Who | What |
|---|---|---|
| `GET /registry` | logged-in users | Add-ons enabled for the caller's Träger (and agency, via `X-Oriso-Agency-Id`), with only the apps whose slots the caller's role may see |
| `/{name}/api/**` | logged-in users | Forwarded to the add-on's `/api/**` with a short-lived token signed by the AddonService instead of the Keycloak token |
| `GET /{name}/ui/**` | anyone | The add-on's bundle and assets (loaded with `import()`, which cannot send a token) |
| `GET /.well-known/jwks.json` | anyone | Public key add-ons use to verify the token |
| `GET /admin/addons?tenantId=` | admins of the Träger | Registered add-ons with their switches |
| `PUT /admin/addons/{name}/tenants/{tenantId}` | SuperAdmin | Switch an add-on on or off for a Träger |
| `PUT`/`DELETE /admin/addons/{name}/tenants/{tenantId}/agencies/{agencyId}` | admins of the Träger | Override for one agency |
| `GET`/`PUT /admin/addons/{name}/settings?tenantId=&agencyId=` | admins of the Träger | Settings schema and stored values; PUT validates against the schema |
| `GET /admin/addons/{name}/stats?tenantId=` | admins of the Träger | The add-on's statistics with their labels |
| `GET /host/settings` | add-on backends | Effective settings (defaults, Träger, agency) for the add-on, Träger and agency of the add-on token |

ingress-nginx rewrites `/service/addons/...` to `/addons/...`, like the other services.

**Enablement:** off until a SuperAdmin enables an add-on for a Träger. Within an enabled
Träger, agencies can be switched off (or on again). An agency switch alone has no effect.

**Agency context:** when the web app sends `X-Oriso-Agency-Id`, the AddonService checks with
UserService (`/users/data`, with the caller's token) that the caller belongs to that agency,
applies the agency override and passes `agencyId` in the add-on token.

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
  "apps": [
    { "id": "goals", "title": { "de": "Ausgangssituation und Ziele" },
      "slots": ["asker.navigation", "consultant.session", "consultant.asker-profile"] }
  ]
}
```

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
