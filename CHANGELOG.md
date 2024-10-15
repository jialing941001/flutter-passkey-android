## 2.1.0
* Added support for preferImmediatelyAvailableCredentials

## 2.0.4
* Added NoCredentialsAvailableException to indicate that no credentials are available during a login

## 2.0.3
* Bump androidx.credentials:credentials to 1.2.0 => release builds will no work on Android 13 and below without custom proguard rules

## 2.0.2
* Map excludeCredentials error to typed exception

## 2.0.1
* Added excludeCredentials support

## 2.0.0

* Removed getFacetID (no longer needed)
* Added cancelCurrentAuthenticatorOperation
* Adapted to new passkeys_platform_interface

## 2.0.0-dev.1

* Removed getFacetID (no longer needed)
* Added cancelCurrentAuthenticatorOperation
* Adapted to new passkeys_platform_interface

## 1.2.1

* Catch more situations when a user interrupts biometrics ceremony (e.g. providing his fingerprint).

## 1.2.0

* Bump passkeys_platform_interface version.

## 1.1.2

* Catch native Android exceptions when user is not signed in to Google account.

## 1.1.1

* Removed unnecessary logs
* Catch CancellationExceptions from CredentialManager

## 1.1.0

* Added userHandle to AuthenticateResponseType.

## 1.0.0

* Better comments. Stable release.

## 0.1.0

* Initial open source release.
