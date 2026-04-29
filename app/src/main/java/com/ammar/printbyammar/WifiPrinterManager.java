package com.ammar.printbyammar;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Handles Wi-Fi/Network printer discovery and printing.
 * Uses RAW printing on port 9100 (JetDirect protocol).
 * This is how HP LaserJet P3015 receives jobs over network.
 */
public class WifiPrinterManager {

    private static final String TAG = "WifiPrinterManager";
    private static final int RAW_PRINT_PORT = 9100;
    private static final int IPP_PORT = 631;
    private static final int CONNECT_TIMEOUT_MS = 3000;
    private static final int PRINT_TIMEOUT_MS = 15000;

    private Context context;
    private ExecutorService executor;

    public interface DiscoveryCallback {
        void onPrinterFound(PrinterInfo printer);
        void onDiscoveryComplete(List<PrinterInfo> printers);
    }

    public interface PrintCallback {
        void onSuccess();
        void onProgress(int percent);
        void onError(String message);
    }

    public WifiPrinterManager(Context context) {
        this.context = context;
        this.executor = Executors.newCachedThreadPool();
    }

    /**
     * Scans the local network subnet for printers on port 9100.
     * Gets the current subnet from WiFi manager and probes all 254 IPs.
     */
    public void discoverPrinters(DiscoveryCallback callback) {
        executor.execute(() -> {
            List<PrinterInfo> found = new ArrayList<>();

            try {
                WifiManager wifiManager = (WifiManager) context.getApplicationContext()
                        .getSystemService(Context.WIFI_SERVICE);
                int ipInt = wifiManager.getConnectionInfo().getIpAddress();
                String myIp = String.format("%d.%d.%d.%d",
                        (ipInt & 0xFF),
                        (ipInt >> 8 & 0xFF),
                        (ipInt >> 16 & 0xFF),
                        (ipInt >> 24 & 0xFF));

                String subnet = myIp.substring(0, myIp.lastIndexOf('.') + 1);
                Log.d(TAG, "Scanning subnet: " + subnet + "0/24");

                // Probe each IP in parallel
                List<Thread> threads = new ArrayList<>();
                for (int i = 1; i <= 254; i++) {
                    final String ip = subnet + i;
                    Thread t = new Thread(() -> {
                        if (isPrinterReachable(ip, RAW_PRINT_PORT)) {
                            String name = resolveHostname(ip);
                            PrinterInfo printer = new PrinterInfo(
                                    name, ip, PrinterInfo.ConnectionType.WIFI);
                            printer.setPort(RAW_PRINT_PORT);
                            found.add(printer);
                            callback.onPrinterFound(printer);
                            Log.d(TAG, "Found printer at " + ip);
                        }
                    });
                    threads.add(t);
                    t.start();
                }

                for (Thread t : threads) {
                    try { t.join(5000); } catch (InterruptedException ignored) {}
                }

            } catch (Exception e) {
                Log.e(TAG, "Discovery error: " + e.getMessage());
            }

            callback.onDiscoveryComplete(found);
        });
    }

    /**
     * Checks if a host has port 9100 open (printer RAW port).
     */
    private boolean isPrinterReachable(String ip, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(ip, port), CONNECT_TIMEOUT_MS);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Tries to resolve a human-readable hostname for an IP address.
     */
    private String resolveHostname(String ip) {
        try {
            InetAddress addr = InetAddress.getByName(ip);
            String hostname = addr.getHostName();
            if (hostname != null && !hostname.equals(ip)) {
                return hostname;
            }
        } catch (Exception ignored) {}
        return "Printer @ " + ip;
    }

    /**
     * Adds a printer manually by IP address.
     */
    public void addManualPrinter(String ipAddress, PrintCallback callback) {
        executor.execute(() -> {
            if (isPrinterReachable(ipAddress, RAW_PRINT_PORT)) {
                // Successfully confirmed printer at this IP
                callback.onSuccess();
            } else if (isPrinterReachable(ipAddress, IPP_PORT)) {
                callback.onSuccess();
            } else {
                callback.onError("Cannot reach printer at " + ipAddress
                        + "\nMake sure it's on the same Wi-Fi network.");
            }
        });
    }

    /**
     * Sends a print job to a network printer via RAW port 9100.
     * Accepts raw PCL/PostScript/image data.
     */
    public void printRaw(PrinterInfo printer, byte[] data, PrintCallback callback) {
        executor.execute(() -> {
            try (Socket socket = new Socket()) {
                socket.connect(
                        new InetSocketAddress(printer.getAddress(), printer.getPort()),
                        CONNECT_TIMEOUT_MS);
                socket.setSoTimeout(PRINT_TIMEOUT_MS);

                OutputStream out = socket.getOutputStream();

                // Send in chunks and report progress
                int chunkSize = 4096;
                int total = data.length;
                int sent = 0;

                while (sent < total) {
                    int len = Math.min(chunkSize, total - sent);
                    out.write(data, sent, len);
                    sent += len;
                    int progress = (int) ((sent / (float) total) * 100);
                    callback.onProgress(progress);
                }

                out.flush();
                Log.d(TAG, "Sent " + total + " bytes to " + printer.getAddress());
                callback.onSuccess();

            } catch (IOException e) {
                Log.e(TAG, "Network print error: " + e.getMessage());
                callback.onError("Print failed: " + e.getMessage());
            }
        });
    }

    /**
     * Sends a print job from an InputStream (e.g., for large PDFs).
     */
    public void printStream(PrinterInfo printer, InputStream inputStream,
                            long totalBytes, PrintCallback callback) {
        executor.execute(() -> {
            try (Socket socket = new Socket()) {
                socket.connect(
                        new InetSocketAddress(printer.getAddress(), printer.getPort()),
                        CONNECT_TIMEOUT_MS);
                socket.setSoTimeout(PRINT_TIMEOUT_MS);

                OutputStream out = socket.getOutputStream();
                byte[] buffer = new byte[4096];
                int bytesRead;
                long totalSent = 0;

                while ((bytesRead = inputStream.read(buffer)) != -1) {
                    out.write(buffer, 0, bytesRead);
                    totalSent += bytesRead;
                    if (totalBytes > 0) {
                        int progress = (int) ((totalSent / (float) totalBytes) * 100);
                        callback.onProgress(progress);
                    }
                }

                out.flush();
                callback.onSuccess();

            } catch (IOException e) {
                Log.e(TAG, "Stream print error: " + e.getMessage());
                callback.onError("Print failed: " + e.getMessage());
            }
        });
    }

    public void destroy() {
        executor.shutdownNow();
    }
}
