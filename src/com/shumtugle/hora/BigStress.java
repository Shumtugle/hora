package com.shumtugle.hora;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.Charset;

/**
 * A large stress list kept on disk and searched in place: sorted lines of
 * "word TAB index-of-stressed-letter" in UTF-8. Only the line offsets live in
 * memory, so a list of well over a million words costs a few megabytes.
 */
final class BigStress {
    private static final String ASSET = "lexicons/stress-big.tsv";
    private static final String FILE = "stress-big.tsv";
    private static final Charset UTF8 = Charset.forName("UTF-8");

    private final MappedByteBuffer data;
    private final int[] lines;

    private BigStress(MappedByteBuffer data, int[] lines) {
        this.data = data;
        this.lines = lines;
    }

    /** Unpacks the list once per app version and maps it; null if it is absent. */
    static BigStress open(Context c) {
        try {
            File dir = new File(c.getFilesDir(), Lexicon.IMPORT_DIR + "-builtin");
            File f = new File(dir, FILE);
            File stamp = new File(dir, FILE + ".version");
            // Unpacked again only when the list itself changed, not with every new build of the app.
            String version = BuildConfigLite.assetStamp(c, "lexicons/stress-big.stamp");
            File idx = new File(dir, FILE + ".idx");
            if (!f.isFile() || !version.equals(Lexicon.readText(stamp))) {
                if (!dir.isDirectory() && !dir.mkdirs()) {
                    return null;
                }
                idx.delete();
                copy(c.getAssets().open(ASSET), f);
                Lexicon.writeFile(stamp, version);
            }
            RandomAccessFile raf = new RandomAccessFile(f, "r");
            try {
                FileChannel ch = raf.getChannel();
                MappedByteBuffer map = ch.map(FileChannel.MapMode.READ_ONLY, 0, ch.size());
                int[] lines = readIndex(idx);
                if (lines == null) {
                    lines = index(map);
                    writeIndex(idx, lines);
                }
                return new BigStress(map, lines);
            } finally {
                raf.close();
            }
        } catch (IOException e) {
            return null;
        }
    }

    /** Where every line starts, read in large blocks rather than byte by byte. */
    private static int[] index(MappedByteBuffer map) {
        int size = map.limit();
        byte[] block = new byte[1 << 20];
        int n = 0;
        for (int at = 0; at < size; at += block.length) {
            int len = Math.min(block.length, size - at);
            map.position(at);
            map.get(block, 0, len);
            for (int i = 0; i < len; i++) {
                if (block[i] == '\n') {
                    n++;
                }
            }
        }
        int[] starts = new int[n];
        int k = 0;
        int start = 0;
        for (int at = 0; at < size && k < n; at += block.length) {
            int len = Math.min(block.length, size - at);
            map.position(at);
            map.get(block, 0, len);
            for (int i = 0; i < len && k < n; i++) {
                if (block[i] == '\n') {
                    starts[k++] = start;
                    start = at + i + 1;
                }
            }
        }
        map.position(0);
        return starts;
    }

    /** The line index kept beside the list, so a new start of the voice does not count the lines again. */
    private static int[] readIndex(File idx) {
        if (!idx.isFile() || idx.length() % 4 != 0 || idx.length() == 0) {
            return null;
        }
        try {
            RandomAccessFile raf = new RandomAccessFile(idx, "r");
            try {
                FileChannel ch = raf.getChannel();
                java.nio.IntBuffer ints = ch.map(FileChannel.MapMode.READ_ONLY, 0, ch.size()).asIntBuffer();
                int[] out = new int[ints.remaining()];
                ints.get(out);
                return out;
            } finally {
                raf.close();
            }
        } catch (IOException e) {
            return null;
        }
    }

    private static void writeIndex(File idx, int[] lines) {
        File tmp = new File(idx.getPath() + ".tmp");
        try {
            java.nio.ByteBuffer b = java.nio.ByteBuffer.allocate(lines.length * 4);
            b.asIntBuffer().put(lines);
            FileOutputStream out = new FileOutputStream(tmp);
            try {
                out.write(b.array());
            } finally {
                out.close();
            }
            if (!tmp.renameTo(idx)) {
                tmp.delete();
            }
        } catch (IOException e) {
            tmp.delete();
        }
    }

    /** Index of the stressed letter in the word, or -1 when the word is not listed. */
    int stressOf(String lowerWord) {
        byte[] key = lowerWord.getBytes(UTF8);
        int lo = 0;
        int hi = lines.length - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            int start = lines[mid];
            int cmp = compare(key, start);
            if (cmp == 0) {
                return readIndex(start + key.length + 1);
            }
            if (cmp < 0) {
                hi = mid - 1;
            } else {
                lo = mid + 1;
            }
        }
        return -1;
    }

    /** Byte-wise comparison of the key with the word that starts at pos. */
    private int compare(byte[] key, int pos) {
        int i = 0;
        while (true) {
            byte b = data.get(pos + i);
            boolean wordEnded = b == '\t';
            if (i == key.length) {
                return wordEnded ? 0 : -1;
            }
            if (wordEnded) {
                return 1;
            }
            int d = (key[i] & 0xff) - (b & 0xff);
            if (d != 0) {
                return d;
            }
            i++;
        }
    }

    private int readIndex(int pos) {
        int v = 0;
        byte b;
        while ((b = data.get(pos++)) >= '0' && b <= '9') {
            v = v * 10 + (b - '0');
        }
        return v;
    }

    private static void copy(InputStream in, File target) throws IOException {
        File tmp = new File(target.getPath() + ".tmp");
        try {
            OutputStream out = new FileOutputStream(tmp);
            try {
                byte[] buf = new byte[1 << 16];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                }
            } finally {
                out.close();
            }
        } finally {
            in.close();
        }
        if (!tmp.renameTo(target)) {
            throw new IOException("rename failed");
        }
    }
}
