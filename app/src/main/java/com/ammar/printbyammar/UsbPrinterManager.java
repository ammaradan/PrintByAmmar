package com.ammar.printbyammar;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * Manages USB printer discovery and printing via Android USB Host API.
 * Supports any printer that uses the USB Printer Class (class 7).
 *
 * HP LaserJet P3015 is fully supported via USB OTG.
 */
public class UsbPrinterManager {

    private static final String TAG = "UsbPrinterManager";
    private static final String ACTION_USB_PERMISSION = "com.ammar.printbyammar.USB_PERMISSION";
    private static final int TIMEOUT_MS = 10000;

    private Context context;
    private UsbManager usbManager;
    private UsbDevice selectedDevice;
    private UsbDeviceConnection connection;
    private UsbEndpoint outEndpoint;
    private UsbInterface printerInterface;

    public interface UsbPermissionCallback {
        void onPermissionGranted(UsbDevice device);
        void onPermissionDenied();
    }

    public interface PrintCallback {
        void onSuccess();
        void onError(String message);
    }

    private UsbPermissionCallback permissionCallback;

    private final BroadcastReceiver usbPermissionReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            String action = intent.getAction();
            if (ACTION_USB_PERMISSION.equals(action)) {
                synchronized (this) {
                    UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        if (device != null && permissionCallback != null) {
                            permissionCallback.onPermissionGranted(device);
                        }
                    } else {
                        if (permissionCallback != null) {
                            permissionCallback.onPermissionDenied();
                        }
                    }
                }
            }
        }
    };

    public UsbPrinterManager(Context context) {
        this.context = context;
        this.usbManager = (UsbManager) context.getSystemService(Context.USB_SERVICE);

        IntentFilter filter = new IntentFilter(ACTION_USB_PERMISSION);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(usbPermissionReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            context.registerReceiver(usbPermissionReceiver, filter);
        }
    }

    /**
     * Returns list of all connected USB printers (USB class 7).
     */
    public List<UsbDevice> getConnectedPrinters() {
        List<UsbDevice> printers = new ArrayList<>();
        HashMap<String, UsbDevice> deviceList = usbManager.getDeviceList();
        for (UsbDevice device : deviceList.values()) {
            if (isPrinterDevice(device)) {
                printers.add(device);
                Log.d(TAG, "Found printer: " + device.getProductName()
                        + " VID=" + device.getVendorId()
                        + " PID=" + device.getProductId());
            }
        }
        return printers;
    }

    /**
     * Checks if a USB device is a printer (class 7 or has printer interface).
     */
    private boolean isPrinterDevice(UsbDevice device) {
        // Check device class
        if (device.getDeviceClass() == UsbConstants.USB_CLASS_PRINTER) {
            return true;
        }
        // Check interface classes
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            UsbInterface iface = device.getInterface(i);
            if (iface.getInterfaceClass() == UsbConstants.USB_CLASS_PRINTER) {
                return true;
            }
        }
        // HP LaserJet specific vendor IDs
        int vendorId = device.getVendorId();
        return vendorId == 0x03F0  // HP
            || vendorId == 0x04A9  // Canon
            || vendorId == 0x04B8  // Epson
            || vendorId == 0x04F9  // Brother
            || vendorId == 0x04E8; // Samsung
    }

    /**
     * Requests USB permission for the given device.
     */
    public void requestPermission(UsbDevice device, UsbPermissionCallback callback) {
        this.permissionCallback = callback;
        if (usbManager.hasPermission(device)) {
            callback.onPermissionGranted(device);
            return;
        }
        int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                ? PendingIntent.FLAG_IMMUTABLE
                : PendingIntent.FLAG_UPDATE_CURRENT;
        PendingIntent permissionIntent = PendingIntent.getBroadcast(
                context, 0, new Intent(ACTION_USB_PERMISSION), flags);
        usbManager.requestPermission(device, permissionIntent);
    }

    /**
     * Opens connection to the USB printer and finds the output endpoint.
     */
    public boolean openConnection(UsbDevice device) {
        selectedDevice = device;

        // Find printer interface
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            UsbInterface iface = device.getInterface(i);
            if (iface.getInterfaceClass() == UsbConstants.USB_CLASS_PRINTER
                    || device.getDeviceClass() == UsbConstants.USB_CLASS_PRINTER) {
                printerInterface = iface;

                // Find bulk-out endpoint
                for (int e = 0; e < iface.getEndpointCount(); e++) {
                    UsbEndpoint ep = iface.getEndpoint(e);
                    if (ep.getType() == UsbConstants.USB_ENDPOINT_XFER_BULK
                            && ep.getDirection() == UsbConstants.USB_DIR_OUT) {
                        outEndpoint = ep;
                        break;
                    }
                }
                break;
            }
        }

        if (outEndpoint == null) {
            // Fallback: try any bulk-out endpoint
            for (int i = 0; i < device.getInterfaceCount(); i++) {
                UsbInterface iface = device.getInterface(i);
                for (int e = 0; e < iface.getEndpointCount(); e++) {
                    UsbEndpoint ep = iface.getEndpoint(e);
                    if (ep.getType() == UsbConstants.USB_ENDPOINT_XFER_BULK
                            && ep.getDirection() == UsbConstants.USB_DIR_OUT) {
                        printerInterface = iface;
                        outEndpoint = ep;
                        break;
                    }
                }
                if (outEndpoint != null) break;
            }
        }

        if (outEndpoint == null) {
            Log.e(TAG, "No bulk-out endpoint found");
            return false;
        }

        connection = usbManager.openDevice(device);
        if (connection == null) {
            Log.e(TAG, "Failed to open USB device");
            return false;
        }

        boolean claimed = connection.claimInterface(printerInterface, true);
        Log.d(TAG, "Interface claimed: " + claimed);
        return claimed;
    }

    /**
     * Sends raw bytes to the printer via USB bulk transfer.
     * Suitable for PCL, PostScript, or image data.
     */
    public boolean sendRawData(byte[] data) {
        if (connection == null || outEndpoint == null) {
            Log.e(TAG, "No USB connection");
            return false;
        }

        int chunkSize = outEndpoint.getMaxPacketSize();
        if (chunkSize <= 0) chunkSize = 512;

        int offset = 0;
        while (offset < data.length) {
            int length = Math.min(chunkSize, data.length - offset);
            byte[] chunk = new byte[length];
            System.arraycopy(data, offset, chunk, 0, length);
            int sent = connection.bulkTransfer(outEndpoint, chunk, length, TIMEOUT_MS);
            if (sent < 0) {
                Log.e(TAG, "USB bulk transfer failed at offset " + offset);
                return false;
            }
            offset += sent;
        }
        Log.d(TAG, "Sent " + data.length + " bytes to USB printer");
        return true;
    }

    /**
     * Sends data from an InputStream to the printer.
     */
    public boolean sendStream(InputStream inputStream) throws IOException {
        byte[] buffer = new byte[4096];
        int bytesRead;
        while ((bytesRead = inputStream.read(buffer)) != -1) {
            byte[] chunk = new byte[bytesRead];
            System.arraycopy(buffer, 0, chunk, 0, bytesRead);
            if (!sendRawData(chunk)) return false;
        }
        return true;
    }

    /**
     * Closes the USB connection cleanly.
     */
    public void closeConnection() {
        if (connection != null && printerInterface != null) {
            connection.releaseInterface(printerInterface);
        }
        if (connection != null) {
            connection.close();
            connection = null;
        }
        selectedDevice = null;
        outEndpoint = null;
        printerInterface = null;
    }

    public void destroy() {
        try {
            context.unregisterReceiver(usbPermissionReceiver);
        } catch (Exception e) {
            Log.w(TAG, "Receiver not registered");
        }
        closeConnection();
    }

    public boolean isConnected() {
        return connection != null && outEndpoint != null;
    }

    public UsbDevice getSelectedDevice() {
        return selectedDevice;
    }
}
