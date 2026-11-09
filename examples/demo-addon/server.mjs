// Demo add-on for the ORISO add-on contract oriso-addon/v1, without dependencies.
// GET /.well-known/oriso-addon.json  manifest
// GET /ui/entry.js                   UI bundle (uses the host's React)
// GET /api/hello                     verifies the AddonService token and echoes its claims
// GET /health                        liveness
import { createServer } from 'node:http';
import { createPublicKey, verify } from 'node:crypto';
import { readFile } from 'node:fs/promises';

const PORT = Number(process.env.PORT ?? 8080);
const NAME = 'demo';
const JWKS_URL =
	process.env.ADDONSERVICE_JWKS_URL ?? 'http://addonservice:8090/addons/.well-known/jwks.json';
const ISSUER = process.env.ADDONSERVICE_ISSUER ?? 'oriso-addonservice';

const manifest = {
	name: NAME,
	version: '0.1.0',
	contract: 'oriso-addon/v1',
	react: '^19',
	ui: '/ui/entry.js',
	apps: [
		{
			id: 'hello',
			title: { de: 'Demo-Add-on', en: 'Demo add-on' },
			slots: ['asker.navigation', 'consultant.navigation', 'consultant.asker-profile']
		}
	],
	health: '/health'
};

let keys = new Map();
let keysLoadedAt = 0;

const loadKeys = async (force = false) => {
	if (!force && Date.now() - keysLoadedAt < 5 * 60_000 && keys.size > 0) {
		return keys;
	}
	const response = await fetch(JWKS_URL);
	if (!response.ok) {
		throw new Error(`JWKS ${response.status}`);
	}
	const { keys: jwks } = await response.json();
	keys = new Map(jwks.map((jwk) => [jwk.kid, createPublicKey({ key: jwk, format: 'jwk' })]));
	keysLoadedAt = Date.now();
	return keys;
};

const decodePart = (part) => JSON.parse(Buffer.from(part, 'base64url').toString('utf8'));

/** Returns the claims of a valid AddonService token for this add-on, otherwise null. */
const verifyToken = async (authorization) => {
	const token = authorization?.startsWith('Bearer ') ? authorization.slice(7) : null;
	const parts = token?.split('.');
	if (!parts || parts.length !== 3) {
		return null;
	}
	const header = decodePart(parts[0]);
	if (header.alg !== 'RS256') {
		return null;
	}
	let key = (await loadKeys()).get(header.kid);
	if (!key) {
		key = (await loadKeys(true)).get(header.kid);
	}
	const signed = Buffer.from(`${parts[0]}.${parts[1]}`);
	if (!key || !verify('sha256', signed, key, Buffer.from(parts[2], 'base64url'))) {
		return null;
	}
	const claims = decodePart(parts[1]);
	const audience = Array.isArray(claims.aud) ? claims.aud : [claims.aud];
	const now = Math.floor(Date.now() / 1000);
	if (claims.iss !== ISSUER || !audience.includes(NAME) || !(claims.exp > now)) {
		return null;
	}
	return claims;
};

const send = (res, status, type, body, extra = {}) => {
	res.writeHead(status, { 'Content-Type': type, ...extra });
	res.end(body);
};

const json = (res, status, body) => send(res, status, 'application/json', JSON.stringify(body));

const server = createServer(async (req, res) => {
	const url = new URL(req.url, 'http://localhost');
	try {
		if (url.pathname === '/.well-known/oriso-addon.json') {
			return json(res, 200, manifest);
		}
		if (url.pathname === '/health') {
			return json(res, 200, { status: 'UP' });
		}
		if (url.pathname === '/ui/entry.js') {
			const body = await readFile(new URL('./ui/entry.js', import.meta.url));
			return send(res, 200, 'text/javascript; charset=utf-8', body, { 'Cache-Control': 'no-cache' });
		}
		if (url.pathname === '/api/hello') {
			const claims = await verifyToken(req.headers.authorization);
			if (!claims) {
				return json(res, 401, { error: 'invalid token' });
			}
			return json(res, 200, {
				message: 'Hallo vom Demo-Add-on',
				you: {
					userId: claims.sub,
					username: claims.username,
					roles: claims.roles,
					tenantId: claims.tenantId,
					agencyId: claims.agencyId ?? null
				},
				at: new Date().toISOString()
			});
		}
		return json(res, 404, { error: 'not found' });
	} catch (error) {
		console.error(error);
		return json(res, 500, { error: 'internal error' });
	}
});

server.listen(PORT, () => console.log(`demo add-on on :${PORT}, JWKS ${JWKS_URL}`));
