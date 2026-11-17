// Vite/Rollup plugin for ORISO add-on bundles (ADR-034): imports of the host's shared modules
// resolve to globalThis.__ORISO_ADDON_SHARED__ instead of being bundled.
import { createRequire } from 'node:module';
import { join } from 'node:path';
import { pathToFileURL } from 'node:url';

const GLOBAL = 'globalThis.__ORISO_ADDON_SHARED__';
const PREFIX = '\0oriso-shared:';

const KIT_EXPORTS = ['version', 'Provider', 'Button', 'TextField', 'Textarea', 'Checkbox', 'Select', 'RadioGroup', 'DatePicker', 'NumberField', 'Notice', 'Card', 'Headline', 'Spinner'];

const reexport = (source, names) =>
	[`const shared = ${source};`, 'export default shared;', ...names.map((name) => `export const ${name} = /* @__PURE__ */ (() => shared.${name})();`)].join('\n');

export default function orisoAddon() {
	const modules = new Map();
	let root = process.cwd();
	// The add-on's own React tells us the export names; resolve it from the add-on, not from here.
	const load = (id) => import(pathToFileURL(createRequire(join(root, 'package.json')).resolve(id)).href);
	return {
		name: 'oriso-addon',
		enforce: 'pre',
		configResolved(config) {
			root = config.root;
		},
		async buildStart() {
			const react = await load('react');
			const reactDomClient = await load('react-dom/client');
			const jsxRuntime = await load('react/jsx-runtime');
			const names = (module) => Object.keys(module).filter((name) => name !== 'default' && /^[A-Za-z_$][\w$]*$/.test(name));
			modules.set('react', reexport(`${GLOBAL}.react`, names(react)));
			modules.set('react-dom/client', reexport(`${GLOBAL}.reactDomClient`, names(reactDomClient)));
			modules.set('react/jsx-runtime', reexport(`${GLOBAL}.jsxRuntime`, names(jsxRuntime)));
			modules.set('@oriso/web-kit', reexport(`${GLOBAL}.kit`, KIT_EXPORTS));
		},
		resolveId(id) {
			return modules.has(id) ? PREFIX + id : null;
		},
		load(id) {
			return id.startsWith(PREFIX) ? { code: modules.get(id.slice(PREFIX.length)), moduleSideEffects: false } : null;
		}
	};
}
