// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 FOGNETX <Drew.Taylor@fognetx.com>

package org.traccar.helper;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class HashingTest {

    @Test
    public void testCurrentHash() {
        Hashing.HashingResult result = Hashing.createHash("password");

        assertTrue(result.getHash().startsWith("pbkdf2-sha256:600000:"));
        assertTrue(Hashing.validatePassword("password", result.getHash(), result.getSalt()));
        assertFalse(Hashing.validatePassword("wrong", result.getHash(), result.getSalt()));
        assertFalse(Hashing.needsRehash(result.getHash()));
    }

    @Test
    public void testLegacyHash() {
        String legacyHash = "3ecb1d0ba8d174b929b51f33de31d84837219e9515b9a84b";
        String legacySalt = "2bb80d537b1da3e38bd30361aa855686bde0eacd7162f4f6";

        assertTrue(Hashing.validatePassword("password", legacyHash, legacySalt));
        assertFalse(Hashing.validatePassword("wrong", legacyHash, legacySalt));
        assertTrue(Hashing.needsRehash(legacyHash));
    }

}
