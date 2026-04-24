package com.swisscom.atclient;

import java.util.Locale;

/**
 * Operator-facing hints for technical radio values that appear in atclient logs.
 *
 * Centralizes the human-readable annotations so every log line that mentions a
 * band, PLMN, registration stat, or signal value reads consistently. The
 * classifier emits a uniform 5-level strength scale (very weak / weak / good /
 * strong / very strong) so CSQ, RSRP, RSRQ, and SINR can be compared at a glance.
 *
 * This class is intentionally dependency-free so it can be used from any helper
 * without pulling in log4j or modem state.
 */
public final class RadioGlossary {

    private RadioGlossary() {}

    public enum SignalLevel {
        VERY_STRONG("very strong"),
        STRONG("strong"),
        GOOD("good"),
        WEAK("weak"),
        VERY_WEAK("very weak"),
        UNKNOWN("unknown");

        private final String label;
        SignalLevel(String label) { this.label = label; }
        public String label() { return label; }
    }

    /** CSQ 0..31 per 3GPP TS 27.007 (31 = best, 99 = not known). */
    public static SignalLevel classifyCsq(Integer csq) {
        if (csq == null || csq == 99) return SignalLevel.UNKNOWN;
        int v = csq.intValue();
        if (v >= 26) return SignalLevel.VERY_STRONG;
        if (v >= 20) return SignalLevel.STRONG;
        if (v >= 15) return SignalLevel.GOOD;
        if (v >= 10) return SignalLevel.WEAK;
        return SignalLevel.VERY_WEAK;
    }

    /** RSRP in dBm. Thresholds aligned with common cellular-coverage tables. */
    public static SignalLevel classifyRsrp(Double dbm) {
        if (dbm == null) return SignalLevel.UNKNOWN;
        double v = dbm.doubleValue();
        if (v >= -80.0)  return SignalLevel.VERY_STRONG;
        if (v >= -90.0)  return SignalLevel.STRONG;
        if (v >= -100.0) return SignalLevel.GOOD;
        if (v >= -110.0) return SignalLevel.WEAK;
        return SignalLevel.VERY_WEAK;
    }

    /** RSRQ in dB (less negative is better). */
    public static SignalLevel classifyRsrq(Double db) {
        if (db == null) return SignalLevel.UNKNOWN;
        double v = db.doubleValue();
        if (v >= -8.0)  return SignalLevel.VERY_STRONG;
        if (v >= -10.0) return SignalLevel.STRONG;
        if (v >= -12.0) return SignalLevel.GOOD;
        if (v >= -15.0) return SignalLevel.WEAK;
        return SignalLevel.VERY_WEAK;
    }

    /** SS-SINR (NR) or RS-SNR (LTE) in dB. */
    public static SignalLevel classifySinr(Double db) {
        if (db == null) return SignalLevel.UNKNOWN;
        double v = db.doubleValue();
        if (v >= 20.0) return SignalLevel.VERY_STRONG;
        if (v >= 13.0) return SignalLevel.STRONG;
        if (v >= 7.0)  return SignalLevel.GOOD;
        if (v >= 0.0)  return SignalLevel.WEAK;
        return SignalLevel.VERY_WEAK;
    }

    /**
     * Decode a band token from +CPSI (e.g. "n28", "NR5G_BAND28", "LTE BAND 3", "B3")
     * and return "&lt;token&gt; (&lt;frequency range, notes&gt;)". Falls back to the raw token
     * when the band is unknown.
     */
    public static String bandHint(String bandToken) {
        if (bandToken == null || bandToken.isEmpty()) return "n/a";
        String raw = bandToken.trim();
        String upper = raw.toUpperCase(Locale.ROOT);

        Integer nrBand = parseNrBand(upper);
        if (nrBand != null) {
            String info = nrBandInfo(nrBand);
            String pretty = "n" + nrBand;
            return info == null ? pretty : pretty + " (" + info + ")";
        }

        Integer lteBand = parseLteBand(upper);
        if (lteBand != null) {
            String info = lteBandInfo(lteBand);
            String pretty = "B" + lteBand;
            return info == null ? pretty : pretty + " (" + info + ")";
        }

        return raw;
    }

    private static Integer parseNrBand(String upper) {
        if (upper.startsWith("N") && upper.length() > 1) {
            try { return Integer.parseInt(upper.substring(1)); } catch (NumberFormatException ignored) {}
        }
        if (upper.startsWith("NR5G_BAND")) {
            try { return Integer.parseInt(upper.substring("NR5G_BAND".length())); } catch (NumberFormatException ignored) {}
        }
        if (upper.startsWith("NR BAND")) {
            try { return Integer.parseInt(upper.substring("NR BAND".length()).trim()); } catch (NumberFormatException ignored) {}
        }
        return null;
    }

    private static Integer parseLteBand(String upper) {
        if (upper.startsWith("B") && upper.length() > 1) {
            try { return Integer.parseInt(upper.substring(1)); } catch (NumberFormatException ignored) {}
        }
        if (upper.startsWith("LTE BAND")) {
            try { return Integer.parseInt(upper.substring("LTE BAND".length()).trim()); } catch (NumberFormatException ignored) {}
        }
        if (upper.startsWith("EUTRAN-BAND")) {
            try { return Integer.parseInt(upper.substring("EUTRAN-BAND".length())); } catch (NumberFormatException ignored) {}
        }
        return null;
    }

    private static String nrBandInfo(int band) {
        switch (band) {
            case 1:  return "2.1 GHz mid-band";
            case 3:  return "1.8 GHz mid-band";
            case 5:  return "850 MHz low-band";
            case 7:  return "2.6 GHz mid-band";
            case 8:  return "900 MHz low-band";
            case 20: return "800 MHz low-band";
            case 28: return "700 MHz low-band, wide coverage but lower capacity";
            case 38: return "2.6 GHz TDD mid-band";
            case 40: return "2.3 GHz TDD mid-band";
            case 41: return "2.5 GHz TDD mid-band";
            case 66: return "1.7/2.1 GHz AWS mid-band";
            case 71: return "600 MHz low-band";
            case 77: return "3.7 GHz C-band, high capacity";
            case 78: return "3.5 GHz C-band, high capacity but short range";
            case 79: return "4.7 GHz C-band";
            case 257: return "28 GHz mmWave";
            case 258: return "26 GHz mmWave";
            case 260: return "39 GHz mmWave";
            case 261: return "28 GHz mmWave";
            default: return null;
        }
    }

    private static String lteBandInfo(int band) {
        switch (band) {
            case 1:  return "2.1 GHz mid-band, LTE";
            case 3:  return "1.8 GHz mid-band, LTE";
            case 7:  return "2.6 GHz mid-band, LTE";
            case 8:  return "900 MHz low-band, LTE";
            case 20: return "800 MHz low-band, LTE";
            case 28: return "700 MHz low-band, LTE";
            case 32: return "1.5 GHz SDL, LTE";
            case 38: return "2.6 GHz TDD, LTE";
            case 40: return "2.3 GHz TDD, LTE";
            case 41: return "2.5 GHz TDD, LTE";
            case 66: return "1.7/2.1 GHz AWS, LTE";
            default: return null;
        }
    }

    /**
     * Operator hint from PLMN (MCC+MNC concatenated). Covers the Swiss MNOs seen
     * in Swisscom Mobile-ID deployments. Unknown codes are returned as "unknown PLMN".
     */
    public static String plmnHint(String plmn) {
        if (plmn == null) return "unknown";
        String trimmed = plmn.trim();
        if (trimmed.isEmpty()) return "unknown";
        switch (trimmed) {
            case "22801": return "Swisscom CH";
            case "22802": return "Sunrise CH";
            case "22803": return "Salt CH";
            case "22805": return "Swisscom Event CH";
            case "22807": return "TDC CH (test)";
            case "22808": return "Swisscom MVNO CH";
            case "22812": return "Sunrise MVNO CH";
            case "22851": return "Swisscom CH (5G pilot range)";
            default:
                if (trimmed.startsWith("228")) return "Switzerland (MCC 228), unknown MNO";
                if (trimmed.startsWith("262")) return "Germany (MCC 262)";
                if (trimmed.startsWith("208")) return "France (MCC 208)";
                if (trimmed.startsWith("222")) return "Italy (MCC 222)";
                if (trimmed.startsWith("232")) return "Austria (MCC 232)";
                return "unknown PLMN " + trimmed;
        }
    }

    /**
     * 3GPP registration stat (TS 27.007 +CREG/+CEREG/+C5GREG). Stat=3 carries an
     * extra hint because it usually means the subscription is not provisioned for
     * the RAT (e.g. 5G SA not enabled for the IMSI).
     */
    public static String regStatHint(Integer stat) {
        if (stat == null) return "n/a";
        switch (stat.intValue()) {
            case 0: return "not-registered (UE is not searching)";
            case 1: return "home-registered";
            case 2: return "searching (no cell found yet)";
            case 3: return "registration-denied (network rejected attach - check subscription or 5G SA provisioning)";
            case 4: return "unknown";
            case 5: return "roaming-registered";
            case 6: return "registered for SMS only (home)";
            case 7: return "registered for SMS only (roaming)";
            case 8: return "attached for emergency services only";
            case 9: return "registered for CSFB not preferred (home)";
            case 10: return "registered for CSFB not preferred (roaming)";
            default: return "stat-" + stat;
        }
    }

    /** CNMP (SIMCom network-mode preference) code hint. */
    public static String cnmpHint(Integer code) {
        if (code == null) return "n/a";
        switch (code.intValue()) {
            case 2:   return "automatic (any RAT)";
            case 13:  return "GSM only";
            case 14:  return "WCDMA only";
            case 38:  return "LTE only";
            case 71:  return "NR5G only (5G SA)";
            case 109: return "LTE + NR5G (EN-DC preferred, typical 5G NSA default)";
            default:  return "CNMP=" + code;
        }
    }

    /**
     * CPSI mode hint (e.g. "NR5G_SA" to "5G standalone"). Recognises SIMCom
     * spellings; unknown values returned unchanged.
     */
    public static String cpsiModeHint(String mode) {
        if (mode == null) return "n/a";
        String upper = mode.trim().toUpperCase(Locale.ROOT);
        if (upper.isEmpty()) return "n/a";
        if (upper.startsWith("NO SERVICE")) return "no service (UE is not camped on any cell)";
        if ("LTE".equals(upper)) return "4G LTE";
        if ("WCDMA".equals(upper)) return "3G UMTS";
        if ("GSM".equals(upper) || "EDGE".equals(upper)) return "2G";
        if ("NR5G_SA".equals(upper)) return "5G standalone (NR only, no LTE anchor)";
        if ("NR5G_NSA".equals(upper) || "LTE_NR5G".equals(upper)) return "5G non-standalone (LTE anchor + NR leg, EN-DC)";
        if ("NGEN-DC".equals(upper)) return "5G NR with E-UTRA dual connectivity";
        return upper;
    }

    /** CFUN argument hint. */
    public static String cfunHint(String argument) {
        if (argument == null) return "n/a";
        String a = argument.trim();
        switch (a) {
            case "0":   return "minimum RF (modem off-air)";
            case "1":   return "full RF (modem active)";
            case "4":   return "airplane mode (RF disabled)";
            case "1,1": return "full modem reboot";
            case "1,0": return "full RF, no reset";
            default:    return "CFUN=" + a;
        }
    }
}
