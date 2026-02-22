package com.github.PulsMiastaApp.PulsMiasta.Security.Service;

import org.bouncycastle.crypto.generators.Argon2BytesGenerator;
import org.bouncycastle.crypto.params.Argon2Parameters;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

/**
 * Generates a deterministic Argon2id blind index for a PESEL number.
 *
 * Design:
 *  - pepper  : external secret injected via @Value — never stored in the database
 *  - salt    : fixed per-field constant (PESEL_BLIND_INDEX_SALT) — makes the hash
 *              repeatable for the same input so it can be used as a lookup index
 *  - output  : 32-byte Argon2id hash encoded as a 64-char hex string stored in
 *              the `pesel_blind_index` column (UNIQUE INDEX)
 *
 * The combination of a strong pepper (env var) + Argon2id memory-hard KDF makes
 * brute-force enumeration of the 11-digit PESEL space computationally infeasible
 * even if the database is fully compromised, as long as the pepper remains secret.
 *
 * Parameters are intentionally kept modest (m=65536, t=3, p=1) so that login
 * latency stays acceptable (~50-100 ms) while still providing strong protection.
 * Increase memory/iterations if the threat model requires it.
 */
@Service
public class BlindIndexService {

    // Fixed per-field salt — public knowledge is acceptable because security
    // relies on the secrecy of the pepper, not the salt.
    private static final byte[] PESEL_FIELD_SALT =
            "pesel-blind-index-v1".getBytes(StandardCharsets.UTF_8);

    // Argon2id parameters
    private static final int MEMORY_KB    = 65536; // 64 MB
    private static final int ITERATIONS   = 3;
    private static final int PARALLELISM  = 1;
    private static final int OUTPUT_BYTES = 32;    // 256-bit → 64 hex chars

    private final byte[] pepperBytes;

    public BlindIndexService(@Value("${auth.pesel.pepper}") String pepper) {
        this.pepperBytes = pepper.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Computes the blind index for a raw PESEL string.
     * The result is deterministic: same pesel + same pepper → same index.
     *
     * @param rawPesel 11-digit PESEL number
     * @return 64-character lowercase hex string
     */
    public String computeIndex(String rawPesel) {
        byte[] password = (rawPesel + new String(pepperBytes, StandardCharsets.UTF_8))
                .getBytes(StandardCharsets.UTF_8);

        Argon2Parameters params = new Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                .withSalt(PESEL_FIELD_SALT)
                .withMemoryAsKB(MEMORY_KB)
                .withIterations(ITERATIONS)
                .withParallelism(PARALLELISM)
                .build();

        Argon2BytesGenerator generator = new Argon2BytesGenerator();
        generator.init(params);

        byte[] hash = new byte[OUTPUT_BYTES];
        generator.generateBytes(password, hash);

        return HexFormat.of().formatHex(hash);
    }
}
