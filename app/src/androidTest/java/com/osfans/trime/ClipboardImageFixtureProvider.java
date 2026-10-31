/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime;

import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.ClipboardManager;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.PersistableBundle;
import android.os.ParcelFileDescriptor;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.OutputStream;
import java.util.concurrent.atomic.AtomicInteger;

/** Test APK provider runs under a different UID; grants only its fixed synthetic image URI. */
public final class ClipboardImageFixtureProvider extends ContentProvider {
    private static final AtomicInteger opens = new AtomicInteger();
    private static final AtomicInteger writes = new AtomicInteger();
    private static volatile boolean delayReads;
    private static volatile boolean delayWrites;
    private static volatile int fixturePixel;
    private static ParcelFileDescriptor heldDescriptor;
    private ClipboardManager clipboard;
    public static ClipData malformedClip(Uri uri) {
        return new ClipData(new ClipDescription("bad", new String[] { null }), new ClipData.Item(uri));
    }

    @Override public boolean onCreate() {
        clipboard = (ClipboardManager) getContext().getSystemService("clipboard");
        return clipboard != null;
    }

    @Override public Bundle call(String method, String arg, Bundle extras) {
        Uri uri = Uri.parse("content://com.x2m7.xrime.test.imagefixture/image");
        long ownerIdentity = Binder.clearCallingIdentity();
        try {
            if ("grant".equals(method)) {
                getContext().grantUriPermission("com.x2m7.xrime", uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } else if ("revoke".equals(method)) {
                getContext().revokeUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } else if ("copy".equals(method) || "copySensitive".equals(method) || "copyEmpty".equals(method)) {
                if ("copyEmpty".equals(method)) {
                    clipboard.setPrimaryClip(ClipData.newPlainText("generated empty", ""));
                } else {
                    ClipDescription description = new ClipDescription("generated image", new String[] { "image/png" });
                    if ("copySensitive".equals(method) && Build.VERSION.SDK_INT >= 24) {
                        PersistableBundle sensitiveExtras = new PersistableBundle();
                        sensitiveExtras.putBoolean("android.content.extra.IS_SENSITIVE", true);
                        description.setExtras(sensitiveExtras);
                    }
                    clipboard.setPrimaryClip(new ClipData(description, new ClipData.Item(uri)));
                }
            } else if ("slow".equals(method)) {
                delayReads = true;
            } else if ("fast".equals(method)) {
                delayReads = false;
            } else if ("slowWrite".equals(method)) {
                delayWrites = true;
            } else if ("fastWrite".equals(method)) {
                delayWrites = false;
            } else if ("writeCount".equals(method)) {
                Bundle result = new Bundle();
                result.putInt("count", writes.get());
                return result;
            } else if ("seed".equals(method)) {
                fixturePixel = Integer.parseInt(arg);
            } else if ("holdRead".equals(method)) {
                Bundle result = new Bundle();
                synchronized (ClipboardImageFixtureProvider.class) {
                    if (heldDescriptor != null) throw new IllegalStateException("Fixture descriptor is already open");
                    try {
                        heldDescriptor = getContext().getContentResolver().openFileDescriptor(Uri.parse(arg), "r");
                        result.putString("status", heldDescriptor == null ? "UNAVAILABLE" : "OK");
                    } catch (SecurityException denied) {
                        result.putString("status", "DENIED");
                    } catch (IOException denied) {
                        result.putString("status", "UNAVAILABLE");
                    }
                }
                return result;
            } else if ("readHeld".equals(method) || "closeHeld".equals(method)) {
                Bundle result = new Bundle();
                synchronized (ClipboardImageFixtureProvider.class) {
                    ParcelFileDescriptor descriptor = heldDescriptor;
                    heldDescriptor = null;
                    if (descriptor == null) {
                        result.putString("status", "UNAVAILABLE");
                    } else {
                        try (java.io.InputStream input = new ParcelFileDescriptor.AutoCloseInputStream(descriptor)) {
                            int total = 0;
                            if ("readHeld".equals(method)) {
                                byte[] bytes = new byte[4096];
                                int count;
                                while ((count = input.read(bytes)) >= 0) total += count;
                            }
                            result.putString("status", "OK");
                            result.putInt("bytes", total);
                        } catch (IOException denied) {
                            result.putString("status", "UNAVAILABLE");
                        }
                    }
                }
                return result;
            } else if ("probeRead".equals(method) || "probeWrite".equals(method) || "probeType".equals(method)) {
                Bundle result = new Bundle();
                try {
                    Uri candidate = Uri.parse(arg);
                    if ("probeType".equals(method)) {
                        String mime = getContext().getContentResolver().getType(candidate);
                        result.putString("status", mime == null ? "DENIED" : mime);
                    } else {
                        String mode = "probeWrite".equals(method) ? "rw" : "r";
                        ParcelFileDescriptor descriptor = getContext().getContentResolver().openFileDescriptor(candidate, mode);
                        if (descriptor == null) throw new FileNotFoundException("No fixture descriptor");
                        try (java.io.InputStream input = new ParcelFileDescriptor.AutoCloseInputStream(descriptor)) {
                            byte[] bytes = new byte[4096];
                            int total = 0;
                            int count;
                            while ((count = input.read(bytes)) >= 0) total += count;
                            result.putString("status", "OK");
                            result.putInt("bytes", total);
                        }
                    }
                } catch (SecurityException denied) {
                    result.putString("status", "DENIED");
                } catch (IOException denied) {
                    result.putString("status", "UNAVAILABLE");
                }
                return result;
            } else if ("openCount".equals(method)) {
                Bundle result = new Bundle();
                result.putInt("count", opens.get());
                return result;
            } else {
                throw new IllegalArgumentException("Unknown fixture action");
            }
        } finally {
            Binder.restoreCallingIdentity(ownerIdentity);
        }
        return new Bundle();
    }

    @Override public String getType(Uri uri) { return "image/png"; }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { return 0; }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { return 0; }
    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"/image".equals(uri.getPath())) throw new FileNotFoundException("Unsupported fixture request");
        if ("w".equals(mode) || "wt".equals(mode)) {
            try {
                ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
                writes.incrementAndGet();
                boolean delayThisWrite = delayWrites;
                Thread consumer = new Thread(() -> {
                    if (delayThisWrite) {
                        try { Thread.sleep(2000); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
                    }
                    try (java.io.InputStream input = new ParcelFileDescriptor.AutoCloseInputStream(pipe[0])) {
                        byte[] bytes = new byte[4096];
                        while (input.read(bytes) >= 0) { }
                    } catch (IOException ignored) {
                        // A cancelled writer may close its descriptor before this reader starts.
                    }
                }, "xrime-fixture-writer");
                consumer.start();
                return pipe[1];
            } catch (IOException failure) {
                throw new FileNotFoundException("Cannot create fixture pipe");
            }
        }
        if (!"r".equals(mode)) throw new FileNotFoundException("Unsupported fixture request");
        try {
            ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
            opens.incrementAndGet();
            boolean delayThisRead = delayReads;
            int pixelForThisRead = fixturePixel;
            Thread producer = new Thread(() -> {
                if (delayThisRead) {
                    try { Thread.sleep(2000); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
                }
                Bitmap bitmap = Bitmap.createBitmap(8, 6, Bitmap.Config.ARGB_8888);
                bitmap.setPixel(0, 0, pixelForThisRead);
                try (OutputStream output = new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])) {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, output);
                } catch (IOException ignored) {
                    // The reader can legitimately revoke or cancel its request.
                } finally {
                    bitmap.recycle();
                }
            }, "image-fixture-producer");
            producer.setDaemon(true);
            producer.start();
            return pipe[0];
        } catch (IOException failure) {
            throw new FileNotFoundException("Cannot create fixture pipe");
        }
    }
}
