package com.luma.gallery.data;

import android.database.Cursor;
import android.database.MatrixCursor;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Intent;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.DocumentsProvider;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;

/** Framework-only provider in the test APK. All documents are generated fixtures. */
public final class HiddenMediaFixtureProvider extends DocumentsProvider {
    public static final String AUTHORITY = "com.luma.gallery.test.hidden";
    private File image;
    private File marker;
    private boolean revoked;
    private static final String DIR = DocumentsContract.Document.MIME_TYPE_DIR;
    private static final String[] COLUMNS = {
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
        DocumentsContract.Document.COLUMN_SIZE,
        DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        DocumentsContract.Document.COLUMN_FLAGS,
    };

    /** Bootstrap is separate because no document grant exists before the first fixture seed. */
    public static final class Control extends ContentProvider {
        @Override public boolean onCreate() { return true; }
        @Override public Bundle call(String method, String arg, Bundle extras) {
            if (!"com.luma.gallery".equals(getCallingPackage()) || (!"seed".equals(method) && !"revoke".equals(method))) {
                throw new SecurityException("Only Gallery instrumentation may manage generated fixtures");
            }
            long identity = Binder.clearCallingIdentity();
            try { return getContext().getContentResolver().call(Uri.parse("content://" + AUTHORITY), method, arg, extras); }
            finally { Binder.restoreCallingIdentity(identity); }
        }
        @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) { throw new UnsupportedOperationException(); }
        @Override public String getType(Uri uri) { return null; }
        @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
        @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException(); }
        @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
    }

    @Override public boolean onCreate() {
        image = new File(getContext().getCacheDir(), "hidden-media-fixture.jpg");
        marker = new File(getContext().getCacheDir(), "hidden-media-marker.txt");
        return true;
    }

    @Override public Bundle call(String method, String arg, Bundle extras) {
        if ("seed".equals(method)) {
            if (!"com.luma.gallery".equals(arg)) throw new SecurityException("Only the instrumented Gallery package can receive fixture access");
            byte[] bytes = extras == null ? null : extras.getByteArray("bytes");
            if (bytes == null || bytes.length == 0 || bytes.length > 1024 * 1024) throw new IllegalArgumentException("Invalid fixture bytes");
            try (FileOutputStream output = new FileOutputStream(image); FileOutputStream text = new FileOutputStream(marker)) {
                output.write(bytes);
                text.write(new byte[] { 83, 81, 76, 105, 116, 101 });
            } catch (IOException failure) { throw new IllegalStateException(failure); }
            revoked = false;
            for (String id : new String[] { "root", "dot", "denied" }) {
                getContext().grantUriPermission(arg, DocumentsContract.buildTreeDocumentUri(AUTHORITY, id),
                        Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
            }
            return new Bundle();
        }
        if ("revoke".equals(method)) {
            revoked = true;
            for (String id : new String[] { "root", "dot", "denied" }) {
                getContext().revokeUriPermission(DocumentsContract.buildTreeDocumentUri(AUTHORITY, id), Intent.FLAG_GRANT_READ_URI_PERMISSION);
            }
            return new Bundle();
        }
        return super.call(method, arg, extras);
    }

    private void accessible(String id) {
        if (revoked || "denied".equals(id)) throw new SecurityException("Fixture folder access revoked");
    }

    @Override public Cursor queryRoots(String[] projection) {
        MatrixCursor result = new MatrixCursor(new String[] {
            DocumentsContract.Root.COLUMN_ROOT_ID, DocumentsContract.Root.COLUMN_DOCUMENT_ID,
            DocumentsContract.Root.COLUMN_TITLE, DocumentsContract.Root.COLUMN_FLAGS,
        });
        result.addRow(new Object[] { "fixture", "root", "Generated hidden-media fixture", DocumentsContract.Root.FLAG_SUPPORTS_IS_CHILD });
        return result;
    }

    @Override public Cursor queryDocument(String documentId, String[] projection) {
        accessible(documentId);
        MatrixCursor result = new MatrixCursor(projection == null ? COLUMNS : projection);
        if ("root".equals(documentId)) row(result, "root", "Fixture", DIR);
        else if ("dot".equals(documentId)) row(result, "dot", ".thumbnails", DIR);
        else if ("masked".equals(documentId)) row(result, "masked", "Private", DIR);
        else row(result, documentId, fileName(documentId), mime(documentId));
        return result;
    }

    @Override public Cursor queryChildDocuments(String parentId, String[] projection, String sortOrder) {
        accessible(parentId);
        MatrixCursor result = new MatrixCursor(projection == null ? COLUMNS : projection);
        if ("root".equals(parentId)) {
            row(result, "visible.jpg", "visible.jpg", "image/jpeg");
            row(result, "dot", ".thumbnails", DIR);
            row(result, "masked", "Private", DIR);
            row(result, ".cover.jpg", ".cover.jpg", "image/jpeg");
            row(result, "preview.thumb", "preview.thumb", "application/octet-stream");
            row(result, "thumbdata", ".thumbdata4-12345", "application/octet-stream");
            row(result, "fake.jpg", "fake.jpg", "text/plain");
            // Cycle verifies the client does not recurse indefinitely.
            row(result, "root", "Loop", DIR);
        } else if ("dot".equals(parentId)) {
            row(result, "dot/preview.jpg", "preview.jpg", "image/jpeg");
            row(result, "dot/generic.JPG", "generic.JPG", "application/octet-stream");
        } else if ("masked".equals(parentId)) {
            row(result, "masked/.nomedia", ".nomedia", "application/octet-stream");
            row(result, "masked/photo.jpg", "photo.jpg", "image/jpeg");
            row(result, "masked/movie.mp4", "movie.mp4", "video/mp4");
        }
        return result;
    }

    @Override public boolean isChildDocument(String parentId, String documentId) {
        return "root".equals(parentId) || documentId.startsWith(parentId + "/");
    }

    @Override public ParcelFileDescriptor openDocument(String documentId, String mode, CancellationSignal signal) throws FileNotFoundException {
        accessible(documentId);
        if (!"r".equals(mode)) throw new FileNotFoundException("Fixture is read only");
        return ParcelFileDescriptor.open(isImage(documentId) ? image : marker, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    private static boolean isImage(String id) { return id.endsWith(".jpg") || id.endsWith(".JPG") || id.endsWith(".thumb"); }
    private static String mime(String id) { return isImage(id) ? "image/jpeg" : "application/octet-stream"; }
    private static String fileName(String id) { return id.substring(id.lastIndexOf('/') + 1); }
    private void row(MatrixCursor cursor, String id, String name, String type) {
        MatrixCursor.RowBuilder row = cursor.newRow();
        for (String column : cursor.getColumnNames()) {
            if (DocumentsContract.Document.COLUMN_DOCUMENT_ID.equals(column)) row.add(id);
            else if (DocumentsContract.Document.COLUMN_DISPLAY_NAME.equals(column)) row.add(name);
            else if (DocumentsContract.Document.COLUMN_MIME_TYPE.equals(column)) row.add(type);
            else if (DocumentsContract.Document.COLUMN_SIZE.equals(column)) row.add(isImage(id) ? image.length() : marker.length());
            else if (DocumentsContract.Document.COLUMN_LAST_MODIFIED.equals(column)) row.add(1793444400000L);
            else if (DocumentsContract.Document.COLUMN_FLAGS.equals(column)) row.add(0);
            else row.add(null);
        }
    }
}
