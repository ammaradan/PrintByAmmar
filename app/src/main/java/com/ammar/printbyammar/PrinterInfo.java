package com.ammar.printbyammar;

/**
 * Holds information about a discovered printer.
 */
public class PrinterInfo {

    public enum ConnectionType {
        USB, WIFI, BLUETOOTH
    }

    private String name;
    private String address;       // IP for WiFi, MAC for Bluetooth, device path for USB
    private int port;             // default 9100 for RAW, 631 for IPP
    private ConnectionType connectionType;
    private boolean isConnected;

    public PrinterInfo(String name, String address, ConnectionType connectionType) {
        this.name = name;
        this.address = address;
        this.connectionType = connectionType;
        this.port = (connectionType == ConnectionType.WIFI) ? 9100 : 0;
        this.isConnected = false;
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getAddress() { return address; }
    public void setAddress(String address) { this.address = address; }

    public int getPort() { return port; }
    public void setPort(int port) { this.port = port; }

    public ConnectionType getConnectionType() { return connectionType; }
    public void setConnectionType(ConnectionType connectionType) { this.connectionType = connectionType; }

    public boolean isConnected() { return isConnected; }
    public void setConnected(boolean connected) { isConnected = connected; }

    public String getConnectionLabel() {
        switch (connectionType) {
            case USB:       return "USB";
            case WIFI:      return "Wi-Fi  " + address;
            case BLUETOOTH: return "Bluetooth";
            default:        return "Unknown";
        }
    }

    @Override
    public String toString() {
        return name + " (" + getConnectionLabel() + ")";
    }
}
