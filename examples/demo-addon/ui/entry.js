// UI of the demo add-on. No build step: it uses the React the ORISO web app shares on
// globalThis.__ORISO_ADDON_SHARED__ (contract oriso-addon/v1).
const { react: React, reactDomClient } = globalThis.__ORISO_ADDON_SHARED__;
const h = React.createElement;
const roots = new Map();

const card = {
	border: '1px solid var(--m3-outline-variant, #dbdee2)',
	borderRadius: '16px',
	padding: '16px 20px',
	maxWidth: '640px',
	background: 'var(--m3-surface, #fff)',
	color: 'var(--m3-on-surface, #0d1520)',
	fontSize: '15px',
	lineHeight: 1.5
};

function DemoApp(props) {
	const [answer, setAnswer] = React.useState(null);
	const [error, setError] = React.useState(null);

	React.useEffect(() => {
		let active = true;
		props
			.api('/hello')
			.then((response) => (response.ok ? response.json() : Promise.reject(new Error(`HTTP ${response.status}`))))
			.then(
				(body) => active && setAnswer(body),
				(reason) => active && setError(reason.message)
			);
		return () => {
			active = false;
		};
	}, [props.api]);

	const greeting = props.informal ? 'Hallo! Schön, dass du da bist.' : 'Guten Tag! Schön, dass Sie da sind.';

	return h(
		'div',
		{ style: card },
		h('p', { style: { margin: '0 0 8px', fontWeight: 700, color: 'var(--m3-primary, #009fe3)' } }, greeting),
		h('p', { style: { margin: '0 0 8px' } }, `Platz: ${props.slot}, Sprache: ${props.locale}`),
		props.sessionId != null &&
			h('p', { style: { margin: '0 0 8px' } }, `Fall ${props.sessionId}, Beratungsstelle ${props.agencyId}`),
		answer &&
			h(
				'pre',
				{ style: { margin: 0, fontSize: '13px', whiteSpace: 'pre-wrap' } },
				JSON.stringify(answer, null, 2)
			),
		error && h('p', { style: { margin: 0, color: 'var(--m3-error, #b42318)' } }, `API: ${error}`)
	);
}

export async function mount(props) {
	const root = reactDomClient.createRoot(props.domElement);
	roots.set(props.domElement, root);
	root.render(h(DemoApp, props));
}

export async function update(props) {
	roots.get(props.domElement)?.render(h(DemoApp, props));
}

export async function unmount(props) {
	roots.get(props.domElement)?.unmount();
	roots.delete(props.domElement);
}
