package com.swisscom.atclient;

import java.util.Map;

/**
 * Modem specific behavior abstraction for the ATClient.
 *
 * This branch ships only the SIMCom SIM8262E-M2 5G HAT driver. The interface is
 * kept so a future modem family can be added without changing the core; every
 * call site in ATresponder is written against ModemDriver, not against the
 * concrete implementation.
 *
 * Current implementations:
 *   - {@link SIMComSIM8262Driver} - SIMCom SIM8262E-M2 (AT+STK=1, +STIN URCs,
 *     AT+STGI= / AT+STGR= commands, plaintext PIN format, AT+CPSI? for extended
 *     signal, AT+CNMP=<code> for RAT selection, AT+C5GREG? for 5G registration).
 */
public interface ModemDriver {

    // ---- Identification -----------------------------------------------------

    /** Human readable vendor name. Used in log summaries. */
    String getVendorName();

    /** Short family label used in diagnostic summaries (e.g. "SIM8262E-M2"). */
    String getModemFamilyLabel();

    /** Config keyed port name pattern for auto detection on Linux. */
    String getPortNameHintLinux();

    /** Default baud rate expected by the modem's AT command port. */
    int getDefaultBaudRate();

    // ---- STK proactive command detection ------------------------------------

    /** True if this line looks like a STK proactive command URC for this modem. */
    boolean isSTKProactiveURC(String line);

    /**
     * True if the line is the STK state change URC variant if the modem has a
     * separate one. Drivers whose modem emits a single URC kind return the same
     * result as {@link #isSTKProactiveURC(String)}.
     */
    boolean isSTKStateURC(String line);

    /** Parse the proactive command type out of the URC. */
    int parseSTKCommandType(String urcLine);

    /** Whether the URC format requires an explicit acknowledgement. */
    boolean requiresAcknowledgement(String urcLine);

    // ---- STK info retrieval -------------------------------------------------

    /** Command to fetch proactive command details (e.g. "AT+STGI=21"). */
    String buildGetInfoCommand(int cmdType);

    /** Does this line look like the STGI response for this modem (e.g. "+STGI:")? */
    boolean isSTKInfoResponse(String line);

    /**
     * Extract the decoded display text from a single STGI response line.
     * Returns null if no text field is decodable from this specific line.
     */
    String extractDisplayText(String stgiLine);

    // ---- STK Terminal Response actions --------------------------------------

    /** Acknowledge a DISPLAY TEXT proactive command (OK / continue). */
    void ackDisplayText(ATCommandSender sender, int cmdType);

    /** User cancelled a proactive command (Terminal Response "user cancel"). */
    void cancelSession(ATCommandSender sender, int cmdType);

    /** No response before deadline (Terminal Response "timeout"). */
    void sessionTimeout(ATCommandSender sender, int cmdType);

    /** Submit user input (PIN) for a GET INPUT proactive command. */
    void submitInput(ATCommandSender sender, int cmdType, String text);

    /** Select an item from a SELECT ITEM / SET UP MENU proactive command. */
    void selectMenuItem(ATCommandSender sender, int cmdType, int itemId);

    /** Generic positive acknowledgement for proactive commands that only need OK. */
    void acknowledgeProactive(ATCommandSender sender, int cmdType);

    // ---- STK command type semantic mapping ----------------------------------

    int getSendMessageType();
    int getPlayToneType();
    int getDisplayTextType();
    int getGetInputType();
    int getSelectItemType();
    int getSetUpMenuType();

    /** Return the proactive command type that signals "applet back to main menu".
     *  Return -1 if the modem does not emit a distinct event for this. */
    int getReturnToMainType();

    /** Return the proactive command type for "SIM lost / reset". Return -1 if none. */
    int getSimLostType();

    // ---- PIN encoding --------------------------------------------------------

    /** Format a plain ASCII PIN string for the modem's GET INPUT response. */
    String formatPinForResponse(String plainPin);

    // ---- Signal / network probes --------------------------------------------

    /** Command to retrieve extended signal quality, or null if not supported. */
    String buildExtendedSignalCommand();

    /** Command to retrieve serving cell diagnostic info, or null if not supported. */
    String buildCellInfoCommand();

    /** Build the AT command to request a RAT change. */
    String buildRATSelectionCommand(String mode);

    /** Does this modem support 5G NR registration reporting (+C5GREG)? */
    boolean supports5GRegistration();

    /** Command to query 5G registration, or null if unsupported. */
    String build5GRegistrationQuery();

    // ---- Startup readiness profile -----------------------------------------

    /** List of AT commands issued during startup to gather readiness signals. */
    String[] getStartupReadinessCommands();

    /** Driver decides when the modem is "ready" based on collected boolean flags. */
    boolean isStartupReady(Map<String, Boolean> flags);

    /** CSQ threshold below which idle radio is considered degraded. */
    int getDegradedSignalThreshold();

    /** Command to nudge the modem into re acquiring a cell (radio recovery). */
    String buildRadioRecoveryCommand();

    // ---- Modem identity ------------------------------------------------------

    /** Consume diagnostic response lines (ATI, +CGMI, etc.) to accumulate identity. */
    void updateIdentityFromLine(String line);

    /** Human readable identity summary for log. */
    String getIdentitySummary();

    // ---- Provisioning vs runtime startup ------------------------------------

    /** Issue the one shot provisioning sequence; intended to run only when needed. */
    void provisionSTK(ATCommandSender sender);

    /** Check whether STK is already provisioned. */
    boolean isProvisioningRequired(ATCommandSender sender);

    /** Routine startup commands on every ATClient launch (no STK mode toggles). */
    void sendStartupCommands(ATCommandSender sender);

    // ---- Serial timing tuning -----------------------------------------------

    /** Interval in milliseconds between buffered reader polls in the receive loops. */
    int getSerialPollIntervalMillis();

    /** Sleep before issuing each AT command, in milliseconds. Return 0 to skip. */
    int getSerialSendPreSleepMillis();
}
