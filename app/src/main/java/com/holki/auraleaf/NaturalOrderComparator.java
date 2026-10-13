package com.holki.auraleaf;

import java.util.Comparator;

/**
 * Natural order comparison for filenames (e.g., page2.jpg comes before page10.jpg).
 */
public class NaturalOrderComparator implements Comparator<String> {

    @Override
    public int compare(String a, String b) {
        if (a == null && b == null) return 0;
        if (a == null) return -1;
        if (b == null) return 1;

        int ia = 0, ib = 0;
        int nza, nzb;
        char ca, cb;

        while (true) {
            nza = nzb = 0;
            ca = charAt(a, ia);
            cb = charAt(b, ib);

            // Skip leading spaces or zeros in numeric run
            while (Character.isSpaceChar(ca) || ca == '0') {
                if (ca == '0') nza++;
                else nza = 0;
                ca = charAt(a, ++ia);
            }

            while (Character.isSpaceChar(cb) || cb == '0') {
                if (cb == '0') nzb++;
                else nzb = 0;
                cb = charAt(b, ++ib);
            }

            // Process digits
            if (Character.isDigit(ca) && Character.isDigit(cb)) {
                int bias = compareRight(a.substring(ia), b.substring(ib));
                if (bias != 0) return bias;
            }

            if (ca == 0 && cb == 0) {
                // The strings compare the same. Perhaps the number of zeros differ
                return nza - nzb;
            }

            if (ca < cb) return -1;
            if (ca > cb) return 1;

            ia++;
            ib++;
        }
    }

    private static int compareRight(String a, String b) {
        int bias = 0;
        int ia = 0;
        int ib = 0;

        for (;; ia++, ib++) {
            char ca = charAt(a, ia);
            char cb = charAt(b, ib);

            if (!Character.isDigit(ca) && !Character.isDigit(cb)) {
                return bias;
            }
            if (!Character.isDigit(ca)) {
                return -1;
            }
            if (!Character.isDigit(cb)) {
                return 1;
            }
            if (ca < cb) {
                if (bias == 0) bias = -1;
            } else if (ca > cb) {
                if (bias == 0) bias = 1;
            }
        }
    }

    private static char charAt(String s, int i) {
        return i >= s.length() ? 0 : s.charAt(i);
    }
}
