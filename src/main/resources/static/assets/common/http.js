const apiRoot = '/lnis/api/v1';

/** Shared JSON decoding; callers retain their cache, header and failure policies. */
export async function requestJson(path, options = {}, policy = {}) {
    const response = await fetch(apiRoot + path, options);
    if (policy.notFoundIsNull && response.status === 404) return null;
    if (!response.ok && policy.errorDetails === false) throw new Error('HTTP ' + response.status);
    if (response.status === 204) return policy.allowEmpty ? {} : null;
    // A failed JSON decode must not consume the text used for an error message.
    const bodySource = response.clone ? response.clone() : response;
    let body;
    try {
        body = await bodySource.json();
    } catch (error) {
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
