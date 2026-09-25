package com.rpcsv.app;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * FileProvider minimo para o update in-app.
 *
 * O APK oficial do Vita3K nao embute androidx.core, e a build do RPCSV e
 * hand-rolled (aapt2 + javac + d8), sem dependencias externas. Um
 * android.support.FileProvider ausente faria o instalador estourar em runtime,
 * entao este provisor serve um unico arquivo: o .apk baixado em
 * getExternalFilesDir("update").
 */
public class UpdateProvider extends ContentProvider {

    public static final String AUTHORITY = "com.rpcsv.app.updateprovider";

    /** Raiz permitida: getExternalFilesDir("update"). Nada fora dela e servido. */
    private File updateDir() {
        File base = getContext() == null ? null : getContext().getExternalFilesDir("update");
        return base;
    }

    private File resolve(Uri uri) throws FileNotFoundException {
        String name = uri.getLastPathSegment();
        if (name == null || name.isEmpty() || name.indexOf('/') >= 0 || name.indexOf('\\') >= 0 || name.contains("..")) {
            throw new FileNotFoundException("segmento invalido: " + name);
        }
        File dir = updateDir();
        if (dir == null) throw new FileNotFoundException("sem diretorio externo");
        File f = new File(dir, name);
        // Defense in depth: o nome ja nao pode conter separadores, mas o
        // canonical path evita servir algo fora da raiz por encoding esperto.
        try {
            String cd = dir.getCanonicalPath();
            String cf = f.getCanonicalPath();
            if (!cf.startsWith(cd + File.separator)) throw new FileNotFoundException("fora da raiz");
        } catch (FileNotFoundException e) {
            throw e;
        } catch (Exception e) {
            throw new FileNotFoundException(e.getMessage());
        }
        if (!f.isFile()) throw new FileNotFoundException("nao existe: " + name);
        return f;
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        File f = resolve(uri);
        int m = ParcelFileDescriptor.MODE_READ_ONLY;
        if (mode != null && mode.contains("w")) m = ParcelFileDescriptor.MODE_READ_WRITE | ParcelFileDescriptor.MODE_CREATE | ParcelFileDescriptor.MODE_TRUNCATE;
        return ParcelFileDescriptor.open(f, m);
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        File f;
        try {
            f = resolve(uri);
        } catch (FileNotFoundException e) {
            return null;
        }
        String[] cols = projection != null ? projection : new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
        MatrixCursor c = new MatrixCursor(cols, 1);
        Object[] row = new Object[cols.length];
        for (int i = 0; i < cols.length; i++) {
            if (OpenableColumns.DISPLAY_NAME.equals(cols[i])) row[i] = f.getName();
            else if (OpenableColumns.SIZE.equals(cols[i])) row[i] = f.length();
            else row[i] = null;
        }
        c.addRow(row);
        return c;
    }

    @Override
    public String getType(Uri uri) {
        return "application/vnd.android.package-archive";
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("read-only");
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("read-only");
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("read-only");
    }
}
