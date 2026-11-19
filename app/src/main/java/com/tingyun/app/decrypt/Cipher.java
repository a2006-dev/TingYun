package com.tingyun.app.decrypt;

public final class Cipher {

    private Cipher() {
    }

    public static final int KEY128 = 128;
    public static final int BOUNDARY = 0x7FFF;

    public static byte[] qmc1Transform(byte[] data, byte[] key, int offsetStart) {
        byte[] out = data.clone();
        int pos = offsetStart;
        int i = 0;
        int total = out.length;

        while (i < total) {
            int phase;
            int take;
            if (pos <= BOUNDARY) {
                take = Math.min(BOUNDARY + 1 - pos, total - i);
                phase = pos % KEY128;
            } else {
                int r = pos % BOUNDARY;
                take = Math.min(BOUNDARY - r, total - i);
                phase = r % KEY128;
            }
            xorInPlace(out, i, take, key, phase);
            i += take;
            pos += take;
        }
        return out;
    }

    public static void xorInPlace(byte[] data, int start, int len, byte[] key, int phase) {
        int klen = key.length;
        if (klen == 0) return;
        int ph = ((phase % klen) + klen) % klen;
        for (int i = 0; i < len; i++) {
            data[start + i] ^= key[(ph + i) % klen];
        }
    }

    public static byte[] xorKeyStream(byte[] data, byte[] key) {
        byte[] out = data.clone();
        xorInPlace(out, 0, out.length, key, 0);
        return out;
    }

    public static final int TEA_DELTA = 0x9E3779B9;
    public static final int TEA_ROUNDS = 16;
    public static final int TEA_SALT_LEN = 2;
    public static final int TEA_ZERO_LEN = 7;

    private static int teaMix(int value, int s, int k1, int k2) {
        int left = ((value << 4) + k1);
        int right = ((value >>> 5) + k2);
        int mid = (s + value);
        return left ^ mid ^ right;
    }

    public static long teaDecryptBlock(long block, int[] key) {
        int hi = (int) (block >>> 32);
        int lo = (int) (block & 0xFFFFFFFFL);
        int s = TEA_DELTA * TEA_ROUNDS;
        for (int i = 0; i < TEA_ROUNDS; i++) {
            lo = lo - teaMix(hi, s, key[2], key[3]);
            hi = hi - teaMix(lo, s, key[0], key[1]);
            s = s - TEA_DELTA;
        }
        return ((long) hi << 32) | (lo & 0xFFFFFFFFL);
    }

    public static byte[] teaCbcDecrypt(byte[] ciphertext, byte[] key16) throws DecryptException {
        if (key16.length != 16) throw new DecryptException("TEA 密钥长度必须是 16");
        if (ciphertext.length % 8 != 0 || ciphertext.length < 10) {
            throw new DecryptException("TEA 非法密文长度 " + ciphertext.length);
        }
        int[] words = new int[4];
        for (int i = 0; i < 4; i++) {
            words[i] = ((key16[i * 4] & 0xFF) << 24)
                    | ((key16[i * 4 + 1] & 0xFF) << 16)
                    | ((key16[i * 4 + 2] & 0xFF) << 8)
                    | (key16[i * 4 + 3] & 0xFF);
        }

        long ivPrev = 0L;
        long ivCur = 0L;
        byte[] plain = new byte[ciphertext.length];
        int p = 0;

        for (int i = 0; i < ciphertext.length; i += 8) {
            long block = 0L;
            for (int k = 0; k < 8; k++) {
                block = (block << 8) | (ciphertext[i + k] & 0xFFL);
            }
            long mixed = block ^ ivCur;
            long nextIv = teaDecryptBlock(mixed, words);
            long chunk = nextIv ^ ivPrev;
            for (int k = 7; k >= 0; k--) {
                plain[p + k] = (byte) (chunk & 0xFF);
                chunk >>>= 8;
            }
            p += 8;
            ivPrev = block;
            ivCur = nextIv;
        }

        int pad = plain[0] & 0b111;
        int bodyStart = 1 + pad + TEA_SALT_LEN;
        int bodyEnd = ciphertext.length - TEA_ZERO_LEN;
        if (bodyEnd < bodyStart) throw new DecryptException("TEA 密文过短");

        for (int i = bodyEnd; i < plain.length; i++) {
            if (plain[i] != 0) throw new DecryptException("TEA 尾部校验失败");
        }

        int len = bodyEnd - bodyStart;
        byte[] out = new byte[len];
        System.arraycopy(plain, bodyStart, out, 0, len);
        return out;
    }

    public static final int MAP_LEN = 128;
    public static final int MAP_MAGIC = 71214;
    public static final int RC4_FIRST_SEGMENT = 0x80;
    public static final int RC4_SEGMENT = 0x1400;
    public static final int RC4_STREAM_CACHE = RC4_SEGMENT + 512;

    public static byte[] compressKey(byte[] longKey) throws DecryptException {
        int n = longKey.length;
        if (n == 0) throw new DecryptException("Map 密钥为空");
        byte[] out = new byte[MAP_LEN];
        for (int i = 0; i < MAP_LEN; i++) {
            int idx = (i * i + MAP_MAGIC) % n;
            int shift = (idx + 4) % 8;
            out[i] = (byte) (((longKey[idx] & 0xFF) << shift) | ((longKey[idx] & 0xFF) >>> shift));
        }
        return out;
    }

    public static double qmc2Hash(byte[] key) {
        long h = 1;
        for (byte b : key) {
            int value = b & 0xFF;
            if (value == 0) continue;
            long nxt = (h * value);
            
            nxt = nxt & 0xFFFFFFFFL;
            if (nxt == 0 || nxt <= h) break;
            h = nxt;
        }
        return (double) h;
    }

    public static int segmentKey(int segId, int seed, double h) {
        if (seed == 0) return 0;
        long denom = ((long) (segId + 1) * (seed & 0xFF)) & 0xFFFFFFFFL;
        if (denom == 0) return 0;
        return (int) (h / (double) denom * 100.0);
    }

    public interface Stream {
        byte[] decrypt(byte[] data, int offset);
    }

    public static Stream makeStream(byte[] masterKey) throws DecryptException {
        if (masterKey == null || masterKey.length == 0) throw new DecryptException("主密钥为空");
        if (masterKey.length <= 300) return new MapStream(masterKey);
        return new Rc4Stream(masterKey);
    }

    public static final class MapStream implements Stream {
        private final byte[] key;

        public MapStream(byte[] masterKey) throws DecryptException {
            this.key = compressKey(masterKey);
        }

        @Override
        public byte[] decrypt(byte[] data, int offset) {
            return qmc1Transform(data, key, offset);
        }
    }

    public static final class Rc4Stream implements Stream {
        private final byte[] key;
        private final int[] state;
        private final int n;
        private int si = 0;
        private int sj = 0;
        private final double hash;
        private final byte[] keyStream;

        public Rc4Stream(byte[] masterKey) {
            this.key = masterKey.clone();
            this.n = this.key.length;
            this.state = new int[n];
            for (int i = 0; i < n; i++) state[i] = i & 0xFF;

            int j = 0;
            for (int i = 0; i < n; i++) {
                j = (j + state[i] + (key[i % n] & 0xFF)) % n;
                int t = state[i];
                state[i] = state[j];
                state[j] = t;
            }
            this.hash = qmc2Hash(this.key);
            this.keyStream = new byte[RC4_STREAM_CACHE];
            for (int i = 0; i < RC4_STREAM_CACHE; i++) keyStream[i] = (byte) nextByte();
        }

        private int nextByte() {
            si = (si + 1) % n;
            sj = (sj + state[si]) % n;
            int t = state[si];
            state[si] = state[sj];
            state[sj] = t;
            return state[(state[si] + state[sj]) % n] & 0xFF;
        }

        @Override
        public byte[] decrypt(byte[] data, int offset) {
            byte[] out = data.clone();
            int total = out.length;
            int pos = offset;
            int start = 0;

            if (pos < RC4_FIRST_SEGMENT) {
                int take = Math.min(RC4_FIRST_SEGMENT - pos, total);
                for (int j = 0; j < take; j++) {
                    int p = pos + j;
                    int seed = key[p % n] & 0xFF;
                    int idx = segmentKey(p, seed, hash) % n;
                    out[j] ^= key[(idx + n) % n];
                }
                start += take;
                pos += take;
            }

            while (start < total) {
                int segId = pos / RC4_SEGMENT;
                int blockOff = pos % RC4_SEGMENT;
                int seed = key[segId % n] & 0xFF;
                int skip = segmentKey(segId, seed, hash) & 0x1FF;
                int take = Math.min(RC4_SEGMENT - blockOff, total - start);

                int from = skip + blockOff;
                for (int j = 0; j < take; j++) {
                    int si2 = from + j;
                    if (si2 >= keyStream.length) si2 = si2 % keyStream.length;
                    out[start + j] ^= keyStream[si2];
                }
                start += take;
                pos += take;
            }
            return out;
        }
    }

    public static byte[] ncmStreamBytes(byte[] key) {
        int[] sbox = new int[256];
        for (int i = 0; i < 256; i++) sbox[i] = i;

        int j = 0;
        int klen = key.length;
        for (int i = 0; i < 256; i++) {
            j = (j + sbox[i] + (key[i % klen] & 0xFF)) & 0xFF;
            int t = sbox[i];
            sbox[i] = sbox[j];
            sbox[j] = t;
        }

        byte[] box = new byte[256];
        for (int i = 0; i < 256; i++) {
            int idx = (sbox[i] + sbox[(i + sbox[i]) & 0xFF]) & 0xFF;
            box[i] = (byte) sbox[idx];
        }
        return box;
    }

    public static byte[] ncmDecryptAudio(byte[] data, byte[] box) {
        byte[] out = new byte[data.length];
        for (int i = 0; i < data.length; i++) {
            out[i] = (byte) (data[i] ^ box[(i + 1) & 0xFF]);
        }
        return out;
    }

    public static int u32le(byte[] d, int pos) {
        return (d[pos] & 0xFF)
                | ((d[pos + 1] & 0xFF) << 8)
                | ((d[pos + 2] & 0xFF) << 16)
                | ((d[pos + 3] & 0xFF) << 24);
    }

    public static int u32be(byte[] d, int pos) {
        return ((d[pos] & 0xFF) << 24)
                | ((d[pos + 1] & 0xFF) << 16)
                | ((d[pos + 2] & 0xFF) << 8)
                | (d[pos + 3] & 0xFF);
    }

    public static String sniffContainer(byte[] head) {
        if (head == null || head.length < 4) return "bin";
        if (head.length >= 4 && head[0] == 'f' && head[1] == 'L' && head[2] == 'a' && head[3] == 'C') {
            return "flac";
        }
        
        if (head[0] == 'I' && head[1] == 'D' && head[2] == '3') return "mp3";
        
        if ((head[0] & 0xFF) == 0xFF && (head[1] & 0xE0) == 0xE0) return "mp3";
        if (head[0] == 'O' && head[1] == 'g' && head[2] == 'g' && head[3] == 'S') return "ogg";
        if (head[0] == 'R' && head[1] == 'I' && head[2] == 'F' && head[3] == 'F') return "wav";
        
        if (head.length >= 8 && head[4] == 'f' && head[5] == 't' && head[6] == 'y' && head[7] == 'p') {
            return "m4a";
        }
        return "bin";
    }

    public static class DecryptException extends Exception {
        public DecryptException(String message) {
            super(message);
        }
    }
}
