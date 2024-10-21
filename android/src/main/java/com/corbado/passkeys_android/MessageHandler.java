package com.corbado.passkeys_android;

import static androidx.credentials.PublicKeyCredential.TYPE_PUBLIC_KEY_CREDENTIAL;

import android.app.Activity;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import android.os.CancellationSignal;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.credentials.CreateCredentialResponse;
import androidx.credentials.CreatePublicKeyCredentialRequest;
import androidx.credentials.Credential;
import androidx.credentials.CredentialManager;
import androidx.credentials.CredentialManagerCallback;
import androidx.credentials.GetCredentialRequest;
import androidx.credentials.GetCredentialResponse;
import androidx.credentials.GetPublicKeyCredentialOption;
import androidx.credentials.PrepareGetCredentialResponse;
import androidx.credentials.PublicKeyCredential;
import androidx.credentials.exceptions.CreateCredentialCancellationException;
import androidx.credentials.exceptions.CreateCredentialException;
import androidx.credentials.exceptions.GetCredentialCancellationException;
import androidx.credentials.exceptions.GetCredentialException;
import androidx.credentials.exceptions.NoCredentialException;
import androidx.credentials.exceptions.publickeycredential.CreatePublicKeyCredentialDomException;
import androidx.credentials.exceptions.publickeycredential.CreatePublicKeyCredentialException;
import androidx.credentials.exceptions.publickeycredential.GetPublicKeyCredentialDomException;

import com.corbado.passkeys_android.models.login.AllowCredentialType;
import com.corbado.passkeys_android.models.signup.AuthenticatorSelectionType;
import com.corbado.passkeys_android.models.signup.CreateCredentialOptions;
import com.corbado.passkeys_android.models.login.GetCredentialOptions;
import com.corbado.passkeys_android.models.signup.ExcludeCredentialType;
import com.corbado.passkeys_android.models.signup.PubKeyCredParamType;
import com.corbado.passkeys_android.models.signup.RelyingPartyType;
import com.corbado.passkeys_android.models.signup.UserType;
import com.google.android.gms.fido.Fido;
import com.google.android.gms.fido.fido2.Fido2ApiClient;
import com.google.android.gms.tasks.Task;

import org.json.JSONException;
import org.json.JSONObject;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

public class MessageHandler implements Messages.PasskeysApi {

    private static final String TAG = "MessageHandler";
    private static final String SYNC_ACCOUNT_NOT_AVAILABLE_ERROR = "Sync account could not be accessed. If you are running on an emulator, please restart that device (select 'Could boot now').";
    private static final String MISSING_GOOGLE_SIGN_IN_ERROR = "Please sign in with a Google account first to create a new passkey.";
    private static final String EXCLUDE_CREDENTIALS_MATCH_ERROR = "You can not create a credential on this device because one of the excluded credentials exists on the local device.";

    private final FlutterPasskeysPlugin plugin;

    private CancellationSignal currentCancellationSignal;

    public MessageHandler(FlutterPasskeysPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void canAuthenticate(@NonNull Messages.Result<Boolean> result) {

        Activity activity = plugin.requireActivity();
        Fido2ApiClient fido2ApiClient = Fido.getFido2ApiClient(activity.getApplicationContext());

        Task<Boolean> isAvailable = fido2ApiClient.isUserVerifyingPlatformAuthenticatorAvailable();
        isAvailable.addOnSuccessListener(result::success);
        isAvailable.addOnFailureListener(result::error);
    }

    @Override
    public void register(
            @NonNull String challenge,
            @NonNull Messages.RelyingParty relyingParty,
            @NonNull Messages.User user,
            @NonNull Messages.AuthenticatorSelection authenticatorSelection,
            @Nullable List<Messages.PubKeyCredParam> pubKeyCredParams,
            @Nullable Long timeout,
            @Nullable String attestation,
            @NonNull List<Messages.ExcludeCredential> excludeCredentials,
            @NonNull Messages.Result<Messages.RegisterResponse> result
    ) {

        UserType userType = new UserType(user.getName(), user.getDisplayName(), user.getId(), user.getIcon());
        RelyingPartyType relyingPartyType = new RelyingPartyType(relyingParty.getId(), relyingParty.getName());
        AuthenticatorSelectionType authSelectionType = new AuthenticatorSelectionType(authenticatorSelection.getAuthenticatorAttachment(), authenticatorSelection.getRequireResidentKey(), authenticatorSelection.getResidentKey(), authenticatorSelection.getUserVerification());
        List<PubKeyCredParamType> pubKeyCredParamsType = new ArrayList<>();
        if (pubKeyCredParams != null) {
            pubKeyCredParamsType = pubKeyCredParams.stream().map(p -> new PubKeyCredParamType(p.getType(), p.getAlg())).collect(Collectors.toList());
        }
        final List<ExcludeCredentialType> excludeCredentialsType = excludeCredentials.stream().map(c -> new ExcludeCredentialType(c.getType(), c.getId())).collect(Collectors.toList());

        CreateCredentialOptions createCredentialOptions = new CreateCredentialOptions(
                challenge,
                relyingPartyType,
                userType,
                pubKeyCredParamsType,
                timeout,
                authSelectionType,
                attestation,
                excludeCredentialsType
        );

        try {
            String options = createCredentialOptions.toJSON().toString();
            Activity activity = plugin.requireActivity();
            if(!PasskeysEligibility.isPasskeySupported(activity)) {
                Log.e(TAG, "Your device is not support passkey");
                Exception platformException = new Messages.FlutterError("android-missing-google-sign-in", "Your device is not support passkey", "");
                result.error(platformException);
            }
            CredentialManager credentialManager = CredentialManager.create(activity);
            CreatePublicKeyCredentialRequest createPublicKeyCredentialRequest = new CreatePublicKeyCredentialRequest(options);
            currentCancellationSignal = new CancellationSignal();
            credentialManager.createCredentialAsync(activity, createPublicKeyCredentialRequest, currentCancellationSignal, Runnable::run, new CredentialManagerCallback<>() {

                @Override
                public void onResult(CreateCredentialResponse res) {
                    String resp = res.getData().getString("androidx.credentials.BUNDLE_KEY_REGISTRATION_RESPONSE_JSON");
                    try {
                        JSONObject json = new JSONObject(resp);
                        JSONObject response = json.getJSONObject("response");
                        result.success(new Messages.RegisterResponse.Builder().setId(json.getString("id")).setRawId(json.getString("rawId")).setClientDataJSON(response.getString("clientDataJSON")).setAttestationObject(response.getString("attestationObject")).build());
                    } catch (JSONException e) {
                        Log.e(TAG, "Error parsing response: " + resp, e);
                        result.error(e);
                    }
                }

                @Override
                public void onError(CreateCredentialException e) {
                    Exception platformException = e;
                    if (Objects.equals(e.getMessage(), "Unable to create key during registration")) {
                        // currently, Android throws this error when users skip the fingerPrint animation => we interpret this as a cancellation for now
                        platformException = new Messages.FlutterError("cancelled", e.getMessage(), "");
                    } else if (e instanceof CreateCredentialCancellationException) {
                        platformException = new Messages.FlutterError("cancelled", e.getMessage(), "");
                    } else if (e instanceof CreatePublicKeyCredentialDomException) {
                        if (Objects.equals(e.getMessage(), "User is unable to create passkeys.")) {
                            platformException = new Messages.FlutterError("android-missing-google-sign-in", e.getMessage(), MISSING_GOOGLE_SIGN_IN_ERROR);
                        } else if (Objects.equals(e.getMessage(), "Unable to get sync account.")) {
                            platformException = new Messages.FlutterError("android-sync-account-not-available", e.getMessage(), SYNC_ACCOUNT_NOT_AVAILABLE_ERROR);
                        } else if (Objects.equals(e.getMessage(), "One of the excluded credentials exists on the local device")) {
                            platformException = new Messages.FlutterError("exclude-credentials-match", e.getMessage(), EXCLUDE_CREDENTIALS_MATCH_ERROR);
                        } else {
                            platformException = new Messages.FlutterError("android-unhandled: " + e.getType(), e.getMessage(), e.getErrorMessage());
                        }
                    } else {
                        platformException = new Messages.FlutterError("android-unhandled" + e.getType(), e.getMessage(), e.getErrorMessage());
                    }

                    result.error(platformException);
                }
            });
        } catch (JSONException e) {
            Log.e(TAG, "Error creating JSON", e);
            result.error(e);
        }
    }

    @Override
    public void authenticate(@NonNull String relyingPartyId, @NonNull String challenge, @Nullable Long timeout, @Nullable String userVerification, @Nullable List<Messages.AllowCredential> allowCredentials, @NonNull Messages.Result<Messages.AuthenticateResponse> result) {
        List<AllowCredentialType> allowCredentialsType = new ArrayList<>();
        if (allowCredentials != null) {
            allowCredentialsType = allowCredentials.stream().map(c -> new AllowCredentialType(c.getType(), c.getId(), c.getTransports())).collect(Collectors.toList());
        }
        GetCredentialOptions getCredentialOptions = new GetCredentialOptions(challenge, timeout, relyingPartyId, allowCredentialsType, userVerification);
        try {
            String options = getCredentialOptions.toJSON().toString();

            Activity activity = plugin.requireActivity();

            if(!PasskeysEligibility.isPasskeySupported(activity)) {
                Log.e(TAG, "Your device is not support passkey");
                Exception platformException = new Messages.FlutterError("android-missing-google-sign-in", "Your device is not support passkey", "");
                result.error(platformException);
            }

            CredentialManager credentialManager = CredentialManager.create(activity);
            GetPublicKeyCredentialOption getPublicKeyCredentialOption = new GetPublicKeyCredentialOption(options);

            GetCredentialRequest getCredRequest = new GetCredentialRequest.Builder().addCredentialOption(getPublicKeyCredentialOption).setPreferImmediatelyAvailableCredentials(true).build();
            currentCancellationSignal = new CancellationSignal();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                credentialManager.prepareGetCredentialAsync(
                        getCredRequest,
                        currentCancellationSignal,
                        Runnable::run,
                        new CredentialManagerCallback<PrepareGetCredentialResponse, GetCredentialException>() {
                            @Override
                            public void onResult(PrepareGetCredentialResponse prepareGetCredentialResponse) {

                                boolean hasCredentialResults = prepareGetCredentialResponse.hasCredentialResults(TYPE_PUBLIC_KEY_CREDENTIAL);
                                Log.i(TAG, "Pending Get Credential Handle is null: " + hasCredentialResults);
                                if(hasCredentialResults) {
                                    credentialManager.getCredentialAsync(activity, getCredRequest, currentCancellationSignal, Runnable::run, new CredentialManagerCallback<>() {

                                        @Override
                                        public void onResult(GetCredentialResponse res) {
                                            Log.e(TAG, "onResult called");
                                            Credential credential = res.getCredential();
                                            if (credential instanceof PublicKeyCredential) {
                                                String responseJson = ((PublicKeyCredential) credential).getAuthenticationResponseJson();
                                                try {
                                                    final JSONObject json = new JSONObject(responseJson);
                                                    final JSONObject response = json.getJSONObject("response");

                                                    final String id = json.getString("id");
                                                    final String rawId = json.getString("rawId");

                                                    final String clientDataJSON = response.getString("clientDataJSON");
                                                    final String userHandle = response.getString("userHandle");
                                                    final String signature = response.getString("signature");
                                                    final String authenticatorData = response.getString("authenticatorData");

                                                    final Messages.AuthenticateResponse msg = new Messages.AuthenticateResponse.Builder().setId(id).setRawId(rawId).setClientDataJSON(clientDataJSON).setAuthenticatorData(authenticatorData).setSignature(signature).setUserHandle(userHandle).build();

                                                    result.success(msg);
                                                } catch (JSONException e) {
                                                    Log.e(TAG, "Error parsing response: " + responseJson, e);
                                                    result.error(e);
                                                }
                                            } else {
                                                result.error(new Exception("Credential is of type " + credential.getClass().getName() + ", but should be of type PublicKeyCredential"));
                                            }
                                        }

                                        @Override
                                        public void onError(@NonNull GetCredentialException e) {
                                            Log.e(TAG, "onError called", e);
                                            Exception platformException = e;

                                            // currently, Android throws this error when users skip the fingerPrint animation => we interpret this as a cancellation for now
                                            if (Objects.equals(e.getMessage(), "None of the allowed credentials can be authenticated")) {
                                                platformException = new Messages.FlutterError("cancelled", e.getMessage(), "");
                                            } else if (e instanceof GetCredentialCancellationException) {
                                                platformException = new Messages.FlutterError("cancelled", e.getMessage(), "");
                                            } else if (e instanceof NoCredentialException) {
                                                platformException = new Messages.FlutterError("android-no-credential", e.getMessage(), "");
                                            } else if (e instanceof GetPublicKeyCredentialDomException) {
                                                if (Objects.requireNonNull(e.getMessage()).contains("Cancelled by user")) {
                                                    platformException = new Messages.FlutterError("cancelled", e.getMessage(), "");
                                                } else if (Objects.equals(e.getMessage(), "Failed to decrypt credential.")) {
                                                    platformException = new Messages.FlutterError("android-sync-account-not-available", e.getMessage(), SYNC_ACCOUNT_NOT_AVAILABLE_ERROR);
                                                } else {
                                                    platformException = new Messages.FlutterError("android-unhandled: " + e.getType(), e.getMessage(), e.getErrorMessage());
                                                }
                                            } else {
                                                platformException = new Messages.FlutterError("android-unhandled: " + e.getType(), e.getMessage(), e.getErrorMessage());
                                            }

                                            result.error(platformException);
                                        }

                                    });
                                } else {
                                    Exception platformException = new Messages.FlutterError("android-no-credential", "no credential available", "");
                                    result.error(platformException);
                                }
                            }

                            @Override
                            public void onError(@NonNull GetCredentialException e) {
                                Log.e(TAG, "error");
                                Exception platformException = new Messages.FlutterError("android-unhandled: " + e.getType(), e.getMessage(), e.getErrorMessage());
                                result.error(platformException);
                            }
                        }
                );
            } else {
                credentialManager.getCredentialAsync(activity, getCredRequest, currentCancellationSignal, Runnable::run, new CredentialManagerCallback<>() {

                    @Override
                    public void onResult(GetCredentialResponse res) {
                        Log.e(TAG, "onResult called");
                        Credential credential = res.getCredential();
                        if (credential instanceof PublicKeyCredential) {
                            String responseJson = ((PublicKeyCredential) credential).getAuthenticationResponseJson();
                            try {
                                final JSONObject json = new JSONObject(responseJson);
                                final JSONObject response = json.getJSONObject("response");

                                final String id = json.getString("id");
                                final String rawId = json.getString("rawId");

                                final String clientDataJSON = response.getString("clientDataJSON");
                                final String userHandle = response.getString("userHandle");
                                final String signature = response.getString("signature");
                                final String authenticatorData = response.getString("authenticatorData");

                                final Messages.AuthenticateResponse msg = new Messages.AuthenticateResponse.Builder().setId(id).setRawId(rawId).setClientDataJSON(clientDataJSON).setAuthenticatorData(authenticatorData).setSignature(signature).setUserHandle(userHandle).build();

                                result.success(msg);
                            } catch (JSONException e) {
                                Log.e(TAG, "Error parsing response: " + responseJson, e);
                                result.error(e);
                            }
                        } else {
                            result.error(new Exception("Credential is of type " + credential.getClass().getName() + ", but should be of type PublicKeyCredential"));
                        }
                    }

                    @Override
                    public void onError(@NonNull GetCredentialException e) {
                        Exception platformException = e;

                        // currently, Android throws this error when users skip the fingerPrint animation => we interpret this as a cancellation for now
                        if (Objects.equals(e.getMessage(), "None of the allowed credentials can be authenticated")) {
                            platformException = new Messages.FlutterError("cancelled", e.getMessage(), "");
                        } else if (e instanceof GetCredentialCancellationException) {
                            platformException = new Messages.FlutterError("cancelled", e.getMessage(), "");
                        } else if (e instanceof NoCredentialException) {
                            platformException = new Messages.FlutterError("android-no-credential", e.getMessage(), "");
                        } else if (e instanceof GetPublicKeyCredentialDomException) {
                            if (Objects.requireNonNull(e.getMessage()).contains("Cancelled by user")) {
                                platformException = new Messages.FlutterError("cancelled", e.getMessage(), "");
                            } else if (Objects.equals(e.getMessage(), "Failed to decrypt credential.")) {
                                platformException = new Messages.FlutterError("android-sync-account-not-available", e.getMessage(), SYNC_ACCOUNT_NOT_AVAILABLE_ERROR);
                            } else {
                                platformException = new Messages.FlutterError("android-unhandled: " + e.getType(), e.getMessage(), e.getErrorMessage());
                            }
                        } else {
                            platformException = new Messages.FlutterError("android-unhandled: " + e.getType(), e.getMessage(), e.getErrorMessage());
                        }

                        result.error(platformException);
                    }

                });
            }
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void cancelCurrentAuthenticatorOperation(@NonNull Messages.Result<Void> result) {
        if (currentCancellationSignal != null) {
            currentCancellationSignal.cancel();
            currentCancellationSignal = null;
        }

        result.success(null);
    }
}
