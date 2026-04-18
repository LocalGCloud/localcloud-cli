## ADDED Requirements

### Requirement: OAuth2 token refresh for ADC credentials
The `CredentialBroker` SHALL refresh expired access tokens using the `refresh_token` from ADC files by calling the Google OAuth2 token endpoint.

#### Scenario: Token expired and refresh succeeds
- **WHEN** `getAccessToken()` is called and the stored token is expired but a refresh_token exists
- **THEN** the system SHALL POST to `https://oauth2.googleapis.com/token` with the refresh_token, client_id, and client_secret, and store the new access token

#### Scenario: Token expired and no refresh token
- **WHEN** `getAccessToken()` is called and the token is expired but no refresh_token exists (e.g., service account key)
- **THEN** the system SHALL return null and the credential status SHALL show "expired"

#### Scenario: Refresh fails due to revoked credentials
- **WHEN** a token refresh request returns 400/401
- **THEN** the system SHALL log the error and the credential status SHALL show "invalid — re-run gcloud auth application-default login"

### Requirement: Token expiry tracking
The `CredentialBroker` SHALL track token expiry time and proactively refresh tokens 5 minutes before they expire.

#### Scenario: Proactive refresh
- **WHEN** a remote API call is made and the token expires in less than 5 minutes
- **THEN** the system SHALL refresh the token before making the API call
