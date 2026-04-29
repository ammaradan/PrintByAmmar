package com.ammar.printbyammar;

/**
 * Stores all user-selected print settings.
 */
public class PrintOptions {

    public enum PaperSize {
        A4, LETTER, LEGAL, A3, A5
    }

    public enum Orientation {
        PORTRAIT, LANDSCAPE
    }

    public enum ColorMode {
        COLOR, BLACK_AND_WHITE
    }

    private int copies = 1;
    private PaperSize paperSize = PaperSize.A4;
    private Orientation orientation = Orientation.PORTRAIT;
    private ColorMode colorMode = ColorMode.COLOR;
    private boolean duplex = false;
    private boolean fitToPage = true;

    // Getters
    public int getCopies() { return copies; }
    public PaperSize getPaperSize() { return paperSize; }
    public Orientation getOrientation() { return orientation; }
    public ColorMode getColorMode() { return colorMode; }
    public boolean isDuplex() { return duplex; }
    public boolean isFitToPage() { return fitToPage; }

    // Setters
    public void setCopies(int copies) { this.copies = Math.max(1, Math.min(99, copies)); }
    public void setPaperSize(PaperSize paperSize) { this.paperSize = paperSize; }
    public void setOrientation(Orientation orientation) { this.orientation = orientation; }
    public void setColorMode(ColorMode colorMode) { this.colorMode = colorMode; }
    public void setDuplex(boolean duplex) { this.duplex = duplex; }
    public void setFitToPage(boolean fitToPage) { this.fitToPage = fitToPage; }

    /**
     * Returns paper dimensions in mm as [width, height] in portrait orientation.
     */
    public int[] getPaperDimensionsMm() {
        switch (paperSize) {
            case A4:     return new int[]{210, 297};
            case LETTER: return new int[]{216, 279};
            case LEGAL:  return new int[]{216, 356};
            case A3:     return new int[]{297, 420};
            case A5:     return new int[]{148, 210};
            default:     return new int[]{210, 297};
        }
    }

    public String getPaperSizeLabel() {
        switch (paperSize) {
            case A4:     return "A4";
            case LETTER: return "Letter";
            case LEGAL:  return "Legal";
            case A3:     return "A3";
            case A5:     return "A5";
            default:     return "A4";
        }
    }
}
