package com.ammar.printbyammar;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.pdf.PdfDocument;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Converts documents to PCL5 print jobs for HP LaserJet P3015.
 *
 * KEY FIX: Images are converted to 1-bit black/white raster PCL5.
 * PDFs are rendered page by page to bitmaps then converted to PCL5.
 * Raw JPEG/binary data is NEVER sent directly to the printer.
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
                String mimeType = context.getContentResolver().getType(fileUri);
                if (mimeType == null) mimeType = guessMimeType(fileUri.toString());
                Log.d(TAG, "MIME: " + mimeType);

                if ("application/pdf".equals(mimeType)) {
                    buildPdfJob(fileUri, options, callback);
                } else if (mimeType != null && mimeType.startsWith("image/")) {
                    buildImageJob(fileUri, options, callback);
                } else if (mimeType != null && mimeType.startsWith("text/")) {
                    buildTextJob(fileUri, options, callback);
                } else {
                    // Try as image
                    buildImageJob(fileUri, options, callback);
                }
            } catch (Exception e) {
                Log.e(TAG, "Error: " + e.getMessage(), e);
                callback.onError("Failed to process file: " + e.getMessage());
            }
        }).start();
    }

    // ── PDF Job ──────────────────────────────────────────────────────────────

    private void buildPdfJob(Uri uri, PrintOptions options, JobCallback callback)
            throws IOException {
        ParcelFileDescriptor pfd = context.getContentResolver().openFileDescriptor(uri, "r");
        if (pfd == null) throw new IOException("Cannot open PDF");

        PdfRenderer renderer = new PdfRenderer(pfd);
        int pageCount = renderer.getPageCount();
        Log.d(TAG, "PDF pages: " + pageCount);

        ByteArrayOutputStream jobStream = new ByteArrayOutputStream();
        writePclHeader(jobStream, options);

        for (int i = 0; i < pageCount; i++) {
            PdfRenderer.Page page = renderer.openPage(i);

            // Get page dimensions at 300 DPI
            float pageWidthPt  = page.getWidth();   // in points (1/72 inch)
            float pageHeightPt = page.getHeight();
            int bmpW = (int)(pageWidthPt  * DPI / 72f);
            int bmpH = (int)(pageHeightPt * DPI / 72f);

            // Cap to A4 max to avoid memory issues
            if (bmpW > 2480) bmpW = 2480;
            if (bmpH > 3508) bmpH = 3508;

            Bitmap bmp = Bitmap.createBitmap(bmpW, bmpH, Bitmap.Config.ARGB_8888);
            bmp.eraseColor(Color.WHITE);
            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT);
            page.close();

            if (options.getColorMode() == PrintOptions.ColorMode.BLACK_AND_WHITE) {
                bmp = toGrayscale(bmp);
            }

            if (i > 0) {
                jobStream.write(0x0C); // form feed = new page
            }

            writeRasterPage(jobStream, bmp);
            bmp.recycle();
            callback.onPageRendered(i + 1, pageCount);
        }

        writePclFooter(jobStream);
        renderer.close();
        pfd.close();
        callback.onJobReady(jobStream.toByteArray(), pageCount);
    }

    // ── Image Job ────────────────────────────────────────────────────────────

    private void buildImageJob(Uri uri, PrintOptions options, JobCallback callback)
            throws IOException {
        InputStream is = context.getContentResolver().openInputStream(uri);
        if (is == null) throw new IOException("Cannot open file");

        // Decode with subsampling to avoid OOM on large images
        BitmapFactory.Options bmpOpts = new BitmapFactory.Options();
        bmpOpts.inSampleSize = 2; // downsample 2x to save memory
        Bitmap bmp = BitmapFactory.decodeStream(is, null, bmpOpts);
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

        // Scale to fit page at 300 DPI
        int[] dimMm = options.getPaperDimensionsMm();
        int pageW = mmToDots(dimMm[0]);
        int pageH = mmToDots(dimMm[1]);
        if (options.isFitToPage()) {
            bmp = scaleFitPage(bmp, pageW, pageH);
        }

        ByteArrayOutputStream jobStream = new ByteArrayOutputStream();
        writePclHeader(jobStream, options);
        writeRasterPage(jobStream, bmp);
        writePclFooter(jobStream);
        bmp.recycle();

        callback.onJobReady(jobStream.toByteArray(), 1);
    }

    // ── Text Job ─────────────────────────────────────────────────────────────

    private void buildTextJob(Uri uri, PrintOptions options, JobCallback callback)
            throws IOException {
        InputStream is = context.getContentResolver().openInputStream(uri);
        if (is == null) throw new IOException("Cannot open file");
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int n;
        while ((n = is.read(chunk)) != -1) buf.write(chunk, 0, n);
        is.close();
        String text = buf.toString("UTF-8");

        ByteArrayOutputStream jobStream = new ByteArrayOutputStream();
        // PCL reset
        jobStream.write("\u001BE".getBytes());
        // Paper
        jobStream.write(("\u001B&l" + getPaperCode(options.getPaperSize()) + "A").getBytes());
        // Orientation
        int orient = options.getOrientation() == PrintOptions.Orientation.LANDSCAPE ? 1 : 0;
        jobStream.write(("\u001B&l" + orient + "O").getBytes());
        // Copies
        jobStream.write(("\u001B&l" + options.getCopies() + "X").getBytes());
        // Font: Courier 12pt
        jobStream.write("\u001B(s0p12h0s0b3T".getBytes());
        // Left margin
        jobStream.write("\u001B&a5L".getBytes());
        // Text
        jobStream.write(text.replace("\r\n", "\n").getBytes("UTF-8"));
        // End
        jobStream.write("\u001BE".getBytes());

        callback.onJobReady(jobStream.toByteArray(), 1);
    }

    // ── PCL5 Raster Helpers ──────────────────────────────────────────────────

    private void writePclHeader(ByteArrayOutputStream out, PrintOptions options)
            throws IOException {
        // Reset
        out.write("\u001BE".getBytes());
        // Paper size
        out.write(("\u001B&l" + getPaperCode(options.getPaperSize()) + "A").getBytes());
        // Orientation: 0=portrait 1=landscape
        int orient = options.getOrientation() == PrintOptions.Orientation.LANDSCAPE ? 1 : 0;
        out.write(("\u001B&l" + orient + "O").getBytes());
        // Copies
        out.write(("\u001B&l" + options.getCopies() + "X").getBytes());
        // Resolution 300 DPI
        out.write(("\u001B*t" + DPI + "R").getBytes());
        // Move cursor to top-left
        out.write("\u001B*p0X".getBytes());
        out.write("\u001B*p0Y".getBytes());
    }

    private void writePclFooter(ByteArrayOutputStream out) throws IOException {
        out.write("\u001BE".getBytes()); // Reset/eject
    }

    /**
     * Writes a single page as PCL5 raster data.
     * Converts RGB bitmap to 1-bit (black/white) raster — correct format for laser printers.
     */
    private void writeRasterPage(ByteArrayOutputStream out, Bitmap bmp) throws IOException {
        int width  = bmp.getWidth();
        int height = bmp.getHeight();
        int bytesPerRow = (width + 7) / 8;

        // Configure raster
        out.write(("\u001B*r" + width  + "S").getBytes()); // raster width
        out.write(("\u001B*r" + height + "T").getBytes()); // raster height
        out.write("\u001B*r1A".getBytes());                 // start raster (left margin)
        out.write("\u001B*b0M".getBytes());                 // uncompressed mode

        int[] rowPixels = new int[width];

        for (int y = 0; y < height; y++) {
            bmp.getPixels(rowPixels, 0, width, 0, y, width, 1);
            byte[] rowData = new byte[bytesPerRow];

            for (int x = 0; x < width; x++) {
                int px = rowPixels[x];
                int r  = (px >> 16) & 0xFF;
                int g  = (px >>  8) & 0xFF;
                int b  =  px        & 0xFF;
                // Luminance: dark pixel → set bit (print ink)
                int lum = (r * 299 + g * 587 + b * 114) / 1000;
                if (lum < 200) { // threshold: anything not clearly white = ink
                    rowData[x / 8] |= (byte)(0x80 >>> (x % 8));
                }
            }

            // PCL transfer raster row
            out.write(("\u001B*b" + bytesPerRow + "W").getBytes());
            out.write(rowData);
        }

        // End raster
        out.write("\u001B*rB".getBytes());
    }

    // ── Bitmap Utilities ─────────────────────────────────────────────────────

    private Bitmap toGrayscale(Bitmap src) {
        Bitmap result = Bitmap.createBitmap(src.getWidth(), src.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(result);
        Paint paint = new Paint();
        android.graphics.ColorMatrix cm = new android.graphics.ColorMatrix();
        cm.setSaturation(0);
        paint.setColorFilter(new android.graphics.ColorMatrixColorFilter(cm));
        canvas.drawBitmap(src, 0, 0, paint);
        return result;
    }

    private Bitmap scaleFitPage(Bitmap src, int pageW, int pageH) {
        float scale = Math.min((float) pageW / src.getWidth(),
                               (float) pageH / src.getHeight());
        int newW = (int)(src.getWidth()  * scale);
        int newH = (int)(src.getHeight() * scale);
        Bitmap page = Bitmap.createBitmap(pageW, pageH, Bitmap.Config.ARGB_8888);
        page.eraseColor(Color.WHITE);
        Canvas canvas = new Canvas(page);
        Bitmap scaled = Bitmap.createScaledBitmap(src, newW, newH, true);
        canvas.drawBitmap(scaled, (pageW - newW) / 2f, (pageH - newH) / 2f, null);
        return page;
    }

    private int mmToDots(int mm) {
        return (int)(mm * DPI / 25.4f);
    }

    private int getPaperCode(PrintOptions.PaperSize size) {
        switch (size) {
            case LETTER: return 2;
            case LEGAL:  return 3;
            case A3:     return 27;
            case A5:     return 25;
            case A4:
            default:     return 26;
        }
    }

    private String guessMimeType(String uri) {
        String u = uri.toLowerCase();
        if (u.endsWith(".pdf"))  return "application/pdf";
        if (u.endsWith(".jpg") || u.endsWith(".jpeg")) return "image/jpeg";
        if (u.endsWith(".png"))  return "image/png";
        if (u.endsWith(".txt"))  return "text/plain";
        return null;
    }
}
