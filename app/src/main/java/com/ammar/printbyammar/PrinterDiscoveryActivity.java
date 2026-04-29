package com.ammar.printbyammar;

import android.app.Activity;
import android.content.Intent;
import android.hardware.usb.UsbDevice;
import android.os.Bundle;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import java.util.ArrayList;
import java.util.List;

/**
 * Activity for discovering and selecting printers.
 * Supports USB, Wi-Fi (network scan + manual IP), and returns selected printer.
 */
public class PrinterDiscoveryActivity extends AppCompatActivity {

    public static final String EXTRA_PRINTER_NAME    = "printer_name";
    public static final String EXTRA_PRINTER_ADDRESS = "printer_address";
    public static final String EXTRA_PRINTER_PORT    = "printer_port";
    public static final String EXTRA_PRINTER_TYPE    = "printer_type";

    private RadioGroup rgConnectionType;
    private Button btnSearch;
    private Button btnManualIp;
    private ListView lvPrinters;
    private ProgressBar progressSearch;
    private TextView tvSearchStatus;

    private UsbPrinterManager usbManager;
    private WifiPrinterManager wifiManager;

    private List<PrinterInfo> printerList = new ArrayList<>();
    private ArrayAdapter<PrinterInfo> adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_printer_discovery);

        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("Select Printer");
        }

        rgConnectionType = findViewById(R.id.rgConnectionType);
        btnSearch = findViewById(R.id.btnSearch);
        btnManualIp = findViewById(R.id.btnManualIp);
        lvPrinters = findViewById(R.id.lvPrinters);
        progressSearch = findViewById(R.id.progressSearch);
        tvSearchStatus = findViewById(R.id.tvSearchStatus);

        usbManager = new UsbPrinterManager(this);
        wifiManager = new WifiPrinterManager(this);

        adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, printerList);
        lvPrinters.setAdapter(adapter);

        btnSearch.setOnClickListener(v -> startSearch());
        btnManualIp.setOnClickListener(v -> showManualIpDialog());

        lvPrinters.setOnItemClickListener((parent, view, position, id) -> {
            PrinterInfo printer = printerList.get(position);
            returnPrinter(printer);
        });

        // Auto-search USB on open
        searchUsb();
    }

    private void startSearch() {
        int selectedId = rgConnectionType.getCheckedRadioButtonId();
        printerList.clear();
        adapter.notifyDataSetChanged();

        if (selectedId == R.id.rbUsb) {
            searchUsb();
        } else if (selectedId == R.id.rbWifi) {
            searchWifi();
        }
    }

    private void searchUsb() {
        setSearching(true, "Checking USB...");
        List<UsbDevice> devices = usbManager.getConnectedPrinters();

        if (devices.isEmpty()) {
            setSearching(false, "No USB printers found.\nMake sure printer is connected via USB OTG cable.");
        } else {
            for (UsbDevice device : devices) {
                String name = device.getProductName();
                if (name == null || name.isEmpty()) name = "USB Printer";
                PrinterInfo printer = new PrinterInfo(name,
                        device.getDeviceName(), PrinterInfo.ConnectionType.USB);
                printerList.add(printer);
            }
            adapter.notifyDataSetChanged();
            setSearching(false, devices.size() + " USB printer(s) found");
        }
    }

    private void searchWifi() {
        setSearching(true, "Scanning network for printers...");
        wifiManager.discoverPrinters(new WifiPrinterManager.DiscoveryCallback() {
            @Override
            public void onPrinterFound(PrinterInfo printer) {
                runOnUiThread(() -> {
                    printerList.add(printer);
                    adapter.notifyDataSetChanged();
                });
            }

            @Override
            public void onDiscoveryComplete(List<PrinterInfo> printers) {
                runOnUiThread(() -> {
                    if (printers.isEmpty()) {
                        setSearching(false, "No network printers found.\nTry entering IP manually.");
                    } else {
                        setSearching(false, printers.size() + " printer(s) found on network");
                    }
                });
            }
        });
    }

    private void showManualIpDialog() {
        final EditText etIp = new EditText(this);
        etIp.setHint("e.g. 192.168.1.100");
        etIp.setInputType(android.text.InputType.TYPE_CLASS_PHONE);

        new AlertDialog.Builder(this)
                .setTitle("Enter Printer IP Address")
                .setMessage("Enter the IP address of your printer:")
                .setView(etIp)
                .setPositiveButton("Connect", (dialog, which) -> {
                    String ip = etIp.getText().toString().trim();
                    if (!ip.isEmpty()) {
                        connectToManualIp(ip);
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void connectToManualIp(String ip) {
        setSearching(true, "Connecting to " + ip + "...");
        wifiManager.addManualPrinter(ip, new WifiPrinterManager.PrintCallback() {
            @Override
            public void onSuccess() {
                runOnUiThread(() -> {
                    PrinterInfo printer = new PrinterInfo(
                            "Printer @ " + ip, ip, PrinterInfo.ConnectionType.WIFI);
                    printer.setPort(9100);
                    printerList.add(printer);
                    adapter.notifyDataSetChanged();
                    setSearching(false, "Connected to " + ip);
                });
            }

            @Override
            public void onProgress(int percent) {}

            @Override
            public void onError(String message) {
                runOnUiThread(() -> {
                    setSearching(false, "");
                    Toast.makeText(PrinterDiscoveryActivity.this, message, Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void returnPrinter(PrinterInfo printer) {
        Intent result = new Intent();
        result.putExtra(EXTRA_PRINTER_NAME, printer.getName());
        result.putExtra(EXTRA_PRINTER_ADDRESS, printer.getAddress());
        result.putExtra(EXTRA_PRINTER_PORT, printer.getPort());
        result.putExtra(EXTRA_PRINTER_TYPE, printer.getConnectionType().name());
        setResult(Activity.RESULT_OK, result);
        finish();
    }

    private void setSearching(boolean searching, String status) {
        progressSearch.setVisibility(searching ? View.VISIBLE : View.GONE);
        tvSearchStatus.setText(status);
        btnSearch.setEnabled(!searching);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        usbManager.destroy();
        wifiManager.destroy();
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }
}
