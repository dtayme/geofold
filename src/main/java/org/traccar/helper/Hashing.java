/*
 * Copyright 2015 - 2026 Anton Tananaev (anton@traccar.org)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
// Modified by FOGNETX <Drew.Taylor@fognetx.com>, 2026. Modifications licensed under
// AGPL-3.0-or-later (SPDX-License-Identifier: AGPL-3.0-or-later).

package org.traccar.helper;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;

public final class Hashing {

    private static final String CURRENT_ALGORITHM = "pbkdf2-sha256";
    public static final int ITERATIONS = 600000;
    private static final int LEGACY_ITERATIONS = 1000;
    public static final int SALT_SIZE = 24;
    public static final int HASH_SIZE = 32;
    private static final int LEGACY_HASH_SIZE = 24;

    private static final SecretKeyFactory FACTORY_SHA256;
    private static final SecretKeyFactory FACTORY_SHA1;
    static {
        try {
            FACTORY_SHA256 = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            FACTORY_SHA1 = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1");
        } catch (NoSuchAlgorithmException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    public static class HashingResult {

        private final String hash;
        private final String salt;

        public HashingResult(String hash, String salt) {
            this.hash = hash;
            this.salt = salt;
        }

        public String getHash() {
            return hash;
        }

        public String getSalt() {
            return salt;
        }
    }

    private Hashing() {}

    private static byte[] function(SecretKeyFactory factory, char[] password, byte[] salt, int iterations, int hashSize) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, hashSize * Byte.SIZE);
            return factory.generateSecret(spec).getEncoded();
        } catch (InvalidKeySpecException e) {
            throw new SecurityException(e);
        }
    }

    private static final SecureRandom RANDOM = new SecureRandom();

    public static HashingResult createHash(String password) {
        byte[] salt = new byte[SALT_SIZE];
        RANDOM.nextBytes(salt);
        byte[] hash = function(FACTORY_SHA256, password.toCharArray(), salt, ITERATIONS, HASH_SIZE);
        return new HashingResult(
                CURRENT_ALGORITHM + ":" + ITERATIONS + ":" + DataConverter.printHex(hash),
                DataConverter.printHex(salt));
    }

    public static boolean validatePassword(String password, String hashHex, String saltHex) {
        if (password == null || hashHex == null || saltHex == null) {
            return false;
        }
        byte[] salt = DataConverter.parseHex(saltHex);
        HashComponents components = parseHash(hashHex);
        return slowEquals(
                components.hash(),
                function(components.factory(), password.toCharArray(), salt, components.iterations(), components.hashSize()));
    }

    public static boolean needsRehash(String hash) {
        return hash == null || !hash.startsWith(CURRENT_ALGORITHM + ":" + ITERATIONS + ":");
    }

    private record HashComponents(SecretKeyFactory factory, int iterations, int hashSize, byte[] hash) {}

    private static HashComponents parseHash(String value) {
        String[] components = value.split(":", 3);
        if (components.length == 3 && components[0].equals(CURRENT_ALGORITHM)) {
            int iterations = Integer.parseInt(components[1]);
            byte[] hash = DataConverter.parseHex(components[2]);
            return new HashComponents(FACTORY_SHA256, iterations, hash.length, hash);
        }
        return new HashComponents(FACTORY_SHA1, LEGACY_ITERATIONS, LEGACY_HASH_SIZE, DataConverter.parseHex(value));
    }

    /**
     * Compares two byte arrays in length-constant time. This comparison method
     * is used so that password hashes cannot be extracted from an on-line
     * system using a timing attack and then attacked off-line.
     */
    private static boolean slowEquals(byte[] a, byte[] b) {
        int diff = a.length ^ b.length;
        for (int i = 0; i < a.length && i < b.length; i++) {
            diff |= a[i] ^ b[i];
        }
        return diff == 0;
    }

}
