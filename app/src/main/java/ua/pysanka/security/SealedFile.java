package ua.pysanka.security;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Pysanka: encrypted files for the clipboard (images, videos), streamed in chunks so a large video
 * never has to fit in memory.
 *
 * Format: "PYS1", 8 random bytes (nonce prefix), then chunks. Each chunk is up to {@link #CHUNK}
 * bytes of data sealed with AES-256-GCM (16-byte tag); its nonce is the prefix plus the chunk
 * number, and its associated data says whether it is the last chunk, followed by a caller-given
 * label (for the clipboard, the file's id). So chunks cannot be reordered, dropped or cut off, and
 * two files cannot be swapped, without the read failing. An empty file is one empty last chunk.
 *
 * Written in Java so the format can be checked with plain javac as well as in the unit tests.
 */
public final class SealedFile {
    private SealedFile() {}

    static final byte[] MAGIC = {'P', 'Y', 'S', '1'};
    static final int CHUNK = 64 * 1024;
    private static final int TAG = 16;
    private static final int PREFIX = 8;
    private static final SecureRandom RANDOM = new SecureRandom();

    /** Encrypts everything from {@code in} into {@code out}. Neither stream is closed. */
    public static void seal(SecretKey key, byte[] label, InputStream in, OutputStream out) throws IOException, GeneralSecurityException {
        byte[] prefix = new byte[PREFIX];
        RANDOM.nextBytes(prefix);
        out.write(MAGIC);
        out.write(prefix);
        byte[] current = new byte[CHUNK];
        byte[] next = new byte[CHUNK];
        int curLen = readFully(in, current);
        long index = 0;
        while (true) {
            // Look one chunk ahead: only then is it known whether the current one is the last
            int nextLen = curLen == CHUNK ? readFully(in, next) : 0;
            boolean last = nextLen == 0;
            out.write(cipher(Cipher.ENCRYPT_MODE, key, prefix, index, last, label).doFinal(current, 0, curLen));
            if (last) break;
            byte[] t = current; current = next; next = t;
            curLen = nextLen;
            index++;
        }
        Arrays.fill(current, (byte) 0);
        Arrays.fill(next, (byte) 0);
    }

    /** A stream of the original bytes. Throws IOException on a wrong key or any change to the file. */
    public static InputStream open(SecretKey key, byte[] label, InputStream in) throws IOException {
        byte[] head = new byte[MAGIC.length + PREFIX];
        if (readFully(in, head) != head.length || !Arrays.equals(Arrays.copyOf(head, MAGIC.length), MAGIC)) {
            throw new IOException("Not a sealed file");
        }
        return new Reader(key, label.clone(), Arrays.copyOfRange(head, MAGIC.length, head.length), in);
    }

    private static Cipher cipher(int mode, SecretKey key, byte[] prefix, long index, boolean last, byte[] label) throws GeneralSecurityException {
        if (index > 0xFFFFFFFFL) throw new GeneralSecurityException("File too large");
        byte[] nonce = new byte[12];
        System.arraycopy(prefix, 0, nonce, 0, PREFIX);
        nonce[8] = (byte) (index >>> 24);
        nonce[9] = (byte) (index >>> 16);
        nonce[10] = (byte) (index >>> 8);
        nonce[11] = (byte) index;
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(mode, key, new GCMParameterSpec(TAG * 8, nonce));
        byte[] aad = new byte[1 + label.length];
        aad[0] = (byte) (last ? 1 : 0);
        System.arraycopy(label, 0, aad, 1, label.length);
        c.updateAAD(aad);
        return c;
    }

    /** Reads until {@code buf} is full or the stream ends; returns how much was read. */
    static int readFully(InputStream in, byte[] buf) throws IOException {
        int n = 0;
        while (n < buf.length) {
            int r = in.read(buf, n, buf.length - n);
            if (r < 0) break;
            n += r;
        }
        return n;
    }

    private static final class Reader extends InputStream {
        private final SecretKey key;
        private final byte[] label;
        private final byte[] prefix;
        private final InputStream in;
        private byte[] sealed = new byte[CHUNK + TAG];
        private byte[] following = new byte[CHUNK + TAG];
        private int sealedLen;
        private byte[] plain = new byte[0];
        private int pos;
        private long index;
        private boolean done;

        Reader(SecretKey key, byte[] label, byte[] prefix, InputStream in) throws IOException {
            this.key = key;
            this.label = label;
            this.prefix = prefix;
            this.in = in;
            sealedLen = readFully(in, sealed);
            if (sealedLen < TAG) throw new IOException("Damaged sealed file");
        }

        private boolean fill() throws IOException {
            while (pos >= plain.length) {
                if (done) return false;
                // A full-size sealed chunk may or may not be the last one: read ahead to know
                int followingLen = sealedLen == CHUNK + TAG ? readFully(in, following) : 0;
                boolean last = followingLen == 0;
                if (!last && followingLen < TAG) throw new IOException("Damaged sealed file");
                try {
                    plain = cipher(Cipher.DECRYPT_MODE, key, prefix, index, last, label).doFinal(sealed, 0, sealedLen);
                } catch (GeneralSecurityException e) {
                    throw new IOException("Sealed file was changed or the key is wrong", e);
                }
                pos = 0;
                index++;
                if (last) {
                    done = true;
                } else {
                    byte[] t = sealed; sealed = following; following = t;
                    sealedLen = followingLen;
                }
            }
            return true;
        }

        @Override
        public int read() throws IOException {
            if (!fill()) return -1;
            return plain[pos++] & 0xFF;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (len == 0) return 0;
            if (!fill()) return -1;
            int n = Math.min(len, plain.length - pos);
            System.arraycopy(plain, pos, b, off, n);
            pos += n;
            return n;
        }

        @Override
        public void close() throws IOException {
            Arrays.fill(plain, (byte) 0);
            in.close();
        }
    }

    /** Whole file into memory; only for small files such as thumbnails. */
    public static byte[] readAll(SecretKey key, byte[] label, InputStream in) throws IOException {
        try (InputStream s = open(key, label, in)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16 * 1024];
            int n;
            while ((n = s.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }
}
