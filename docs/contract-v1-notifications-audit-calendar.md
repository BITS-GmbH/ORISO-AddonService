# oriso-addon/v1: notifications, audit entries, calendar entries

Extension of the add-on contract described in ADR-032 section 6. All three are optional for an add-on.

## Who talks to whom

```
add-on backend --(add-on token)--> AddonService host API --(technical client)--> UserService  (notifications)
add-on backend --(add-on token)--> AddonService host API --> AddonService DB                   (audit entries)
web app --(user token)--> AddonService /addons/calendar --(user-scoped add-on token)--> add-on  (calendar entries)
ORISO-Admin --(admin token)--> AddonService /addons/admin/audit                                 (audit log view)
```

The add-on token is the short-lived RS256 token the AddonService issues per request (claims `sub`, `username`, `roles`, `tenantId`, optional `agencyId`, `sessionId`, `askerId`; audience = add-on name). Add-on backends call the host API with that token while they handle the request it came with.

## 1. Notifications

`POST /addons/host/notifications` (add-on token)

```json
{
  "recipient": "asker",
  "appId": "goals",
  "important": true,
  "title": { "de": "Neues Tool freigegeben", "en": "New tool released" },
  "text": { "de": "Ihre Beraterin hat „Ausgangssituation und Ziele“ für Sie freigegeben." },
  "target": { "slot": "asker.navigation" }
}
```

- `recipient`: `"self"` (the token's `sub`) or `"asker"` (the token's `askerId`, only present when a counsellor acts in a session they may access). Nothing else in v1, so an add-on can never address arbitrary users.
- `title`, `text`: per locale; `de` is required, the core falls back to `de`. Plain text, max 120 / 500 characters.
- `important`: the only delivery hint. The core decides channels from the recipient's own notification preferences.
- `appId`, `target.slot`: where a click leads. `target.slot` must be one of the app's slots that the recipient can open (`asker` counts as role `user`). The AddonService adds `addon` (its name) and, for `consultant.session` and `consultant.asker-profile`, the `sessionId` from the token (400 if the token has none).
- Response `202` with `{ "id": "<notification id>" }`; `403` if the recipient is not allowed; `404` for an unknown app; `400` for an invalid body; `502` if UserService or Keycloak fails.

AddonService forwards to UserService:

`POST /users/event-notifications/addon` (Keycloak service token of the backend-technical client, role `technical`)

```json
{
  "userId": "<keycloak id of the recipient>",
  "tenantId": 1,
  "addon": "digisucht-tools",
  "appId": "goals",
  "slot": "asker.navigation",
  "sessionId": null,
  "important": true,
  "title": { "de": "..." },
  "text": { "de": "..." }
}
```

UserService stores it as an Activity Timeline event of the new type `ADDON_NOTIFICATION` (family `system` unless a better existing family fits), payload = the fields above, and runs the same preference-driven delivery as other events (push and e-mail only if the recipient's settings allow it; `important: false` never sends e-mail). It never renders add-on text as HTML.

The web app renders `ADDON_NOTIFICATION` with the payload's title and text in the recipient's locale and an action target:
- `asker.navigation` / `consultant.navigation`: route `/addons/<addon>/<appId>`
- `consultant.session`: the case view of `sessionId` with `?addon=<addon>/<appId>`
- `consultant.asker-profile`: the case view of `sessionId`

## 2. Audit entries (write only)

`POST /addons/host/audit` (add-on token)

```json
{
  "action": "tool.released",
  "summary": { "de": "Tool „Ausgangssituation und Ziele“ freigegeben", "en": "Released tool" },
  "subject": "asker"
}
```

- `action`: machine key, `[a-z0-9._-]{1,64}`.
- `summary`: per locale, `de` required, plain text, max 300 characters.
- `subject`: `"asker"` (uses the token's `askerId`), `"self"` or omitted.
- The AddonService stamps: id, timestamp (server clock), add-on name, app id if given (`appId`, optional), actor id and username and roles from the token, tenantId, agencyId, sessionId, subject user id. Stored append-only in `addon_audit_entry`.
- `subject: "asker"` without `askerId` in the token: `403`. Unknown `appId`: `404`.
- Response `201` with `{ "id": <entry id> }`. There is no host endpoint to read entries.

`GET /addons/admin/audit?tenantId=&addon=&from=&to=&page=0&size=50` (admin token)

- SuperAdmin: any `tenantId` (optional = all). Träger admins: only their own tenant. Agency-level admins: only entries of their agencies.
- `from` inclusive, `to` exclusive (ISO instants), `size` 1 to 200.
- Response: `{ "items": [ { "id", "createdAt", "addon", "appId", "action", "summary", "actor": { "id", "username", "roles" }, "tenantId", "agencyId", "sessionId", "subjectId" } ], "page", "size", "total" }`, newest first.

ORISO-Admin shows these as an "Add-ons" tab in its Logs area, filtered by add-on and date.

## 3. Calendar entries (read only)

Manifest gets an optional top-level `"calendar": "/calendar"`.

`GET /addons/calendar?from=<ISO instant>&to=<ISO instant>` (user token, web app)

- The AddonService calls `GET <add-on>/calendar?from=&to=` on every add-on that declares `calendar` and is enabled for the caller (Träger and agency rules as for the registry), with a user-scoped add-on token (same claims as the gateway, no session), in parallel, 3 s timeout each; a failing add-on is skipped and listed in `errors`.
- Add-on response: `{ "entries": [ { "id", "appId", "kind": "appointment" | "deadline" | "reminder", "title": { "de": ... }, "start": "<ISO>", "end": "<ISO>"?, "allDay": false?, "link": { "slot": "asker.navigation" | ..., "sessionId"? } } ] }`. Max 200 entries per add-on; only entries of the token's `sub`.
- `from` must be before `to`, the range at most 92 days. The AddonService drops invalid entries (unknown `appId`, other `kind`, missing `title.de`, unparsable `start`/`end`, `end` before `start`, `link.slot` not a slot of that app visible to the caller) and entries outside the range, and keeps the first 200 per add-on.
- AddonService response: `{ "entries": [ ...entries with "addon": "<name>" added ], "errors": [ "<addon name>" ] }`, sorted by `start`.
- The web app shows them in its existing appointment/calendar views next to core appointments, visibly marked as coming from an add-on, clicking opens the link target as for notifications. Read only: no booking, moving or cancelling from the core.

## 4. Visibility: registry in context, dynamic apps

What a user sees is decided in three stages, all in the AddonService (ADR-032 section 2): enablement, slot and role, app state.

### Registry in context

`GET /addons/registry` (user token) accepts the same optional headers as the gateway:

- `X-Oriso-Agency-Id`: verified membership (as in the gateway); the Träger switch and the override of this agency decide.
- `X-Oriso-Session-Id`: counsellors only; verified with UserService as in the gateway; the session's agency decides, and the session context goes into the token used for the visibility call.
- No header: the caller's own agencies (counsellor: their agencies; client: the agencies of their sessions, from `/users/data`). An add-on is listed if it is enabled for at least one of them; if the caller has no agency at all, the Träger switch alone decides.

The web app fetches the registry once without context (navigation, profile-independent places) and once per open case with that case's agency and session (case view, profile tiles). A disabled add-on is therefore absent from the response and the UI renders nothing for it.

### Dynamic app visibility

Manifest, per app: optional `"visibility": "static" | "dynamic"`, default `static`.

For every listed add-on with at least one dynamic app the caller's role can see, the AddonService calls

`GET <add-on>/visibility` (user-scoped add-on token, with agency, session and askerId claims when the registry request had that context)

Add-on response:

```json
{ "apps": { "goals": { "visible": true, "badge": 1 } } }
```

- `visible` is required per dynamic app; apps missing from the answer are hidden.
- `badge`: optional non-negative integer, 0 or absent means none; max 99 is shown as "99+".
- Timeout 2 s (`addons.visibility.timeout`); on timeout or error every dynamic app of that add-on is hidden for this request, and the add-on name is logged.
- Cached per user, add-on and context for `addons.visibility.cache-ttl` (default 30 s). The cache entry of a recipient is dropped when the AddonService forwards a notification for them, so a released tool shows up immediately after the notification.

Registry response per app gains `"badge": <int>` when the add-on reported one. Static apps never carry a badge.

The web app shows the badge on the navigation entry, the profile tile and the case-view button, and refetches the registry when an `ADDON_NOTIFICATION` arrives and when the window regains focus.
