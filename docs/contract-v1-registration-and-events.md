# oriso-addon/v1: registration agency finder, session menus, app icons, lifecycle events

Additions to the contract (ADR-034). Paths are relative to `/service/addons`.

## 1. Slot `registration.agency-finder`

In the core registration (visitors without an account) an add-on can replace the step that
selects the Beratungsstelle. It asks its own questions, suggests or selects an agency and hands
the result back to the registration.

- Manifest: an app with `"slots": ["registration.agency-finder"]`. At most one app per add-on may
  use this slot, otherwise the manifest is invalid. The slot has no role audience: it never
  appears in `GET /registry`.
- Enablement: as for other slots at Träger level, without agencies (the visitor has none yet):
  global switch on and mode `TENANT` with the Träger switch on, `ON` or `ON_HIDDEN`. Agency
  overrides do not apply.

### Which add-on: `GET /public/registration?tenantId=<id>` (no login)

`200` with the add-on that takes over the step for that Träger, or `204` when none does (also
for unknown Träger ids; a missing or non-numeric `tenantId` is `400`):

```json
{
  "contract": "oriso-addon/v1",
  "name": "digisucht-finder",
  "version": "1.0.0",
  "react": "^19",
  "appId": "finder",
  "title": { "de": "Beratungsstelle finden" },
  "icon": "/service/addons/digisucht-finder/ui/icons/finder.svg",
  "entry": "/service/addons/digisucht-finder/ui/entry.js",
  "apiBase": "/service/addons/digisucht-finder/public/api"
}
```

If several add-ons qualify, the first by name wins and a warning is logged. The answer per Träger
is cached for `addons.public-gateway.cache-ttl` (default 30 s), so a switch takes effect within
that time.

### UI contract

The registration mounts the app like any other slot and passes `tenantId` and a
`complete(result)` callback. The app calls

```js
complete({ agencyId, topicId, postcode, consultingType, registrationRef })
```

`registrationRef` is an opaque string (at most 128 characters) chosen by the add-on, for example
the id under which it stored the answers. The core creates the account with that agency and
passes the ref on; UserService then publishes `user.registered` with the new user id and the ref
(see 4), so the add-on can link the stored answers to the user.

### Public API: `/{name}/public/api/**` (no login)

Forwarded to the add-on's `/public/api/**` (not `/api/**`, so anonymous endpoints are kept apart
from the logged-in ones).

- Header `X-Oriso-Tenant-Id` is required (`400` without it or when not numeric).
- Allowed only if the add-on has an app in `registration.agency-finder` enabled for that Träger
  (`403` otherwise, `404` for unknown add-ons).
- Rate limit per client address and add-on: `addons.public-gateway.requests-per-minute`
  (default 60, fixed one-minute window, in memory per replica), `429` above it. The client
  address is the first entry of `X-Forwarded-For` as ingress-nginx sets it, else the TCP peer.
- Request body at most `addons.public-gateway.max-body-size` (default 1 MB), `413` above it.
- As in the normal gateway only an allowlist of headers is forwarded; cookies, the browser's
  `Authorization` header (ignored, never rejected) and client addresses never reach the add-on.
- The add-on receives an anonymous token (RS256, same key and issuer as the other add-on
  tokens, `addons.public-gateway.token-ttl`, default 1 minute):

```json
{ "iss": "oriso-addonservice", "aud": ["digisucht-finder"], "iat": 0, "exp": 60,
  "roles": ["anonymous"], "tenantId": 1 }
```

  There is no `sub` and there are no user claims (`username`, `agencyId`, `sessionId`,
  `askerId`). With this token the host API answers `GET /host/settings` (Träger settings) and
  `GET /host/gates`; `/host/context`, `/host/notifications` and `/host/audit` answer `403`.

## 2. Slots `asker.session-menu` and `consultant.session-menu`

Entries in the "three dots" menu of a chat, for clients (role `user`) and counsellors
(`consultant`). Both need a verified session, like `consultant.session`:

- The web app sends `X-Oriso-Session-Id` (and may send `X-Oriso-Agency-Id`) to the registry and
  the gateway.
- Counsellors: UserService (`/users/consultants/sessions/{id}`) must let them see the session;
  the token gets `sessionId`, `askerId` and the session's `agencyId`.
- Clients: the session must be one of their own within their Träger, from
  `/users/addon-context` (technical client); otherwise `403`. The token gets `sessionId` and the
  session's `agencyId`; `sub` is the client, so there is no `askerId`.
- When a session is given, its agency decides enablement and goes into the token, also when an
  agency header is present.

## 3. App icons

Per app, optional `"icon": "/ui/icons/goals.svg"`: a monochrome SVG served by the add-on's own UI
route. It must start with `/ui/`, end with `.svg`, contain only `A-Z a-z 0-9 . _ - /` without
`..` or empty segments, and be at most 200 characters; otherwise the manifest is invalid.

The registry, the public registration answer and the admin manifest output give it as a path
the browser can load: `/service/addons/<name>/ui/icons/goals.svg`. SVGs under `/ui/**` are served
with `Content-Type: image/svg+xml`, `X-Content-Type-Options: nosniff` and
`Content-Security-Policy: default-src 'none'; style-src 'unsafe-inline'`, so an SVG opened
directly cannot run script or load anything.

## 4. Lifecycle events `user.registered` and `session.interaction`

From the core's exchange `oriso.lifecycle` (routing key = type), opt in via the manifest:
`"events": ["user.registered", "session.interaction"]`. Delivery, service token and retries as
for `user.deleted` (README, Lifecycle events).

For these two types the AddonService forwards only the fields below, whatever else the message
contains:

```json
{"id":"…","type":"user.registered","occurredAt":"…","tenantId":1,"userId":"…","registrationRef":"…"}
{"id":"…","type":"session.interaction","occurredAt":"…","tenantId":1,"sessionId":16,"agencyId":10,"consultantId":"…","interaction":"message"}
```

- `user.registered`: `userId` is required; `registrationRef` is optional (absent when the
  registration did not go through an add-on), at most 128 characters.
- `session.interaction`: `sessionId` is required; `interaction` is one of `message`, `call`,
  `video_call`, `appointment` (named `interaction` because `type` is the event type). No content
  of the interaction is ever sent.
- Events that break these rules are logged and dropped.
