package com.luma.gallery.data;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;

/** Test APK provider. Framework-only so it also loads outside the instrumented app process. */
public final class MetadataFixtureProvider extends ContentProvider {
    private File image;

    @Override public boolean onCreate() {
        image = new File(getContext().getCacheDir(), "metadata-fixture.jpg");
        return true;
    }

    @Override public Bundle call(String method, String arg, Bundle extras) {
        if (!"seed".equals(method) || extras == null) throw new IllegalArgumentException("Unsupported fixture method");
        byte[] bytes = extras.getByteArray("bytes");
        if (bytes == null || bytes.length == 0 || bytes.length > 2 * 1024 * 1024) throw new IllegalArgumentException("Invalid fixture bytes");
        try (FileOutputStream output = new FileOutputStream(image)) {
            output.write(bytes);
        } catch (IOException failure) {
            throw new IllegalStateException(failure);
        }
        return new Bundle();
    }

    @Override public String getType(Uri uri) { return "image/jpeg"; }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"/photo".equals(uri.getPath()) || uri.getQuery() != null || !"r".equals(mode)) {
            throw new FileNotFoundException("The exact read-only fixture URI is required");
        }
        return ParcelFileDescriptor.open(image, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        MatrixCursor cursor = new MatrixCursor(new String[] { OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE });
        cursor.addRow(new Object[] { "metadata-fixture.jpg", image.length() });
        return cursor;
    }

    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { throw new UnsupportedOperationException(); }
}
