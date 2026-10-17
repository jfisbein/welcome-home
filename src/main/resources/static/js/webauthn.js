"use strict";

// Passkey ceremonies against Spring Security's WebAuthn endpoints.
// Binary fields travel as base64url, which is what Spring Security expects.
const WebAuthn = (() => {

    function toBase64Url(buffer) {
        const base64 = btoa(String.fromCharCode(...new Uint8Array(buffer)));
        return base64.replace(/=/g, "").replace(/\+/g, "-").replace(/\//g, "_");
    }

    function fromBase64Url(text) {
        const binary = atob(text.replace(/-/g, "+").replace(/_/g, "/"));
        return Uint8Array.from(binary, character => character.charCodeAt(0)).buffer;
    }

    function csrfHeaders() {
        const header = document.querySelector('meta[name="_csrf_header"]').content;
        const token = document.querySelector('meta[name="_csrf"]').content;
        return {[header]: token};
    }

    async function postJson(url, body) {
        const response = await fetch(url, {
            method: "POST",
            headers: {"Content-Type": "application/json", ...csrfHeaders()},
            body: body === undefined ? undefined : JSON.stringify(body)
        });
        if (!response.ok) {
            const problem = await response.json().catch(() => ({}));
            const error = new Error(`${url}: HTTP ${response.status}`);
            error.code = problem.error;
            throw error;
        }
        return response.json();
    }

    /**
     * Creates a passkey from an invitation and returns the URL to go to next.
     * baseUrl is the invitation's path; the server answers on its /options and /register.
     */
    async function register(baseUrl, email) {
        const options = await postJson(`${baseUrl}/options`, {email});
        const credential = await navigator.credentials.create({
            publicKey: {
                ...options,
                user: {...options.user, id: fromBase64Url(options.user.id)},
                challenge: fromBase64Url(options.challenge),
                excludeCredentials: (options.excludeCredentials || [])
                    .map(excluded => ({...excluded, id: fromBase64Url(excluded.id)}))
            }
        });
        const response = credential.response;
        const result = await postJson(`${baseUrl}/register`, {
            id: credential.id,
            rawId: toBase64Url(credential.rawId),
            type: credential.type,
            response: {
                attestationObject: toBase64Url(response.attestationObject),
                clientDataJSON: toBase64Url(response.clientDataJSON),
                transports: response.getTransports ? response.getTransports() : []
            },
            clientExtensionResults: credential.getClientExtensionResults(),
            authenticatorAttachment: credential.authenticatorAttachment
        });
        return result.redirectUrl;
    }

    /** Signs in with a passkey and returns the URL to go to next. */
    async function authenticate() {
        const options = await postJson("/webauthn/authenticate/options");
        const credential = await navigator.credentials.get({
            publicKey: {
                ...options,
                challenge: fromBase64Url(options.challenge),
                allowCredentials: (options.allowCredentials || [])
                    .map(allowed => ({...allowed, id: fromBase64Url(allowed.id)}))
            }
        });
        const response = credential.response;
        const result = await postJson("/login/webauthn", {
            id: credential.id,
            rawId: toBase64Url(credential.rawId),
            type: credential.type,
            response: {
                authenticatorData: toBase64Url(response.authenticatorData),
                clientDataJSON: toBase64Url(response.clientDataJSON),
                signature: toBase64Url(response.signature),
                userHandle: response.userHandle ? toBase64Url(response.userHandle) : undefined
            },
            clientExtensionResults: credential.getClientExtensionResults(),
            authenticatorAttachment: credential.authenticatorAttachment
        });
        if (!result.authenticated) {
            throw new Error("Passkey sign-in rejected");
        }
        return result.redirectUrl;
    }

    return {authenticate, register};
})();
