package com.rpcsv.app;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Log em arquivo das duas Ativities.
 *
 * <p>Motivo: o logcat deste aparelho e inviavel para diagnostico. O daemon
 * {@code misight} (MIUI) despeja milhares de linhas por segundo e rotaciona os
 * buffers antes que qualquer coisa da app seja lida. Como o sintoma relatado e
 * "o app fecha" (o processo morre, entao nao ha ultimo log), o arquivo
 * <code>rpcsv.log</code> em getExternalFilesDir(null) sobrevive a morte do
 * processo e a rotacao do logcat, e permite ler o que aconteceu ate o fim.
 */
final class AppLog {

    private static final String TAG = "RPCSV";
    private static final String FILE = "rpcsv.log";
    private static final long MAX_BYTES = 512L * 1024;
    private static final SimpleDateFormat FMT =
            new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);

    private static File dir;

    private AppLog() {
    }

    static void init(Context ctx) {
        if (dir == null && ctx != null) {
            try {
                dir = ctx.getExternalFilesDir(null);
            } catch (Throwable ignore) {
            }
        }
    }

    static File file() {
        if (dir == null) return null;
        return new File(dir, FILE);
    }

    static void i(String msg) {
        write("I", msg, null);
    }

    static void w(String msg) {
        write("W", msg, null);
    }

    static void e(String msg, Throwable t) {
        write("E", msg, t);
    }

    /** Linha de breadcrumb: o usuario le o arquivo e reconstroi a sequencia. */
    static void step(String msg) {
        write("S", msg, null);
    }

    private static void write(String lvl, String msg, Throwable t) {
        String line = FMT.format(new Date()) + " " + lvl + " " + msg;
        if (t != null) {
            StringWriter sw = new StringWriter();
            t.printStackTrace(new PrintWriter(sw));
            line = line + " | " + sw.toString().replace("\n", " \\n ");
        }
        if ("E".equals(lvl)) {
            Log.e(TAG, msg, t);
        } else if ("W".equals(lvl)) {
            Log.w(TAG, msg);
        } else {
            Log.i(TAG, msg);
        }
        append(line);
    }

    private static synchronized void append(String line) {
        File f = file();
        if (f == null) return;
        try {
            if (f.exists() && f.length() > MAX_BYTES) {
                // Rotaciona sozinho: mantem a ultima metade do historico.
                File old = new File(f.getParentFile(), FILE + ".1");
                if (old.exists() && !old.delete()) return;
                if (!f.renameTo(old)) return;
            }
            FileOutputStream out = new FileOutputStream(f, true);
            try {
                out.write((line + "\n").getBytes("UTF-8"));
            } finally {
                out.close();
            }
        } catch (Throwable ignore) {
        }
    }
}
