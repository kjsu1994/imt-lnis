import {readFileSync} from 'node:fs';

const assets = new URL('../../main/resources/static/assets/', import.meta.url);
// Whole-page harness only. Pure helpers are imported and tested as native ES modules.
export function pageSource(name) {
  const shared = ['common/http.js', 'dtn/dtn-adapter-health.js', 'dtn/dtn-settings.js', 'dtn/dtn-gnss.js'].map(path =>
    readFileSync(new URL(path, assets), 'utf8').replace(/^import .*;\r?\n/gm, '').replace(/^export /gm, '')).join('\n');
  const page = readFileSync(new URL('dtn/' + name, assets), 'utf8')
    .replace(/^import .*;\r?\n/gm, '')
    .replace(/initialize\(\);\s*$/, 'globalThis.ready = initialize();');
  return shared + '\ninitPresetControls = () => ({update() {}, refresh() {}}); renderTrialSettings = () => {};\n' + page;
}
