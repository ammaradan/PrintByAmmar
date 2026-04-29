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
 * Converts documents and images to printable byte arrays.
 *
 * For images: encodes as JPEG wrapped in minimal PCL.
 * For PDFs: renders each page to bitmap using Android's PdfRenderer,
 *           then encodes and sends page by page.
 *
 * PCL (Printer Command Language) is natively supported by HP LaserJet P3015.
 */
public class PrintJobBuilder {

    private static final String TAG = "PrintJobBuilder";

    // PCL escape sequences for HP LaserJet
    private static final byte ESC = 0x1B;

    private Context context;

    public interface JobCallback {
        void onJobReady(byte[] data, int pageCount);
        void onPageRendered(int page, int total);
        void onError(String message);
    }

    public PrintJobBuilder(Context context) {
        this.context = context;
    }

    /**
     * Builds a print job from a file URI.
     * Detects file type and routes to the correct handler.
     */
    public void buildJob(Uri fileUri, PrintOptions options, JobCallback callback) {
        new Thread(() -> {
            try {
                String mimeType = context.getContentResolver().getType(fileUri);
                if (mimeType == null) mimeType = guessMimeType(fileUri.toString());

                Log.d(TAG, "Building job for MIME: " + mimeType);

                if (mimeType != null && mimeType.startsWith("image/")) {
                    buildImageJob(fileUri, options, callback);
                } else if ("application/pdf".equals(mimeType)) {
                    buildPdfJob(fileUri, options, callback);
                } else if (mimeType != null && mimeType.startsWith("text/")) {
                    buildTextJob(fileUri, options, callback);
                } else {
                    // Try as image fallback
                    buildImageJob(fileUri, options, callback);
                }

            } catch (Exception e) {
                Log.e(TAG, "Job build error: " + e.getMessage(), e);
                callback.onError("Failed to process file: " + e.getMessage());
            }
        }).start();
    }

    /**
     * Converts an image file to PCL-wrapped JPEG bytes.
     */
    private void buildImageJob(Uri uri, PrintOptions options, JobCallback callback)
            throws IOException {

        InputStream is = context.getContentResolver().openInputStream(uri);
        if (is == null) throw new IOException("Cannot open file");

        Bitmap bitmap = BitmapFactory.decodeStream(is);
        is.close();

        if (bitmap == null) throw new IOException("Cannot decode image");

        // Apply orientation
        if (options.getOrientation() == PrintOptions.Orientation.LANDSCAPE) {
            Matrix matrix = new Matrix();
            matrix.postRotate(90);
            bitmap = Bitmap.createBitmap(bitmap, 0, 0,
                    bitmap.getWidth(), bitmap.getHeight(), matrix, true);
        }

        // Apply B&W if needed
        if (options.getColorMode() == PrintOptions.ColorMode.BLACK_AND_WHITE) {
            bitmap = toGrayscale(bitmap);
        }

        // Get target dimensions at 203 DPI for the paper size
        int[] dimMm = options.getPaperDimensionsMm();
        int targetW = mmToPixels(dimMm[0], 203);
        int targetH = mmToPixels(dimMm[1], 203);

        // Scale bitmap to fit page
        if (options.isFitToPage()) {
            bitmap = scaleFitPage(bitmap, targetW, targetH);
        }

        ByteArrayOutputStream jobStream = new ByteArrayOutputStream();

        // PCL header for HP LaserJet
        byte[] pclHeader = buildPclHeader(options, false);
        jobStream.write(pclHeader);

        // Write image as JPEG
        ByteArrayOutputStream imgStream = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, imgStream);
        byte[] imgBytes = imgStream.toByteArray();

        // PCL raster image command
        byte[] rasterCmd = buildPclRasterImage(bitmap.getWidth(), bitmap.getHeight(), imgBytes);
        jobStream.write(rasterCmd);

        // PCL end-of-job
        jobStream.write(buildPclFooter());

        callback.onJobReady(jobStream.toByteArray(), 1);
    }

    /**
     * Renders PDF pages using Android's PdfRenderer and sends them page by page.
     */
    private void buildPdfJob(Uri uri, PrintOptions options, JobCallback callback)
            throws IOException {

        ParcelFileDescriptor pfd = context.getContentResolver()
                .openFileDescriptor(uri, "r");
        if (pfd == null) throw new IOException("Cannot open PDF");

        PdfRenderer renderer = new PdfRenderer(pfd);
        int pageCount = renderer.getPageCount();
        Log.d(TAG, "PDF has " + pageCount + " pages");

        ByteArrayOutputStream jobStream = new ByteArrayOutputStream();

        // PCL job header
        jobStream.write(buildPclHeader(options, true));

        int[] dimMm = options.getPaperDimensionsMm();
        int targetW, targetH;
        if (options.getOrientation() == PrintOptions.Orientation.LANDSCAPE) {
            targetW = mmToPixels(dimMm[1], 203);
            targetH = mmToPixels(dimMm[0], 203);
        } else {
            targetW = mmToPixels(dimMm[0], 203);
            targetH = mmToPixels(dimMm[1], 203);
        }

        for (int i = 0; i < pageCount; i++) {
            PdfRenderer.Page page = renderer.openPage(i);

            Bitmap bitmap = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888);
            bitmap.eraseColor(Color.WHITE);

            // Render PDF page to bitmap
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT);
            page.close();

            if (options.getColorMode() == PrintOptions.ColorMode.BLACK_AND_WHITE) {
                bitmap = toGrayscale(bitmap);
            }

            // Encode page as JPEG
            ByteArrayOutputStream imgStream = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, imgStream);

            // Add page form feed for multi-page
            if (i > 0) {
                jobStream.write(new byte[]{ESC, (byte)'&', (byte)'l', (byte)'0', (byte)'H'}); // PCL form feed
            }

            byte[] rasterCmd = buildPclRasterImage(targetW, targetH, imgStream.toByteArray());
            jobStream.write(rasterCmd);

            bitmap.recycle();
            callback.onPageRendered(i + 1, pageCount);
        }

        jobStream.write(buildPclFooter());
        renderer.close();
        pfd.close();

        callback.onJobReady(jobStream.toByteArray(), pageCount);
    }

    /**
     * Converts plain text to a printable format.
     */
    private void buildTextJob(Uri uri, PrintOptions options, JobCallback callback)
            throws IOException {

        InputStream is = context.getContentResolver().openInputStream(uri);
        if (is == null) throw new IOException("Cannot open file");

        // Read text content
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int read;
        while ((read = is.read(chunk)) != -1) {
            buffer.write(chunk, 0, read);
        }
        is.close();
        String text = buffer.toString("UTF-8");

        // Render text to bitmap using Canvas
        Bitmap bitmap = renderTextToBitmap(text, options);

        if (options.getColorMode() == PrintOptions.ColorMode.BLACK_AND_WHITE) {
            bitmap = toGrayscale(bitmap);
        }

        ByteArrayOutputStream jobStream = new ByteArrayOutputStream();
        jobStream.write(buildPclHeader(options, false));

        ByteArrayOutputStream imgStream = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, imgStream);
        jobStream.write(buildPclRasterImage(bitmap.getWidth(), bitmap.getHeight(), imgStream.toByteArray()));
        jobStream.write(buildPclFooter());

        callback.onJobReady(jobStream.toByteArray(), 1);
    }

    // ─── PCL Helpers ─────────────────────────────────────────────────────────

    private byte[] buildPclHeader(PrintOptions options, boolean isPdf) {
        StringBuilder pcl = new StringBuilder();
        // PCL reset
        pcl.append("\u001BE");
        // Paper size: A4=26, Letter=2, Legal=3
        int paperCode = getPclPaperCode(options.getPaperSize());
        pcl.append(String.format("\u001B&l%dA", paperCode));
        // Orientation: 0=portrait, 1=landscape
        int orientCode = options.getOrientation() == PrintOptions.Orientation.LANDSCAPE ? 1 : 0;
        pcl.append(String.format("\u001B&l%dO", orientCode));
        // Copies
        pcl.append(String.format("\u001B&l%dX", options.getCopies()));
        // Duplex
        if (options.isDuplex()) {
            pcl.append("\u001B&l1S"); // duplex long edge
        }
        return pcl.toString().getBytes();
    }

    private byte[] buildPclFooter() {
        return "\u001BE".getBytes(); // PCL reset / end job
    }

    private byte[] buildPclRasterImage(int width, int height, byte[] jpegData) {
        // For HP LaserJets, we use PCL XL or just send the raw JPEG
        // Most modern HP LaserJets accept JPEG directly via JetDirect
        // This is simpler and more compatible than PCL raster commands
        return jpegData;
    }

    private int getPclPaperCode(PrintOptions.PaperSize size) {
        switch (size) {
            case LETTER: return 2;
            case LEGAL:  return 3;
            case A4:     return 26;
            case A3:     return 27;
            case A5:     return 25;
            default:     return 26;
        }
    }

    // ─── Bitmap Helpers ───────────────────────────────────────────────────────

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

    private Bitmap scaleFitPage(Bitmap src, int targetW, int targetH) {
        float scale = Math.min((float) targetW / src.getWidth(), (float) targetH / src.getHeight());
        int newW = (int) (src.getWidth() * scale);
        int newH = (int) (src.getHeight() * scale);

        Bitmap page = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888);
        page.eraseColor(Color.WHITE);

        Canvas canvas = new Canvas(page);
        Bitmap scaled = Bitmap.createScaledBitmap(src, newW, newH, true);
        canvas.drawBitmap(scaled, (targetW - newW) / 2f, (targetH - newH) / 2f, null);
        return page;
    }

    private Bitmap renderTextToBitmap(String text, PrintOptions options) {
        int[] dimMm = options.getPaperDimensionsMm();
        int w = mmToPixels(dimMm[0], 150);
        int h = mmToPixels(dimMm[1], 150);

        Bitmap bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(Color.WHITE);

        Canvas canvas = new Canvas(bitmap);
        Paint paint = new Paint();
        paint.setColor(Color.BLACK);
        paint.setTextSize(30f);
        paint.setAntiAlias(true);

        // Simple text wrapping
        int margin = 60;
        int maxWidth = w - (2 * margin);
        float y = margin + 30f;
        float lineHeight = 40f;

        String[] paragraphs = text.split("\n");
        for (String para : paragraphs) {
            // Word wrap
            String[] words = para.split(" ");
            StringBuilder line = new StringBuilder();
            for (String word : words) {
                String testLine = line.length() == 0 ? word : line + " " + word;
                if (paint.measureText(testLine) > maxWidth) {
                    canvas.drawText(line.toString(), margin, y, paint);
                    y += lineHeight;
                    line = new StringBuilder(word);
                    if (y > h - margin) break;
                } else {
                    line = new StringBuilder(testLine);
                }
            }
            if (line.length() > 0 && y <= h - margin) {
                canvas.drawText(line.toString(), margin, y, paint);
                y += lineHeight;
            }
            y += lineHeight * 0.3f;
        }

        return bitmap;
    }

    private int mmToPixels(int mm, int dpi) {
        return (int) (mm * dpi / 25.4f);
    }

    private String guessMimeType(String uriStr) {
        String lower = uriStr.toLowerCase();
        if (lower.endsWith(".pdf")) return "application/pdf";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".txt")) return "text/plain";
        return null;
    }
}
