package com.ammar.printbyammar;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Converts files to PCL5 raster print jobs for HP LaserJet P3015.
 * NEVER sends raw file bytes — always converts to proper PCL5 format.
 */
public class PrintJobBuilder {

    private static final String TAG = "PrintJobBuilder";
    private static final int DPI = 300;

    private Context context;

    public interface JobCallback {
        void onJobReady(byte[] data, int pageCount);
        void onPageRendered(int page, int total);
        void onError(String message);
    }

    public PrintJobBuilder(Context context) {
        this.context = context;
    }

    public void buildJob(Uri fileUri, PrintOptions options, JobCallback callback) {
        new Thread(() -> {
            try {
                String mime = context.getContentResolver().getType(fileUri);
                if (mime == null) mime = guessMime(fileUri.toString());
                Log.d(TAG, "Building job, mime=" + mime);

                if ("application/pdf".equals(mime)) {
                    buildPdfJob(fileUri, options, callback);
                } else if (mime != null && mime.startsWith("image/")) {
                    buildImageJob(fileUri, options, callback);
                } else if (mime != null && mime.startsWith("text/")) {
                    buildTextJob(fileUri, options, callback);
                } else {
                    // Try as image fallback
                    buildImageJob(fileUri, options, callback);
                }
            } catch (Exception e) {
                Log.e(TAG, "Error building job", e);
                callback.onError("Could not process file:\n" + e.getMessage());
            }
        }).start();
    }

    // ── PDF ──────────────────────────────────────────────────────────────────

    private void buildPdfJob(Uri uri, PrintOptions options, JobCallback callback) throws IOException {
        ParcelFileDescriptor pfd = context.getContentResolver().openFileDescriptor(uri, "r");
        if (pfd == null) throw new IOException("Cannot open PDF");

        PdfRenderer renderer = new PdfRenderer(pfd);
        int pageCount = renderer.getPageCount();

        ByteArrayOutputStream job = new ByteArrayOutputStream();
        writePclHeader(job, options);

        for (int i = 0; i < pageCount; i++) {
            PdfRenderer.Page page = renderer.openPage(i);

            // Render at 300 DPI
            int w = Math.min((int)(page.getWidth()  * DPI / 72f), 2480);
            int h = Math.min((int)(page.getHeight() * DPI / 72f), 3508);

            Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            bmp.eraseColor(Color.WHITE);
            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT);
            page.close();

            if (i > 0) job.write(0x0C); // form feed

            writePclRasterPage(job, bmp);
            bmp.recycle();
            callback.onPageRendered(i + 1, pageCount);
        }

        writePclFooter(job);
        renderer.close();
        pfd.close();
        callback.onJobReady(job.toByteArray(), pageCount);
    }

    // ── Image ────────────────────────────────────────────────────────────────

    private void buildImageJob(Uri uri, PrintOptions options, JobCallback callback) throws IOException {
        InputStream is = context.getContentResolver().openInputStream(uri);
        if (is == null) throw new IOException("Cannot open image");

        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inSampleSize = 2;
        Bitmap bmp = BitmapFactory.decodeStream(is, null, o);
        is.close();
        if (bmp == null) throw new IOException("Cannot decode image");

        // Rotate for landscape
        if (options.getOrientation() == PrintOptions.Orientation.LANDSCAPE) {
            Matrix m = new Matrix();
            m.postRotate(90);
            bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), m, true);
        }

        // Grayscale
        if (options.getColorMode() == PrintOptions.ColorMode.BLACK_AND_WHITE) {
            bmp = toGrayscale(bmp);
        }

        // Scale to fit page
        int[] mm = options.getPaperDimensionsMm();
        int pw = mmToDots(mm[0]);
        int ph = mmToDots(mm[1]);
        bmp = scaleFit(bmp, pw, ph);

        ByteArrayOutputStream job = new ByteArrayOutputStream();
        writePclHeader(job, options);
        writePclRasterPage(job, bmp);
        writePclFooter(job);
        bmp.recycle();

        callback.onJobReady(job.toByteArray(), 1);
    }

    // ── Text ─────────────────────────────────────────────────────────────────

    private void buildTextJob(Uri uri, PrintOptions options, JobCallback callback) throws IOException {
        InputStream is = context.getContentResolver().openInputStream(uri);
        if (is == null) throw new IOException("Cannot open text file");
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int n;
        while ((n = is.read(chunk)) != -1) buf.write(chunk, 0, n);
        is.close();
        String text = buf.toString("UTF-8");

        ByteArrayOutputStream job = new ByteArrayOutputStream();
        job.write("\u001BE".getBytes());
        job.write(("\u001B&l" + paperCode(options.getPaperSize()) + "A").getBytes());
        job.write(("\u001B&l" + (options.getOrientation() == PrintOptions.Orientation.LANDSCAPE ? 1 : 0) + "O").getBytes());
        job.write(("\u001B&l" + options.getCopies() + "X").getBytes());
        job.write("\u001B(s0p10h0s0b3T".getBytes()); // Courier 10pt
        job.write("\u001B&a5L".getBytes()); // left margin
        job.write(text.getBytes("UTF-8"));
        job.write("\u001BE".getBytes());
        callback.onJobReady(job.toByteArray(), 1);
    }

    // ── PCL5 Helpers ─────────────────────────────────────────────────────────

    private void writePclHeader(ByteArrayOutputStream out, PrintOptions opt) throws IOException {
        out.write("\u001BE".getBytes());
        out.write(("\u001B&l" + paperCode(opt.getPaperSize()) + "A").getBytes());
        out.write(("\u001B&l" + (opt.getOrientation() == PrintOptions.Orientation.LANDSCAPE ? 1 : 0) + "O").getBytes());
        out.write(("\u001B&l" + opt.getCopies() + "X").getBytes());
        out.write(("\u001B*t" + DPI + "R").getBytes());
        out.write("\u001B*p0X\u001B*p0Y".getBytes());
    }

    private void writePclFooter(ByteArrayOutputStream out) throws IOException {
        out.write("\u001BE".getBytes());
    }

    /**
     * Core function: converts Bitmap to PCL5 1-bit raster.
     * This is the correct format for HP LaserJet P3015.
     * Each pixel row is converted to bits: 1=ink, 0=no ink.
     */
    private void writePclRasterPage(ByteArrayOutputStream out, Bitmap bmp) throws IOException {
        int width  = bmp.getWidth();
        int height = bmp.getHeight();
        int bytesPerRow = (width + 7) / 8;

        out.write(("\u001B*r" + width  + "S").getBytes());
        out.write(("\u001B*r" + height + "T").getBytes());
        out.write("\u001B*r1A".getBytes());  // start raster
        out.write("\u001B*b0M".getBytes());  // uncompressed

        int[] pixels = new int[width];
        for (int y = 0; y < height; y++) {
            bmp.getPixels(pixels, 0, width, 0, y, width, 1);
            byte[] row = new byte[bytesPerRow];
            for (int x = 0; x < width; x++) {
                int px  = pixels[x];
                int r   = (px >> 16) & 0xFF;
                int g   = (px >>  8) & 0xFF;
                int b   =  px        & 0xFF;
                int lum = (r * 299 + g * 587 + b * 114) / 1000;
                if (lum < 200) {
                    row[x / 8] |= (byte)(0x80 >>> (x % 8));
                }
            }
            out.write(("\u001B*b" + bytesPerRow + "W").getBytes());
            out.write(row);
        }
        out.write("\u001B*rB".getBytes()); // end raster
    }

    // ── Bitmap Utilities ─────────────────────────────────────────────────────

    private Bitmap toGrayscale(Bitmap src) {
        Bitmap out = Bitmap.createBitmap(src.getWidth(), src.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        Paint p = new Paint();
        android.graphics.ColorMatrix cm = new android.graphics.ColorMatrix();
        cm.setSaturation(0);
        p.setColorFilter(new android.graphics.ColorMatrixColorFilter(cm));
        c.drawBitmap(src, 0, 0, p);
        return out;
    }

    private Bitmap scaleFit(Bitmap src, int pw, int ph) {
        float scale = Math.min((float) pw / src.getWidth(), (float) ph / src.getHeight());
        int nw = (int)(src.getWidth() * scale);
        int nh = (int)(src.getHeight() * scale);
        Bitmap page = Bitmap.createBitmap(pw, ph, Bitmap.Config.ARGB_8888);
        page.eraseColor(Color.WHITE);
        Canvas c = new Canvas(page);
        c.drawBitmap(Bitmap.createScaledBitmap(src, nw, nh, true), (pw - nw) / 2f, (ph - nh) / 2f, null);
        return page;
    }

    private int mmToDots(int mm) { return (int)(mm * DPI / 25.4f); }

    private int paperCode(PrintOptions.PaperSize s) {
        switch (s) {
            case LETTER: return 2;
            case LEGAL:  return 3;
            case A3:     return 27;
            case A5:     return 25;
            default:     return 26; // A4
        }
    }

    private String guessMime(String uri) {
        String u = uri.toLowerCase();
        if (u.endsWith(".pdf"))  return "application/pdf";
        if (u.endsWith(".jpg") || u.endsWith(".jpeg")) return "image/jpeg";
        if (u.endsWith(".png"))  return "image/png";
        if (u.endsWith(".txt"))  return "text/plain";
        return null;
    }
}
