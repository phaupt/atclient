package com.swisscom.atclient;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@link ModemDriver} implementation for the Cinterion / Thales PLS8-E HIT U4.
 *
 * Preserves the historical behavior implemented inline in {@link ATresponder}:
 *   - STK activation via AT^SSTA=1,1 (ER mode; UCS2 decoded)
 *   - ^SSTR / ^SSTN URCs from the modem
 *   - Proactive-command details via AT^SSTGI=<cmdType>
 *   - Terminal responses via AT^SSTR=<cmdType>,<code>[,...]
 *   - GET INPUT response with PIN encoded as UCS-2 hex (e.g. "003100320033003400350036"
 *     for plain "123456")
 *   - Extended signal via AT+CESQ, serving-cell via AT^SMONI, RAT selection
 *     via AT+COPS=0,2,00000,<rat>
 *
 * Command-type numbers (proactive commands):
 *   19 SEND MESSAGE | 32 PLAY TONE | 33 DISPLAY TEXT | 35 GET INPUT
 *   36 SELECT ITEM   | 37 SET UP MENU | 254 RETURN TO MAIN | 255 SIM LOST
 */
public final class CinterionPLS8Driver implements ModemDriver {

    // Proactive-command types emitted by PLS8-E
    public static final int CMD_SEND_MESSAGE   = 19;
    public static final int CMD_PLAY_TONE      = 32;
    public static final int CMD_DISPLAY_TEXT   = 33;
    public static final int CMD_GET_INPUT      = 35;
    public static final int CMD_SELECT_ITEM    = 36;
    public static final int CMD_SET_UP_MENU    = 37;
    public static final int CMD_RETURN_MAIN    = 254;
    public static final int CMD_SIM_LOST       = 255;

    // Terminal-response result codes
    public static final int RESULT_OK            = 0;
    public static final int RESULT_USER_CANCEL   = 16;
    public static final int RESULT_USER_TIMEOUT  = 18;

    private static final Pattern SSTR_PATTERN =
            Pattern.compile("\\^SSTR:\\s*(\\d+)\\s*,\\s*(\\d+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern SSTN_PATTERN =
            Pattern.compile("\\^SSTN:\\s*(\\d+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern SSTGI_TEXT_PATTERN =
            Pattern.compile(",\"([0-9A-Fa-f]+)\",");

    private String vendor;       // "CINTERION" or "THALES"
    private String model;        // e.g. "PLS8-E"
    private String revision;     // e.g. "REV ..."
    private String aRevision;    // e.g. "A-REV ..."

    // ---- Identification -----------------------------------------------------

    @Override
    public String getVendorName() {
        return (vendor != null) ? vendor : "Cinterion";
    }

    @Override
    public String getModemFamilyLabel() {
        return (model != null) ? model : "PLS8";
    }

    @Override
    public String getPortNameHintLinux() {
        return "Gemalto";
    }

    @Override
    public int getDefaultBaudRate() {
        return 9600;
    }

    // ---- STK URC detection --------------------------------------------------

    @Override
    public boolean isSTKProactiveURC(String line) {
        if (line == null) return false;
        String u = line.trim().toUpperCase();
        return u.startsWith("^SSTR:");
    }

    @Override
    public boolean isSTKStateURC(String line) {
        if (line == null) return false;
        String u = line.trim().toUpperCase();
        return u.startsWith("^SSTN:");
    }

    @Override
    public int parseSTKCommandType(String urcLine) {
        if (urcLine == null) return -1;
        Matcher mSstr = SSTR_PATTERN.matcher(urcLine);
        if (mSstr.find()) {
            try { return Integer.parseInt(mSstr.group(2)); } catch (NumberFormatException ignored) {}
        }
        Matcher mSstn = SSTN_PATTERN.matcher(urcLine);
        if (mSstn.find()) {
            try { return Integer.parseInt(mSstn.group(1)); } catch (NumberFormatException ignored) {}
        }
        return -1;
    }

    /**
     * PLS8 ^SSTR state field:
     *   0 = PAC  (Proactive Command, requires ack via AT^SSTR=<cmd>,<code>)
     *   1 = WAIT (no ack required)
     * ^SSTN URCs always require handling.
     */
    @Override
    public boolean requiresAcknowledgement(String urcLine) {
        if (urcLine == null) return false;
        Matcher m = SSTR_PATTERN.matcher(urcLine);
        if (m.find()) {
            try { return Integer.parseInt(m.group(1)) == 0; } catch (NumberFormatException ignored) {}
            return true;
        }
        // ^SSTN URCs: always handle
        return isSTKStateURC(urcLine);
    }

    // ---- STK info retrieval -------------------------------------------------

    @Override
    public String buildGetInfoCommand(int cmdType) {
        return "AT^SSTGI=" + cmdType;
    }

    @Override
    public boolean isSTKInfoResponse(String line) {
        return line != null && line.trim().toUpperCase().startsWith("^SSTGI:");
    }

    @Override
    public String extractDisplayText(String stgiLine) {
        if (stgiLine == null || !stgiLine.contains("^SSTGI:")) return null;
        int startIdx = stgiLine.indexOf(",\"");
        int endIdx = (startIdx >= 0) ? stgiLine.indexOf("\",", startIdx + 2) : -1;
        if (startIdx < 0 || endIdx <= startIdx + 2) return null;
        String ucs2Hex = stgiLine.substring(startIdx + 2, endIdx).trim();
        if (ucs2Hex.isEmpty()) return null;
        try {
            return new String(hexToByte(ucs2Hex), StandardCharsets.UTF_16);
        } catch (RuntimeException e) {
            return null;
        }
    }

    // ---- STK terminal-response actions --------------------------------------

    @Override
    public void ackDisplayText(ATCommandSender sender, int cmdType) {
        sender.send("AT^SSTR=" + cmdType + "," + RESULT_OK);
    }

    @Override
    public void cancelSession(ATCommandSender sender, int cmdType) {
        sender.send("AT^SSTR=" + cmdType + "," + RESULT_USER_CANCEL);
    }

    @Override
    public void sessionTimeout(ATCommandSender sender, int cmdType) {
        sender.send("AT^SSTR=" + cmdType + "," + RESULT_USER_TIMEOUT);
    }

    @Override
    public void submitInput(ATCommandSender sender, int cmdType, String text) {
        // PLS8 GET INPUT expects: AT^SSTR=<cmd>,0,,<ucs2hex>
        String ucs2Hex = isUcs2HexLike(text) ? text : formatPinForResponse(text);
        sender.send("AT^SSTR=" + cmdType + "," + RESULT_OK + ",," + ucs2Hex);
    }

    @Override
    public void selectMenuItem(ATCommandSender sender, int cmdType, int itemId) {
        sender.send("AT^SSTR=" + cmdType + "," + RESULT_OK + "," + itemId);
    }

    @Override
    public void acknowledgeProactive(ATCommandSender sender, int cmdType) {
        sender.send("AT^SSTR=" + cmdType + "," + RESULT_OK);
    }

    // ---- Command-type semantic mapping --------------------------------------

    @Override public int getSendMessageType()  { return CMD_SEND_MESSAGE; }
    @Override public int getPlayToneType()     { return CMD_PLAY_TONE; }
    @Override public int getDisplayTextType()  { return CMD_DISPLAY_TEXT; }
    @Override public int getGetInputType()     { return CMD_GET_INPUT; }
    @Override public int getSelectItemType()   { return CMD_SELECT_ITEM; }
    @Override public int getSetUpMenuType()    { return CMD_SET_UP_MENU; }
    @Override public int getReturnToMainType() { return CMD_RETURN_MAIN; }
    @Override public int getSimLostType()      { return CMD_SIM_LOST; }

    // ---- PIN encoding (UCS-2 hex) ------------------------------------------

    @Override
    public String formatPinForResponse(String plainPin) {
        if (plainPin == null) return "";
        StringBuilder sb = new StringBuilder(plainPin.length() * 4);
        for (int i = 0; i < plainPin.length(); i++) {
            sb.append(String.format("%04X", (int) plainPin.charAt(i)));
        }
        return sb.toString();
    }

    // ---- Signal / network ---------------------------------------------------

    @Override
    public String buildExtendedSignalCommand() {
        return "AT+CESQ";
    }

    @Override
    public String buildCellInfoCommand() {
        return "AT^SMONI";
    }

    @Override
    public String buildRATSelectionCommand(String mode) {
        // Matches existing ATresponder.verifyRAT() behavior: AT+COPS=0,2,00000,<rat>
        return "AT+COPS=0,2,00000," + mode;
    }

    @Override
    public boolean supports5GRegistration() {
        return false;
    }

    @Override
    public String build5GRegistrationQuery() {
        return null;
    }

    // ---- Startup readiness profile -----------------------------------------

    @Override
    public String[] getStartupReadinessCommands() {
        return new String[] { "AT+CPIN?", "AT+CREG?", "AT+CEREG?", "AT+COPS?", "AT+CSQ" };
    }

    @Override
    public boolean isStartupReady(Map<String, Boolean> flags) {
        if (flags == null) return false;
        boolean sim = Boolean.TRUE.equals(flags.get("simReady"));
        boolean reg = Boolean.TRUE.equals(flags.get("registrationReady"));
        boolean net = Boolean.TRUE.equals(flags.get("networkReady"));
        boolean sig = Boolean.TRUE.equals(flags.get("signalReady"));
        return sim && reg && net && sig;
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
        if (upper.equals("CINTERION") || upper.equals("THALES")) {
            vendor = normalized;
        } else if (upper.startsWith("PLS")) {
            model = normalized;
        } else if (upper.startsWith("REVISION:") || upper.startsWith("REV")) {
            revision = normalized;
        } else if (upper.startsWith("A-REVISION:") || upper.startsWith("A-REV")) {
            aRevision = normalized;
        }
    }

    @Override
    public String getIdentitySummary() {
        StringBuilder sb = new StringBuilder();
        append(sb, "Vendor", vendor);
        append(sb, "Model", model);
        append(sb, "Revision", revision);
        append(sb, "A-Revision", aRevision);
        return sb.length() == 0 ? "(modem identity not yet captured)" : sb.toString();
    }

    // ---- Provisioning vs routine startup -----------------------------------

    @Override
    public void provisionSTK(ATCommandSender sender) {
        // ER mode: enable STK with UCS2 decoded output
        sender.send("AT^SSTA=1,1");
    }

    @Override
    public boolean isProvisioningRequired(ATCommandSender sender) {
        // PLS8 ER-mode provisioning is an administrator-managed one-shot; the
        // core decides whether to call provisionSTK() based on its factory-mode
        // dispatch, not on this flag. Drivers that need on-start provisioning
        // check (e.g. SIMCom AT+STK?) will return true / false here accordingly.
        return false;
    }

    @Override
    public void sendStartupCommands(ATCommandSender sender) {
        // Routine read-only introspection; must not toggle STK or require reboot.
        sender.send("AT^SSTR?");       // re-arm listener
        sender.send("AT^SSRVSET?");    // usbcomp + srvmap diagnostics
    }

    /** PLS8-E behaviour preserved: keep the conservative 150 ms poll + 150 ms pre-send
     *  sleep the legacy code shipped with. Changing these would alter timings for
     *  existing production deployments, so the PLS8 driver stays byte-for-byte compatible. */
    @Override public int getSerialPollIntervalMillis() { return 150; }
    @Override public int getSerialSendPreSleepMillis() { return 150; }

    // ---- Helpers ------------------------------------------------------------

    private static boolean isUcs2HexLike(String s) {
        if (s == null || s.isEmpty() || (s.length() % 4) != 0) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
            if (!hex) return false;
        }
        return true;
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
