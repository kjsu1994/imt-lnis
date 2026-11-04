const apiRoot = '/lnis/api/v1';

function syncDataRevision(response) {
    const revision = response.headers?.get?.('X-LNIS-Data-Revision');
    if (!revision || !response.ok) return;
    try {
        const storage = globalThis.localStorage;
        const key = 'lnis.data-revision';
        if (!storage || storage.getItem(key) === revision) return;
        if (revision.endsWith(':SERVICE')) {
            for (let index = storage.length - 1; index >= 0; index--) {
                const name = storage.key(index);
                if (name?.startsWith('lnis.')) storage.removeItem(name);
            }
        }
        storage.setItem(key, revision);
        if (globalThis.location?.pathname !== '/lnis/data-manager') globalThis.location?.reload();
    } catch { /* Storage restrictions must not block API results. */ }
}

/** Shared JSON decoding; callers retain their cache, header and failure policies. */
export async function requestJson(path, options = {}, policy = {}) {
    const method = (options.method || 'GET').toUpperCase();
    const requestOptions = method === 'GET' && !options.signal
        ? {...options, signal: AbortSignal.timeout(policy.timeoutMillis ?? 30000)}
        : options;
    const response = await fetch(apiRoot + path, requestOptions);
    syncDataRevision(response);
    if (policy.notFoundIsNull && response.status === 404) return null;
    if (!response.ok && policy.errorDetails === false) throw new Error('HTTP ' + response.status);
    if (response.status === 204) return policy.allowEmpty ? {} : null;
    // A failed JSON decode must not consume the text used for an error message.
    const bodySource = response.clone ? response.clone() : response;
    let body;
    try {
        body = await bodySource.json();
    } catch (error) {
        if (requestOptions.signal?.aborted) throw requestOptions.signal.reason || error;
        if (response.ok) {
            if (!policy.allowEmpty) throw error;
            body = {};
        } else {
            body = {detail: response.text ? await response.text().catch(() => '') : ''};
        }
    }
    if (!response.ok) throw new Error(body?.detail || body?.message || ('HTTP ' + response.status));
    return body;
}
