# @oriso/vite-plugin-addon

Builds an add-on UI bundle against the host's React and component kit (ADR-034). The
imports stay normal; the plugin points them at `globalThis.__ORISO_ADDON_SHARED__`, so
nothing of React or the kit ends up in the bundle and the add-on always renders with the
versions the host runs.

```js
// vite.config.js
import orisoAddon from '@oriso/vite-plugin-addon';

export default {
	plugins: [orisoAddon()],
	build: {
		lib: { entry: 'src/entry.jsx', formats: ['es'], fileName: () => 'entry.js' }
	}
};
```

```jsx
// src/entry.jsx
import { useState } from 'react';
import { createRoot } from 'react-dom/client';
import { Provider, Card, Textarea, Button } from '@oriso/web-kit';

function Goals() {
	const [text, setText] = useState('');
	return (
		<Card>
			<Textarea label="Was möchten Sie erreichen?" value={text} onChange={setText} />
			<Button onClick={() => save(text)}>Speichern</Button>
		</Card>
	);
}

const roots = new Map();
export async function mount({ domElement }) {
	const root = createRoot(domElement);
	roots.set(domElement, root);
	root.render(<Provider><Goals /></Provider>);
}
export async function unmount({ domElement }) {
	roots.get(domElement)?.unmount();
	roots.delete(domElement);
}
```

`react` and `react-dom` are only needed at build time (to know their export names) and
as types. The types for the kit come from `@oriso/web-kit`, which ORISO-Frontend builds
with `npm run build:addon-kit-types`.

The kit exists in the web app (slots `asker.*` and `consultant.*`). ORISO-Admin has no
kit yet; an `admin.page` app falls back to its own components.
