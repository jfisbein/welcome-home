package welcomehome.nuki;

/**
 * How an owner connected Nuki.
 */
public enum NukiCredential {
    /** An API token that the owner generated in Nuki Web and pasted. */
    API_TOKEN,
    /** An OAuth2 authorization that the owner gave welcome-home in Nuki, renewed with its refresh token. */
    OAUTH2
}
