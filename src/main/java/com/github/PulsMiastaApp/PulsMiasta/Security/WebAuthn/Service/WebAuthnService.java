package com.github.PulsMiastaApp.PulsMiasta.Security.WebAuthn.Service;

import com.github.PulsMiastaApp.PulsMiasta.Model.Entities.Jpa.User;
import com.github.PulsMiastaApp.PulsMiasta.Model.Entities.Jpa.UserCredential;
import com.github.PulsMiastaApp.PulsMiasta.Repository.UserCredentialRepository;
import com.github.PulsMiastaApp.PulsMiasta.Repository.UserRepository;
import com.github.PulsMiastaApp.PulsMiasta.Security.Service.AuthResult;
import com.github.PulsMiastaApp.PulsMiasta.Security.Service.TokenService;
import com.github.PulsMiastaApp.PulsMiasta.Security.WebAuthn.Config.WebAuthnProperties;
import com.github.PulsMiastaApp.PulsMiasta.Security.WebAuthn.DTO.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.web.webauthn.api.*;
import org.springframework.security.web.webauthn.management.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Core WebAuthn / Passkey business logic.
 * <p>
 * Orchestrates the two-phase WebAuthn ceremonies:
 * 1. Registration (add a new passkey to an existing account)
 * 2. Authentication (log in with an existing passkey)
 * <p>
 * Uses {@link Webauthn4JRelyingPartyOperations} from Spring Security WebAuthn
 * for cryptographic verification. All key material validation (signature, counter,
 * origin, rpId, user presence / verification flags) is delegated to the library.
 * <p>
 * Challenge lifecycle:
 * - Stored in Redis via {@link ChallengeStore} with a short TTL.
 * - Consumed once in the finish phase (use-once, immune to replay).
 * <p>
 * Session lifecycle:
 * - After a successful authentication ceremony, a session token is issued via
 * {@link TokenService} — the exact same mechanism as PESEL+password login.
 */
@Service
@Slf4j
public class WebAuthnService {

    private final WebAuthnRelyingPartyOperations rpOps;
    private final ChallengeStore challengeStore;
    private final UserRepository userRepository;
    private final UserCredentialRepository credentialRepository;
    private final TokenService tokenService;
    private final WebAuthnProperties props;

    public WebAuthnService(
            UserEntityAdapter userEntityAdapter,
            CredentialRecordAdapter credentialRecordAdapter,
            ChallengeStore challengeStore,
            UserRepository userRepository,
            UserCredentialRepository credentialRepository,
            TokenService tokenService,
            WebAuthnProperties props
    ) {
        this.challengeStore = challengeStore;
        this.userRepository = userRepository;
        this.credentialRepository = credentialRepository;
        this.tokenService = tokenService;
        this.props = props;

        PublicKeyCredentialRpEntity rp = PublicKeyCredentialRpEntity.builder()
                .id(props.getRpId())
                .name(props.getRpName())
                .build();

        this.rpOps = new Webauthn4JRelyingPartyOperations(
                userEntityAdapter,
                credentialRecordAdapter,
                rp,
                props.getAllowedOrigins()
        );
    }

    // =========================================================================
    // REGISTRATION — Phase 1: Begin
    // =========================================================================

    /**
     * Starts a passkey registration ceremony for an already-authenticated user.
     *
     * @param user       the currently authenticated user (from SecurityContext)
     * @param sessionKey an opaque key tied to this specific ceremony (e.g. the auth_token cookie value)
     * @return the creation options that the client passes to {@code navigator.credentials.create()}
     */
    @Transactional
    public RegistrationBeginResponse beginRegistration(User user, String sessionKey) {
        // Ensure the user has a WebAuthn user handle (lazy-assign on first passkey action)
        // Use a transactional retry to handle potential race conditions
        if (user.getWebauthnUserHandle() == null) {
            int maxRetries = 3;
            for (int i = 0; i < maxRetries; i++) {
                try {
                    user.ensureWebauthnUserHandle();
                    userRepository.save(user);
                    break;
                } catch (Exception e) {
                    if (i == maxRetries - 1 || !e.getMessage().contains("Duplicate")) {
                        throw e;
                    }
                    user = userRepository.findById(user.getId()).orElseThrow();
                }
            }
        }

        // Generate and store challenge
        byte[] challenge = challengeStore.generateAndStore("reg:" + sessionKey);

        // Build exclude list — prevents registering a key that is already registered
        List<UserCredential> existing = credentialRepository.findAllByUserId(user.getId());
        List<RegistrationBeginResponse.AllowedCredential> excludeCredentials = existing.stream()
                .map(c -> new RegistrationBeginResponse.AllowedCredential(
                        "public-key",
                        Base64.getUrlEncoder().withoutPadding().encodeToString(c.getCredentialId()),
                        parseTransportList(c.getTransports())
                ))
                .toList();

        return new RegistrationBeginResponse(
                sessionKey,
                new RegistrationBeginResponse.RpInfo(props.getRpId(), props.getRpName()),
                new RegistrationBeginResponse.UserInfo(
                        Base64.getUrlEncoder().withoutPadding().encodeToString(user.getWebauthnUserHandle()),
                        user.getEmail(),
                        user.getFirstName() + " " + user.getLastName()
                ),
                Base64.getUrlEncoder().withoutPadding().encodeToString(challenge),
                List.of(
                        new RegistrationBeginResponse.PubKeyCredParam("public-key", -7),   // ES256 (P-256)
                        new RegistrationBeginResponse.PubKeyCredParam("public-key", -257)  // RS256 (RSA, for broader compat)
                ),
                props.getChallengeTtlSeconds() * 1000L,
                excludeCredentials,
                new RegistrationBeginResponse.AuthenticatorSelection(
                        null,           // no attachment preference → works with Face ID, Touch ID AND USB keys
                        "preferred",    // prefer resident/discoverable key for passwordless UX
                        "preferred"     // prefer user verification (biometric/PIN) but allow fallback
                ),
                "none"  // attestation: "none" maximises privacy and compatibility
        );
    }

    // =========================================================================
    // REGISTRATION — Phase 2: Finish
    // =========================================================================

    /**
     * Completes the registration ceremony and persists the new credential.
     *
     * @param user    the currently authenticated user
     * @param request the attestation response from the authenticator
     */
    @Transactional
    public void finishRegistration(User user, RegistrationFinishRequest request) {
        // 1. Retrieve and consume the challenge (use-once)
        byte[] challenge = challengeStore.consumeChallenge("reg:" + request.sessionKey())
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "Ceremonia rejestracji wygasła lub została już ukończona"));

        // 2. Build the PublicKeyCredential<AttestationResponse> from the client's JSON fields
        PublicKeyCredential<AuthenticatorAttestationResponse> credential = buildAttestationCredential(request);

        // 3. Build the saved creation options (needed by the library to verify the challenge)
        PublicKeyCredentialCreationOptions creationOptions = buildCreationOptions(user, challenge);

        // 4. Delegate cryptographic verification to Webauthn4J
        RelyingPartyRegistrationRequest registrationRequest = new ImmutableRelyingPartyRegistrationRequest(
                creationOptions,
                new RelyingPartyPublicKey(credential, resolveLabel(request))
        );

        try {
            CredentialRecord savedRecord = rpOps.registerCredential(registrationRequest);
            log.info("Passkey registered: credentialId={} userId={}", savedRecord.getCredentialId(), user.getId());
        } catch (Exception ex) {
            log.warn("Passkey registration failed for userId={}: {}", user.getId(), ex.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Rejestracja klucza dostępu nieudana: " + ex.getMessage());
        }
    }

    // =========================================================================
    // AUTHENTICATION — Phase 1: Begin
    // =========================================================================

    /**
     * Starts a discoverable passkey authentication ceremony.
     * <p>
     * {@code allowCredentials} is intentionally empty — the authenticator (Face ID, Touch ID,
     * USB key, etc.) selects the appropriate passkey on its own and returns the {@code userHandle}
     * in the assertion response. The server then resolves the user from that handle.
     * <p>
     * This is the recommended WebAuthn flow: no username, no email, no typing — pure biometric/key.
     *
     * @param sessionKey a random opaque key generated by the controller, returned to the client
     * @return the request options the client passes to {@code navigator.credentials.get()}
     */
    public AuthenticationBeginResponse beginAuthentication(String sessionKey) {
        // Discoverable flow — empty allowCredentials, authenticator picks the key autonomously
        return buildBeginAuthResponse(sessionKey, List.of());
    }

    // =========================================================================
    // AUTHENTICATION — Phase 2: Finish
    // =========================================================================

    /**
     * Completes the authentication ceremony.
     * Verifies the assertion, updates the signature counter, and issues a session token.
     *
     * @param request the assertion response from the authenticator
     * @return an {@link AuthResult} containing session (and optionally remember-me) tokens
     */
    @Transactional
    public AuthResult finishAuthentication(AuthenticationFinishRequest request) {
        // 1. Retrieve and consume the challenge (use-once, replay-proof)
        byte[] challenge = challengeStore.consumeChallenge("auth:" + request.sessionKey())
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "Ceremonia uwierzytelniania wygasła lub została już ukończona"));

        // 2. Build assertion credential, verify cryptographically, update last-used
        PublicKeyCredential<AuthenticatorAssertionResponse> credential = buildAssertionCredential(request);
        UserCredential storedCred = verifyAssertionAndUpdate(challenge, request.rawId(), request.id(), credential);

        // 3. Resolve the owning user (userHandle from authenticator or FK fallback)
        User user = resolveUserFromAssertion(request, storedCred);
        log.info("Passkey authentication success: userId={} credentialId={}", user.getId(), request.id());

        // 4. Issue session tokens — same mechanism as PESEL+password login
        String sessionToken = tokenService.createSession(user.getId());
        String rememberMeToken = request.rememberMe()
                ? tokenService.createRememberMeToken(user.getId(), request.clientType())
                : null;

        return new AuthResult(sessionToken, rememberMeToken);
    }

    // =========================================================================
    // Internal builders
    // =========================================================================

    /**
     * Builds an {@link AuthenticationBeginResponse} for a given {@code sessionKey} and
     * {@code allowCredentials} list. Called by both {@link #beginAuthentication} (empty list
     * → discoverable flow) and {@link #beginSudoAuthentication} (user-scoped list).
     */
    private AuthenticationBeginResponse buildBeginAuthResponse(
            String sessionKey,
            List<AuthenticationBeginResponse.AllowedCredential> allowCredentials) {

        byte[] challenge = challengeStore.generateAndStore("auth:" + sessionKey);
        return new AuthenticationBeginResponse(
                sessionKey,
                Base64.getUrlEncoder().withoutPadding().encodeToString(challenge),
                props.getChallengeTtlSeconds() * 1000L,
                props.getRpId(),
                allowCredentials,
                "required"   // biometric/PIN must be performed (UP + UV flags)
        );
    }

    /**
     * Verifies a WebAuthn assertion against {@code challenge}, looks up the credential,
     * guards against legacy credentials without stored attestation, delegates
     * cryptographic verification to Webauthn4J, updates the last-used timestamp, and
     * returns the persisted {@link UserCredential}.
     * <p>
     * Shared by {@link #finishAuthentication} and {@link #verifyForSudoMode} to avoid
     * duplicating the assertion verification pipeline.
     */
    private UserCredential verifyAssertionAndUpdate(byte[] challenge, String rawId, String credentialId,
                                                    PublicKeyCredential<AuthenticatorAssertionResponse> credential) {

        byte[] rawCredId = Base64.getUrlDecoder().decode(rawId);
        UserCredential storedCred = credentialRepository.findByCredentialId(rawCredId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.UNAUTHORIZED, "Nieznany klucz dostępu — dane uwierzytelniające nie są zarejestrowane na tym serwerze"));

        // Guard: credentials registered before the attestation storage fix have null bytes.
        // Reject early with a clear message instead of an opaque NPE from Webauthn4J.
        if (storedCred.getAttestationObject() == null) {
            log.warn("Credential {} has no stored attestationObject (registered before schema migration). " +
                    "User must delete and re-register this passkey.", credentialId);
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                    "Ten klucz dostępu został zarejestrowany przed aktualizacją serwera i musi zostać ponownie zarejestrowany. " +
                            "Usuń ten klucz dostępu i dodaj go ponownie.");
        }

        PublicKeyCredentialRequestOptions requestOptions = buildDiscoverableRequestOptions(challenge);
        try {
            rpOps.authenticate(new RelyingPartyAuthenticationRequest(requestOptions, credential));
        } catch (Exception ex) {
            log.warn("Passkey assertion failed for credentialId={}: {}", credentialId, ex.getMessage());
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Weryfikacja klucza dostępu nieudana");
        }

        storedCred.setLastUsedAt(Instant.now());
        credentialRepository.save(storedCred);
        return storedCred;
    }

    /**
     * Resolves the authenticated user from the assertion.
     *
     * <p>WebAuthn spec §7.3 step 6: if {@code userHandle} is present in the assertion response,
     * it MUST identify the user (it is the canonical source of truth for discoverable credentials).
     * If absent (non-resident key or older authenticator), we fall back to the credential's FK.
     *
     * <p>We also verify that the userHandle (if present) matches the credential's owner,
     * preventing a credential-swap attack where an attacker presents a valid assertion for
     * credential A but claims it belongs to user B.
     */
    private User resolveUserFromAssertion(AuthenticationFinishRequest request, UserCredential storedCred) {
        String userHandleB64 = request.response().userHandle();

        if (userHandleB64 != null && !userHandleB64.isBlank()) {
            byte[] handleBytes = Base64.getUrlDecoder().decode(userHandleB64);
            User userByHandle = userRepository.findByWebauthnUserHandle(handleBytes)
                    .orElseThrow(() -> new ResponseStatusException(
                            HttpStatus.UNAUTHORIZED, "Identyfikator użytkownika nie pasuje do żadnego zarejestrowanego konta"));

            // Integrity check: the credential must belong to the user identified by the handle
            if (!userByHandle.getId().equals(storedCred.getUser().getId())) {
                log.warn("userHandle/credential owner mismatch: handleUserId={} credentialUserId={}",
                        userByHandle.getId(), storedCred.getUser().getId());
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Weryfikacja klucza dostępu nieudana");
            }
            return userByHandle;
        }

        // No userHandle in assertion (non-discoverable / non-resident key) — fall back to FK
        return storedCred.getUser();
    }

    // =========================================================================
    // SUDO MODE — Begin
    // =========================================================================

    /**
     * Starts a passkey authentication ceremony scoped to a specific user's credentials,
     * for use as a 2FA login step. Identical mechanism to sudo authentication.
     *
     * @param userId     the user's ID (from the pending 2FA token)
     * @param sessionKey an opaque key for this ceremony
     * @return the request options the client passes to {@code navigator.credentials.get()}
     */
    public AuthenticationBeginResponse beginLoginAuthentication(Long userId, String sessionKey) {
        return beginUserScopedAuthentication(userId, sessionKey);
    }

    public AuthenticationBeginResponse beginSudoAuthentication(Long userId, String sessionKey) {
        return beginUserScopedAuthentication(userId, sessionKey);
    }

    private AuthenticationBeginResponse beginUserScopedAuthentication(Long userId, String sessionKey) {
        List<AuthenticationBeginResponse.AllowedCredential> allowCredentials =
                credentialRepository.findAllByUserId(userId).stream()
                        .map(c -> new AuthenticationBeginResponse.AllowedCredential(
                                "public-key",
                                Base64.getUrlEncoder().withoutPadding().encodeToString(c.getCredentialId()),
                                parseTransportList(c.getTransports())
                        ))
                        .toList();
        return buildBeginAuthResponse(sessionKey, allowCredentials);
    }

    // =========================================================================
    // SUDO MODE — Finish
    // =========================================================================

    /**
     * Completes the sudo mode passkey verification ceremony.
     * <p>
     * Verifies the WebAuthn assertion cryptographically and confirms that the credential
     * used belongs to {@code authenticatedUserId}. Does NOT issue new session tokens —
     * the caller is responsible for activating sudo mode on the existing session.
     *
     * @param request             the assertion response from the authenticator
     * @param authenticatedUserId the ID of the currently authenticated user (from SecurityContext)
     * @throws org.springframework.web.server.ResponseStatusException 400 if ceremony expired,
     *                                                                401 if verification fails or credential ownership mismatch
     */
    @Transactional
    public void verifyForSudoMode(SudoFinishRequest request, Long authenticatedUserId) {
        // 1. Retrieve and consume the challenge (use-once, replay-proof)
        byte[] challenge = challengeStore.consumeChallenge("auth:" + request.sessionKey())
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "Ceremonia weryfikacji sudo wygasła lub została już ukończona"));

        // 2. Build assertion credential, verify cryptographically, update last-used
        PublicKeyCredential<AuthenticatorAssertionResponse> credential = buildAssertionCredential(request);
        UserCredential storedCred = verifyAssertionAndUpdate(challenge, request.rawId(), request.id(), credential);

        // 3. Ownership check: the credential MUST belong to the authenticated user
        if (!storedCred.getUser().getId().equals(authenticatedUserId)) {
            log.warn("Sudo mode: credential owner userId={} does not match authenticated userId={}",
                    storedCred.getUser().getId(), authenticatedUserId);
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Weryfikacja klucza dostępu nieudana");
        }

        log.info("Sudo mode passkey verification success: userId={} credentialId={}",
                authenticatedUserId, request.id());
    }

    // =========================================================================
    // LOGIN 2FA — Finish (passkey as second factor)
    // =========================================================================

    /**
     * Completes a passkey verification ceremony used as a 2FA login step.
     * Verifies the WebAuthn assertion and confirms that the credential belongs
     * to the expected user (from the pending token).
     *
     * @param request              the assertion response from the authenticator
     * @param expectedUserId       the user ID from the consumed pending token
     * @throws ResponseStatusException 400 if ceremony expired, 401 if verification fails
     */
    @Transactional
    public void verifyForLogin(AuthenticationFinishRequest request, Long expectedUserId) {
        byte[] challenge = challengeStore.consumeChallenge("auth:" + request.sessionKey())
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "Ceremonia weryfikacji logowania wygasła lub została już ukończona"));

        PublicKeyCredential<AuthenticatorAssertionResponse> credential = buildAssertionCredential(request);
        UserCredential storedCred = verifyAssertionAndUpdate(challenge, request.rawId(), request.id(), credential);

        if (!storedCred.getUser().getId().equals(expectedUserId)) {
            log.warn("Login 2FA: credential owner userId={} does not match expected userId={}",
                    storedCred.getUser().getId(), expectedUserId);
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Weryfikacja klucza dostępu nieudana");
        }

        log.info("Login 2FA passkey verification success: userId={} credentialId={}",
                expectedUserId, request.id());
    }

    // =========================================================================
    // Credential management helpers
    // =========================================================================

    @Transactional(readOnly = true)
    public List<UserCredential> listCredentials(Long userId) {
        return credentialRepository.findAllByUserId(userId);
    }

    @Transactional
    public void deleteCredential(Long credentialId, Long userId) {
        // Authorization guard: only delete if the credential belongs to this user
        if (!credentialRepository.findById(credentialId)
                .map(c -> c.getUser().getId().equals(userId))
                .orElse(false)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Klucz dostępu nie znaleziony");
        }
        credentialRepository.deleteByIdAndUserId(credentialId, userId);
    }

    private PublicKeyCredential<AuthenticatorAttestationResponse> buildAttestationCredential(
            RegistrationFinishRequest req) {

        AuthenticatorAttestationResponse.AuthenticatorAttestationResponseBuilder responseBuilder =
                AuthenticatorAttestationResponse.builder()
                        .clientDataJSON(Bytes.fromBase64(req.response().clientDataJSON()))
                        .attestationObject(Bytes.fromBase64(req.response().attestationObject()));

        // Parse transports if present
        if (req.response().transports() != null && !req.response().transports().isBlank()) {
            List<AuthenticatorTransport> transports = Arrays.stream(req.response().transports().split(","))
                    .map(String::trim)
                    .filter(s -> !s.isBlank())
                    .map(AuthenticatorTransport::valueOf)
                    .toList();
            responseBuilder.transports(transports);
        }

        return PublicKeyCredential.<AuthenticatorAttestationResponse>builder()
                .id(req.id())
                .rawId(Bytes.fromBase64(req.rawId()))
                .type(PublicKeyCredentialType.PUBLIC_KEY)
                .response(responseBuilder.build())
                .build();
    }

    private PublicKeyCredential<AuthenticatorAssertionResponse> buildAssertionCredential(
            AuthenticationFinishRequest req) {
        return buildAssertionCredentialRaw(
                req.id(), req.rawId(),
                req.response().clientDataJSON(),
                req.response().authenticatorData(),
                req.response().signature(),
                req.response().userHandle()
        );
    }

    private PublicKeyCredential<AuthenticatorAssertionResponse> buildAssertionCredential(
            SudoFinishRequest req) {
        return buildAssertionCredentialRaw(
                req.id(), req.rawId(),
                req.response().clientDataJSON(),
                req.response().authenticatorData(),
                req.response().signature(),
                req.response().userHandle()
        );
    }

    private PublicKeyCredential<AuthenticatorAssertionResponse> buildAssertionCredentialRaw(
            String id, String rawId,
            String clientDataJSON, String authenticatorData, String signature, String userHandle) {

        AuthenticatorAssertionResponse.AuthenticatorAssertionResponseBuilder responseBuilder =
                AuthenticatorAssertionResponse.builder()
                        .clientDataJSON(Bytes.fromBase64(clientDataJSON))
                        .authenticatorData(Bytes.fromBase64(authenticatorData))
                        .signature(Bytes.fromBase64(signature));

        if (userHandle != null && !userHandle.isBlank()) {
            responseBuilder.userHandle(Bytes.fromBase64(userHandle));
        }

        return PublicKeyCredential.<AuthenticatorAssertionResponse>builder()
                .id(id)
                .rawId(Bytes.fromBase64(rawId))
                .type(PublicKeyCredentialType.PUBLIC_KEY)
                .response(responseBuilder.build())
                .build();
    }

    private PublicKeyCredentialCreationOptions buildCreationOptions(User user, byte[] challenge) {
        return PublicKeyCredentialCreationOptions.builder()
                .rp(PublicKeyCredentialRpEntity.builder()
                        .id(props.getRpId())
                        .name(props.getRpName())
                        .build())
                .user(ImmutablePublicKeyCredentialUserEntity.builder()
                        .id(new Bytes(user.getWebauthnUserHandle()))
                        .name(user.getEmail())
                        .displayName(user.getFirstName() + " " + user.getLastName())
                        .build())
                .challenge(new Bytes(challenge))
                .pubKeyCredParams(List.of(
                        PublicKeyCredentialParameters.ES256,   // ECDSA P-256 — Face ID, Touch ID, USB keys
                        PublicKeyCredentialParameters.RS256    // RSA — broader compatibility
                ))
                .authenticatorSelection(AuthenticatorSelectionCriteria.builder()
                        // null attachment = no preference → platform (Face ID/Touch ID) AND roaming (USB/NFC) both work
                        .residentKey(ResidentKeyRequirement.REQUIRED)       // must store userHandle on device (discoverable)
                        .userVerification(UserVerificationRequirement.REQUIRED) // biometric/PIN required
                        .build())
                .attestation(AttestationConveyancePreference.NONE)          // max privacy, no attestation chain needed
                .build();
    }

    /**
     * Builds request options for the discoverable credential flow.
     * <p>
     * Empty {@code allowCredentials} signals to both the authenticator and the Webauthn4J library
     * that any passkey for this RP is acceptable. The authenticator selects the key autonomously
     * (based on resident key storage) and returns the {@code userHandle} in the assertion,
     * which we use to identify the user in {@link #resolveUserFromAssertion}.
     * <p>
     * {@code userVerification = REQUIRED} enforces biometric/PIN (UP + UV flags must both be set).
     */
    private PublicKeyCredentialRequestOptions buildDiscoverableRequestOptions(byte[] challenge) {
        return PublicKeyCredentialRequestOptions.builder()
                .challenge(new Bytes(challenge))
                .rpId(props.getRpId())
                .allowCredentials(List.of())   // empty = discoverable, authenticator picks the key
                .userVerification(UserVerificationRequirement.REQUIRED)
                .build();
    }

    // -------------------------------------------------------------------------
    // Transport helpers
    // -------------------------------------------------------------------------

    private List<String> parseTransportList(String csv) {
        if (csv == null || csv.isBlank()) return List.of();
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .toList();
    }

    private Set<AuthenticatorTransport> parseTransportSet(String csv) {
        if (csv == null || csv.isBlank()) return Set.of();
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .map(AuthenticatorTransport::valueOf)
                .collect(Collectors.toSet());
    }

    private String resolveLabel(RegistrationFinishRequest request) {
        if (request.label() != null && !request.label().isBlank()) {
            return request.label();
        }
        // Fallback label derived from reported transports
        if (request.response().transports() != null) {
            String t = request.response().transports().toLowerCase();
            if (t.contains("internal")) return "Built-in authenticator";
            if (t.contains("usb")) return "USB Security Key";
            if (t.contains("nfc")) return "NFC Security Key";
            if (t.contains("ble")) return "Bluetooth Security Key";
        }
        return "Security Key";
    }
}
