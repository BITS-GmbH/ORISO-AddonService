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

ingress-nginx rewrites `/service/addons/...` to `/addons/...`, like the other services.

**Enablement:** off until a SuperAdmin enables an add-on for a Träger. Within an enabled
Träger, agencies can be switched off (or on again). An agency switch alone has no effect.

**Agency context:** when the web app sends `X-Oriso-Agency-Id`, the AddonService checks with
UserService (`/users/data`, with the caller's token) that the caller belongs to that agency,
applies the agency override and passes `agencyId` in the add-on token.

**Token for add-ons** (RS256, audience = add-on name, 2 minutes): `sub`, `username`, `roles`,
`tenantId`, `agencyId` (if any). Cookies, the Keycloak token and client addresses are never
forwarded.

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

## Development

```sh
./mvnw verify
```

Images: `harbor.mybits.dev/digisucht/addonservice:<version>-<sha>` from `main`.
