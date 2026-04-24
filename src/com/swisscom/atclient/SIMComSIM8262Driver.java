package com.swisscom.atclient;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@link ModemDriver} implementation for the SIMCom SIM8262E-M2 (Qualcomm X62) 5G module.
 *
 * Empirical formats (validated on Swisscom Mobile-ID auth, 2026-04-17):
 *   - STK enabled via AT+STK=1, decoded mode via AT+STKFMT=0
 *   - Single URC kind: +STIN: &lt;cmdType&gt; for all proactive commands and state changes
 *   - Proactive-command details via AT+STGI=&lt;cmdType&gt;
 *   - DISPLAY TEXT ack: AT+STGR=21,0
 *   - GET INPUT (PIN submit): AT+STGR=23,"&lt;plaintext_pin&gt;" — SINGLE arg, plaintext in quotes,
 *     no result-code prefix. UCS2-hex with a result-code prefix is REJECTED with ERROR.
 *   - SELECT ITEM / SETUP MENU: AT+STGR=&lt;cmdType&gt;,&lt;item_id&gt; — single arg item id
 *   - After a completed auth, the applet re-emits +STIN: 25 (SET UP MENU). This
 *     is used as the "return to main menu" marker by the core.
 *
 * Command-type numbers observed:
 *   21 DISPLAY TEXT | 23 GET INPUT | 24 SELECT ITEM | 25 SET UP MENU
 *   13 SEND MESSAGE (not fired during standard Mobile-ID auth; included defensively)
 *   20 PLAY TONE (defensive)
 *
 * Text fields in STGI responses are UCS-2 hex encoded even in "decoded" mode.
 */
public final class SIMComSIM8262Driver implements ModemDriver {

    public static final int CMD_SEND_MESSAGE   = 13;
    public static final int CMD_PLAY_TONE      = 20;
    public static final int CMD_DISPLAY_TEXT   = 21;
    public static final int CMD_GET_INPUT      = 23;
    public static final int CMD_SELECT_ITEM    = 24;
    public static final int CMD_SET_UP_MENU    = 25;

    public static final int RESULT_OK           = 0;
    public static final int RESULT_USER_CANCEL  = 16;
    public static final int RESULT_USER_TIMEOUT = 18;

    private static final Pattern STIN_PATTERN =
            Pattern.compile("\\+STIN:\\s*(\\d+)", Pattern.CASE_INSENSITIVE);
    // STGI text field: extract the first UCS2-hex string in double quotes on the line
    private static final Pattern STGI_TEXT_PATTERN =
            Pattern.compile("\"([0-9A-Fa-f]+)\"");

    private String vendor;
    private String model;
    private String revision;
    private String imei;

    // Network mode code used in buildRATSelectionCommand. Mapping:
    //   "AUTO"   -> AT+CNMP=2
    //   "LTE"    -> AT+CNMP=38
    //   "NR"     -> AT+CNMP=71    (5G NR SA only)
    //   "LTE_NR" -> AT+CNMP=109   (LTE + NR5G preferred, Swisscom 2026 default)
    //   default  -> AT+CNMP=109

    // ---- Identification -----------------------------------------------------

    @Override
    public String getVendorName() {
        return (vendor != null) ? vendor : "SIMCom";
    }

    @Override
    public String getModemFamilyLabel() {
        return (model != null) ? model : "SIM8262E-M2";
    }

    @Override
    public String getPortNameHintLinux() {
        return "SimTech";
    }

    @Override
    public int getDefaultBaudRate() {
        return 115200;
    }

    // ---- STK URC detection --------------------------------------------------

    @Override
    public boolean isSTKProactiveURC(String line) {
        if (line == null) return false;
        return line.trim().toUpperCase().startsWith("+STIN:");
    }

    /**
     * SIMCom emits a single URC kind; state-change detection therefore returns
     * the same result as the proactive-command check.
     */
    @Override
    public boolean isSTKStateURC(String line) {
        return isSTKProactiveURC(line);
    }

    @Override
    public int parseSTKCommandType(String urcLine) {
        if (urcLine == null) return -1;
        Matcher m = STIN_PATTERN.matcher(urcLine);
        if (m.find()) {
            try { return Integer.parseInt(m.group(1)); } catch (NumberFormatException ignored) {}
        }
        return -1;
    }

    /**
     * SIMCom +STIN URCs always require a terminal response; there is no PAC/WAIT
     * distinction in the URC format.
     */
    @Override
    public boolean requiresAcknowledgement(String urcLine) {
        return isSTKProactiveURC(urcLine);
    }

    // ---- STK info retrieval -------------------------------------------------

    @Override
    public String buildGetInfoCommand(int cmdType) {
        return "AT+STGI=" + cmdType;
    }

    @Override
    public boolean isSTKInfoResponse(String line) {
        return line != null && line.trim().toUpperCase().startsWith("+STGI:");
    }

    @Override
    public String extractDisplayText(String stgiLine) {
        if (stgiLine == null || !stgiLine.contains("+STGI:")) return null;
        Matcher m = STGI_TEXT_PATTERN.matcher(stgiLine);
        if (!m.find()) return null;
        String ucs2Hex = m.group(1);
        if (ucs2Hex == null || ucs2Hex.isEmpty() || (ucs2Hex.length() % 4) != 0) return null;
        try {
            return new String(hexToByte(ucs2Hex), StandardCharsets.UTF_16);
        } catch (RuntimeException e) {
            return null;
        }
    }

    // ---- STK terminal-response actions --------------------------------------

    @Override
    public void ackDisplayText(ATCommandSender sender, int cmdType) {
        sender.send("AT+STGR=" + cmdType + "," + RESULT_OK);
    }

    @Override
    public void cancelSession(ATCommandSender sender, int cmdType) {
        sender.send("AT+STGR=" + cmdType + "," + RESULT_USER_CANCEL);
    }

    @Override
    public void sessionTimeout(ATCommandSender sender, int cmdType) {
        sender.send("AT+STGR=" + cmdType + "," + RESULT_USER_TIMEOUT);
    }

    /**
     * GET INPUT response on SIMCom decoded mode: plain text in quotes, single arg,
     * NO result-code prefix. Any other format (UCS2-hex, 3-arg with leading 0)
     * returns ERROR. This was determined empirically on 2026-04-17.
     */
    @Override
    public void submitInput(ATCommandSender sender, int cmdType, String text) {
        String plain = text == null ? "" : text;
        sender.send("AT+STGR=" + cmdType + ",\"" + plain + "\"");
    }

    /**
     * SELECT ITEM / SET UP MENU response on SIMCom: single-arg item id.
     * Example: AT+STGR=24,50 selects item 50 out of the offered list.
     */
    @Override
    public void selectMenuItem(ATCommandSender sender, int cmdType, int itemId) {
        sender.send("AT+STGR=" + cmdType + "," + itemId);
    }

    @Override
    public void acknowledgeProactive(ATCommandSender sender, int cmdType) {
        sender.send("AT+STGR=" + cmdType + "," + RESULT_OK);
    }

    // ---- Command-type semantic mapping --------------------------------------

    @Override public int getSendMessageType()  { return CMD_SEND_MESSAGE; }
    @Override public int getPlayToneType()     { return CMD_PLAY_TONE; }
    @Override public int getDisplayTextType()  { return CMD_DISPLAY_TEXT; }
    @Override public int getGetInputType()     { return CMD_GET_INPUT; }
    @Override public int getSelectItemType()   { return CMD_SELECT_ITEM; }
    @Override public int getSetUpMenuType()    { return CMD_SET_UP_MENU; }

    /**
     * SIMCom has no distinct "return to main menu" STK event; the applet re-emits
     * SET UP MENU (+STIN: 25) when it returns to idle after a completed auth.
     * Mapping this to CMD_SET_UP_MENU lets the core treat the re-emit as the
     * post-session recovery trigger.
     */
    @Override public int getReturnToMainType() { return CMD_SET_UP_MENU; }

    /** SIMCom does not have a documented "SIM lost" STK event. */
    @Override public int getSimLostType() { return -1; }

    // ---- PIN encoding (plaintext) ------------------------------------------

    /**
     * SIMCom SIM8262E in decoded mode accepts the PIN in plaintext in the
     * AT+STGR=23,"&lt;pin&gt;" response. UCS2-hex is rejected with ERROR.
     */
    @Override
    public String formatPinForResponse(String plainPin) {
        return plainPin == null ? "" : plainPin;
    }

    // ---- Signal / network ---------------------------------------------------

    /**
     * SIMCom SIM82xx firmware 22131B05X62M44A-M2 does not support AT+CESQ
     * (returns ERROR). Use AT+CPSI? for extended signal/RAT/band info.
     */
    @Override
    public String buildExtendedSignalCommand() {
        return "AT+CPSI?";
    }

    @Override
    public String buildCellInfoCommand() {
        return "AT+CNWINFO?";
    }

    @Override
    public String buildRATSelectionCommand(String mode) {
        if (mode == null) return "AT+CNMP=109";
        switch (mode.toUpperCase()) {
            case "AUTO":   return "AT+CNMP=2";
            case "LTE":    return "AT+CNMP=38";
            case "NR":
            case "NR5G":   return "AT+CNMP=71";
            case "LTE_NR":
            case "LTE+NR": return "AT+CNMP=109";
            default:       return "AT+CNMP=109";
        }
    }

    @Override
    public boolean supports5GRegistration() {
        return true;
    }

    @Override
    public String build5GRegistrationQuery() {
        return "AT+C5GREG?";
    }

    // ---- Startup readiness profile -----------------------------------------

    @Override
    public String[] getStartupReadinessCommands() {
        // Include C5GREG in addition to CREG/CEREG; the driver accepts "ready"
        // if the modem is registered on any of these RATs.
        return new String[] { "AT+CPIN?", "AT+CREG?", "AT+CEREG?", "AT+C5GREG?", "AT+COPS?", "AT+CSQ" };
    }

    @Override
    public boolean isStartupReady(Map<String, Boolean> flags) {
        if (flags == null) return false;
        boolean sim = Boolean.TRUE.equals(flags.get("simReady"));
        boolean lteReg = Boolean.TRUE.equals(flags.get("ceregReady"));
        boolean nrReg  = Boolean.TRUE.equals(flags.get("c5gregReady"));
        boolean anyReg = lteReg || nrReg || Boolean.TRUE.equals(flags.get("registrationReady"));
        boolean net = Boolean.TRUE.equals(flags.get("networkReady"));
        boolean sig = Boolean.TRUE.equals(flags.get("signalReady"));
        return sim && anyReg && net && sig;
    }

    @Override
    public int getDegradedSignalThreshold() {
        return 9;
    }

    @Override
    public String buildRadioRecoveryCommand() {
        return "AT+COPS=0";
    }

    // ---- Modem identity -----------------------------------------------------

    @Override
    public void updateIdentityFromLine(String line) {
        if (line == null) return;
        String normalized = line.trim();
        String upper = normalized.toUpperCase();
        if (upper.startsWith("MANUFACTURER:")) {
            vendor = afterColon(normalized);
        } else if (upper.startsWith("MODEL:")) {
            model = afterColon(normalized);
        } else if (upper.startsWith("REVISION:")) {
            revision = afterColon(normalized);
        } else if (upper.startsWith("IMEI:")) {
            imei = afterColon(normalized);
        } else if (upper.contains("SIMCOM") && vendor == null) {
            vendor = "SIMCom";
        } else if (upper.contains("SIM8262") && model == null) {
            model = normalized;
        }
    }

    @Override
    public String getIdentitySummary() {
        StringBuilder sb = new StringBuilder();
        append(sb, "Vendor", vendor);
        append(sb, "Model", model);
        append(sb, "Revision", revision);
        append(sb, "IMEI", imei);
        return sb.length() == 0 ? "(modem identity not yet captured)" : sb.toString();
    }

    // ---- Provisioning vs routine startup -----------------------------------

    /**
     * Enable STK and set decoded output format, then reboot the modem so the
     * setting takes effect. Intended to run only once per device; subsequent
     * ATClient starts should find STK already enabled.
     *
     * Note: AT+CFUN=1,1 reboots the modem and the AT port disappears briefly.
     * The core is responsible for waiting for the port to come back.
     */
    @Override
    public void provisionSTK(ATCommandSender sender) {
        sender.send("AT+STK=1");
        sender.send("AT+STKFMT=0");
        sender.send("AT+CFUN=1,1");
    }

    /**
     * We need both STK=1 and STKFMT=0 to be active. The checking itself requires
     * one round-trip but has no side effects on the STK state machine.
     */
    @Override
    public boolean isProvisioningRequired(ATCommandSender sender) {
        // Best-effort: if either check fails, assume provisioning is needed.
        // The core can call provisionSTK() and reboot once.
        boolean stkOn = sender.send("AT+STK?", "+STK: 1");
        boolean decoded = sender.send("AT+STKFMT?", "+STKFMT: 0");
        return !(stkOn && decoded);
    }

    @Override
    public void sendStartupCommands(ATCommandSender sender) {
        // No STK-mode toggles here; only read-only introspection.
        sender.send("AT+CNMP?");     // log current network-mode preference
        sender.send("AT+CGDCONT?");  // APN contexts for diagnostics
    }

    /**
     * SIM8262E-M2 is a USB CDC virtual serial port on Qualcomm X62: commands are framed
     * atomically by the kernel driver and the modem responds within tens of ms. 50 ms
     * polling is responsive without wasting CPU. No pre send sleep is needed.
     */
    @Override public int getSerialPollIntervalMillis() { return 50; }
    @Override public int getSerialSendPreSleepMillis() { return 0; }

    // ---- Helpers ------------------------------------------------------------

    private static String afterColon(String line) {
        int idx = line.indexOf(':');
        return (idx < 0) ? line.trim() : line.substring(idx + 1).trim();
    }

    private static byte[] hexToByte(String hex) {
        int len = hex.length();
        byte[] out = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            out[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                    + Character.digit(hex.charAt(i + 1), 16));
        }
        return out;
    }

    private static void append(StringBuilder sb, String key, String value) {
        if (value == null || value.isEmpty()) return;
        if (sb.length() > 0) sb.append(", ");
        sb.append(key).append('=').append(value);
    }
}
