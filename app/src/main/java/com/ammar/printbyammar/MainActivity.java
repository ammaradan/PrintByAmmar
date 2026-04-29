package com.ammar.printbyammar;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.database.Cursor;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.util.List;

/**
 * Main Activity — Print By Ammar
 *
 * Full native Android print app supporting:
 *   • USB OTG printing (HP LaserJet P3015 and more)
 *   • Wi-Fi / Network printing via port 9100
 *   • PDF, image, and text file printing
 */
public class MainActivity extends AppCompatActivity {

    private static final int REQ_PICK_FILE        = 100;
    private static final int REQ_PRINTER_DISCOVER  = 101;
    private static final int REQ_STORAGE_PERMISSION = 102;

    // UI
    private LinearLayout uploadZone;
    private LinearLayout fileCard;
    private TextView tvFileName, tvFileSize, tvFileIcon;
    private Button btnRemoveFile, btnSelectPrinter, btnPrint;
    private Button btnDecCopies, btnIncCopies;
    private TextView tvCopies, tvSelectedPrinter, tvPrinterStatus;
    private Spinner spinnerConnection, spinnerPaperSize, spinnerOrientation;
    private Switch switchColor;
    private ProgressBar progressBar;

    // State
    private Uri selectedFileUri;
    private PrinterInfo selectedPrinter;
    private PrintOptions printOptions = new PrintOptions();
    private int copies = 1;

    // Managers
    private UsbPrinterManager usbPrinterManager;
    private WifiPrinterManager wifiPrinterManager;
    private PrintJobBuilder printJobBuilder;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        initViews();
        initManagers();
        setupSpinners();
        setupListeners();
        checkAndRequestPermissions();

        // Handle shared file (from other apps via Share → Print)
        handleIncomingIntent(getIntent());
    }

    private void initViews() {
        uploadZone      = findViewById(R.id.uploadZone);
        fileCard        = findViewById(R.id.fileCard);
        tvFileName      = findViewById(R.id.tvFileName);
        tvFileSize      = findViewById(R.id.tvFileSize);
        tvFileIcon      = findViewById(R.id.tvFileIcon);
        btnRemoveFile   = findViewById(R.id.btnRemoveFile);
        btnSelectPrinter= findViewById(R.id.btnSelectPrinter);
        btnPrint        = findViewById(R.id.btnPrint);
        btnDecCopies    = findViewById(R.id.btnDecCopies);
        btnIncCopies    = findViewById(R.id.btnIncCopies);
        tvCopies        = findViewById(R.id.tvCopies);
        tvSelectedPrinter = findViewById(R.id.tvSelectedPrinter);
        tvPrinterStatus = findViewById(R.id.tvPrinterStatus);
        spinnerConnection = findViewById(R.id.spinnerConnection);
        spinnerPaperSize  = findViewById(R.id.spinnerPaperSize);
        spinnerOrientation= findViewById(R.id.spinnerOrientation);
        switchColor     = findViewById(R.id.switchColor);
        progressBar     = findViewById(R.id.progressBar);
    }

    private void initManagers() {
        usbPrinterManager = new UsbPrinterManager(this);
        wifiPrinterManager = new WifiPrinterManager(this);
        printJobBuilder = new PrintJobBuilder(this);
    }

    private void setupSpinners() {
        // Connection type
        ArrayAdapter<String> connAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item,
                new String[]{"USB", "Wi-Fi / Network"});
        connAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerConnection.setAdapter(connAdapter);

        // Paper size
        ArrayAdapter<String> paperAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item,
                new String[]{"A4", "Letter", "Legal", "A3", "A5"});
        paperAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerPaperSize.setAdapter(paperAdapter);

        // Orientation
        ArrayAdapter<String> orientAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item,
                new String[]{"Portrait", "Landscape"});
        orientAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerOrientation.setAdapter(orientAdapter);
    }

    private void setupListeners() {
        uploadZone.setOnClickListener(v -> pickFile());

        btnRemoveFile.setOnClickListener(v -> clearFile());

        btnDecCopies.setOnClickListener(v -> {
            if (copies > 1) {
                copies--;
                tvCopies.setText(String.valueOf(copies));
                printOptions.setCopies(copies);
            }
        });

        btnIncCopies.setOnClickListener(v -> {
            if (copies < 99) {
                copies++;
                tvCopies.setText(String.valueOf(copies));
                printOptions.setCopies(copies);
            }
        });

        btnSelectPrinter.setOnClickListener(v -> openPrinterDiscovery());

        btnPrint.setOnClickListener(v -> startPrint());

        spinnerPaperSize.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                PrintOptions.PaperSize[] sizes = PrintOptions.PaperSize.values();
                if (pos < sizes.length) printOptions.setPaperSize(sizes[pos]);
            }
            @Override public void onNothingSelected(AdapterView<?> p) {}
        });

        spinnerOrientation.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                printOptions.setOrientation(pos == 0
                        ? PrintOptions.Orientation.PORTRAIT
                        : PrintOptions.Orientation.LANDSCAPE);
            }
            @Override public void onNothingSelected(AdapterView<?> p) {}
        });

        switchColor.setOnCheckedChangeListener((btn, isChecked) -> {
            printOptions.setColorMode(isChecked
                    ? PrintOptions.ColorMode.COLOR
                    : PrintOptions.ColorMode.BLACK_AND_WHITE);
            switchColor.setText(isChecked ? "Color" : "B & W");
        });
    }

    // ─── File Picking ──────────────────────────────────────────────────────────

    private void pickFile() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES,
                new String[]{"application/pdf", "image/*", "text/plain"});
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(Intent.createChooser(intent, "Select File to Print"), REQ_PICK_FILE);
    }

    private void handleIncomingIntent(Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (Intent.ACTION_SEND.equals(action)) {
            Uri uri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (uri != null) setSelectedFile(uri);
        }
    }

    private void setSelectedFile(Uri uri) {
        selectedFileUri = uri;

        String[] info = getFileInfo(uri);
        String name = info[0];
        String size = info[1];
        String ext  = info[2];

        tvFileName.setText(name);
        tvFileSize.setText(size + " · " + ext.toUpperCase());
        tvFileIcon.setText(getFileEmoji(ext));

        uploadZone.setVisibility(View.GONE);
        fileCard.setVisibility(View.VISIBLE);

        updatePrintButtonState();
    }

    private void clearFile() {
        selectedFileUri = null;
        uploadZone.setVisibility(View.VISIBLE);
        fileCard.setVisibility(View.GONE);
        updatePrintButtonState();
    }

    private String[] getFileInfo(Uri uri) {
        String name = "Unknown file";
        String size = "? KB";
        String ext  = "file";

        Cursor cursor = getContentResolver().query(uri, null, null, null, null);
        if (cursor != null && cursor.moveToFirst()) {
            int nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
            int sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE);
            if (nameIdx >= 0) name = cursor.getString(nameIdx);
            if (sizeIdx >= 0) {
                long bytes = cursor.getLong(sizeIdx);
                if (bytes < 1024) size = bytes + " B";
                else if (bytes < 1024 * 1024) size = String.format("%.1f KB", bytes / 1024f);
                else size = String.format("%.1f MB", bytes / (1024f * 1024f));
            }
            cursor.close();
        }

        if (name.contains(".")) {
            ext = name.substring(name.lastIndexOf('.') + 1).toLowerCase();
        }

        return new String[]{name, size, ext};
    }

    private String getFileEmoji(String ext) {
        switch (ext) {
            case "pdf":  return "📕";
            case "jpg":
            case "jpeg":
            case "png":
            case "webp": return "🖼";
            case "txt":  return "📄";
            default:     return "📁";
        }
    }

    // ─── Printer Discovery ────────────────────────────────────────────────────

    private void openPrinterDiscovery() {
        Intent intent = new Intent(this, PrinterDiscoveryActivity.class);
        startActivityForResult(intent, REQ_PRINTER_DISCOVER);
    }

    private void setPrinter(PrinterInfo printer) {
        selectedPrinter = printer;
        tvSelectedPrinter.setText(printer.getName());
        tvPrinterStatus.setText("⬤ " + printer.getName());
        updatePrintButtonState();
    }

    // ─── Printing ─────────────────────────────────────────────────────────────

    private void startPrint() {
        if (selectedFileUri == null) {
            toast("Please select a file first");
            return;
        }
        if (selectedPrinter == null) {
            toast("Please select a printer first");
            return;
        }

        setLoading(true);

        printJobBuilder.buildJob(selectedFileUri, printOptions,
                new PrintJobBuilder.JobCallback() {

            @Override
            public void onJobReady(byte[] data, int pageCount) {
                switch (selectedPrinter.getConnectionType()) {
                    case USB:
                        printViaUsb(data);
                        break;
                    case WIFI:
                        printViaWifi(data);
                        break;
                    default:
                        runOnUiThread(() -> {
                            setLoading(false);
                            toast("Connection type not supported");
                        });
                }
            }

            @Override
            public void onPageRendered(int page, int total) {
                runOnUiThread(() ->
                        toast("Rendering page " + page + " of " + total + "..."));
            }

            @Override
            public void onError(String message) {
                runOnUiThread(() -> {
                    setLoading(false);
                    showError("Failed to process file", message);
                });
            }
        });
    }

    private void printViaUsb(byte[] data) {
        List<UsbDevice> printers = usbPrinterManager.getConnectedPrinters();
        if (printers.isEmpty()) {
            runOnUiThread(() -> {
                setLoading(false);
                showError("USB Error",
                        "No USB printer detected.\n\nMake sure:\n" +
                        "• USB OTG cable is connected\n" +
                        "• Printer is powered on\n" +
                        "• Cable supports data transfer");
            });
            return;
        }

        UsbDevice device = printers.get(0);
        usbPrinterManager.requestPermission(device, new UsbPrinterManager.UsbPermissionCallback() {
            @Override
            public void onPermissionGranted(UsbDevice dev) {
                new Thread(() -> {
                    boolean opened = usbPrinterManager.openConnection(dev);
                    if (!opened) {
                        runOnUiThread(() -> {
                            setLoading(false);
                            showError("USB Error",
                                    "Failed to open printer connection.\n" +
                                    "Try disconnecting and reconnecting the cable.");
                        });
                        return;
                    }

                    boolean success = usbPrinterManager.sendRawData(data);
                    usbPrinterManager.closeConnection();

                    runOnUiThread(() -> {
                        setLoading(false);
                        if (success) {
                            showSuccess("Print job sent! Check your printer.");
                        } else {
                            showError("Print Failed",
                                    "Data transfer failed. Check USB connection.");
                        }
                    });
                }).start();
            }

            @Override
            public void onPermissionDenied() {
                runOnUiThread(() -> {
                    setLoading(false);
                    showError("Permission Denied",
                            "USB permission denied.\nPlease allow access when prompted.");
                });
            }
        });
    }

    private void printViaWifi(byte[] data) {
        wifiPrinterManager.printRaw(selectedPrinter, data, new WifiPrinterManager.PrintCallback() {
            @Override
            public void onSuccess() {
                runOnUiThread(() -> {
                    setLoading(false);
                    showSuccess("Print job sent to " + selectedPrinter.getName());
                });
            }

            @Override
            public void onProgress(int percent) {
                // Could update a progress bar here
            }

            @Override
            public void onError(String message) {
                runOnUiThread(() -> {
                    setLoading(false);
                    showError("Network Print Failed", message);
                });
            }
        });
    }

    // ─── UI Helpers ───────────────────────────────────────────────────────────

    private void updatePrintButtonState() {
        btnPrint.setEnabled(selectedFileUri != null && selectedPrinter != null);
    }

    private void setLoading(boolean loading) {
        progressBar.setVisibility(loading ? View.VISIBLE : View.GONE);
        btnPrint.setEnabled(!loading && selectedFileUri != null && selectedPrinter != null);
        btnPrint.setText(loading ? "Printing..." : "🖨  Print Now");
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    private void showSuccess(String message) {
        new AlertDialog.Builder(this)
                .setTitle("✅ Success")
                .setMessage(message)
                .setPositiveButton("OK", null)
                .show();
    }

    private void showError(String title, String message) {
        new AlertDialog.Builder(this)
                .setTitle("❌ " + title)
                .setMessage(message)
                .setPositiveButton("OK", null)
                .show();
    }

    // ─── Permissions ──────────────────────────────────────────────────────────

    private void checkAndRequestPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this,
                    Manifest.permission.READ_MEDIA_IMAGES) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.READ_MEDIA_IMAGES}, REQ_STORAGE_PERMISSION);
            }
        } else {
            if (ContextCompat.checkSelfPermission(this,
                    Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, REQ_STORAGE_PERMISSION);
            }
        }
    }

    // ─── Activity Results ─────────────────────────────────────────────────────

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQ_PICK_FILE && resultCode == Activity.RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri != null) setSelectedFile(uri);

        } else if (requestCode == REQ_PRINTER_DISCOVER && resultCode == Activity.RESULT_OK
                && data != null) {
            String name    = data.getStringExtra(PrinterDiscoveryActivity.EXTRA_PRINTER_NAME);
            String address = data.getStringExtra(PrinterDiscoveryActivity.EXTRA_PRINTER_ADDRESS);
            int port       = data.getIntExtra(PrinterDiscoveryActivity.EXTRA_PRINTER_PORT, 9100);
            String typeStr = data.getStringExtra(PrinterDiscoveryActivity.EXTRA_PRINTER_TYPE);

            PrinterInfo.ConnectionType type = PrinterInfo.ConnectionType.USB;
            try {
                if (typeStr != null) type = PrinterInfo.ConnectionType.valueOf(typeStr);
            } catch (Exception ignored) {}

            PrinterInfo printer = new PrinterInfo(name, address, type);
            printer.setPort(port);
            setPrinter(printer);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        // Permission result handled — file picker will work via content resolver regardless
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        usbPrinterManager.destroy();
        wifiPrinterManager.destroy();
    }
}
