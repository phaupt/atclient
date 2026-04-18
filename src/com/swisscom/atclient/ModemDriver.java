package com.swisscom.atclient;

import java.util.Map;

/**
 * Modem-specific behavior abstraction for the ATClient.
 *
 * The core {@link ATresponder} orchestrates serial I/O, heartbeat, watchdog,
 * keyword parsing, and recovery. Everything that differs between modem families
 * (URC prefixes, STK command-type numbers, proactive-command response syntax,
 * signal/network probes, RAT handling) lives behind this interface.
 *
 * Current implementations:
 *   - {@link CinterionPLS8Driver} — Cinterion/Thales PLS8-E HIT U4 (AT^SSTA,
 *     ^SSTR / ^SSTN URCs, AT^SSTGI=, AT^SSTR= terminal responses, UCS2-hex PIN format)
 *   - {@link SIMComSIM8262Driver} — SIMCom SIM8262E-M2 (AT+STK=1, +STIN URCs,
 *     AT+STGI=, AT+STGR= terminal responses, plaintext PIN format)
 *
 * Log-marker compatibility across drivers is intentional: both drivers emit the
 * same STK019/033/035/037-style markers where the underlying command types match
 * semantically, so post-deployment log tooling does not need to change.
 */
public interface ModemDriver {

    // ---- Identification -----------------------------------------------------

    /** Human-readable vendor (e.g. "Cinterion" or "SIMCom"). Used in log summaries. */
    String getVendorName();

    /** Short family label used in diagnostic summaries (e.g. "PLS8-E", "SIM8262E-M2"). */
    String getModemFamilyLabel();

    /**
     * Config-keyed port-name pattern for auto-detection on Linux
     * (e.g. "Gemalto" matches Cinterion PLS8 ttyACM; "SimTech" matches SIMCom 5G HAT).
     */
    String getPortNameHintLinux();

    /** Default baud rate expected by the modem's AT command port. */
    int getDefaultBaudRate();

    // ---- STK proactive-command detection ------------------------------------

    /**
     * @return true if this line looks like an STK proactive-command URC for this
     *         modem (e.g. "^SSTR: " / "^SSTN: " on PLS8-E, "+STIN: " on SIMCom).
     */
    boolean isSTKProactiveURC(String line);

    /**
     * @return true if the line is the STK state-change URC variant if the modem
     *         has one (PLS8-E has a separate ^SSTN for state changes). Drivers
     *         whose modem only emits a single URC kind should simply return the
     *         same result as {@link #isSTKProactiveURC(String)}.
     */
    boolean isSTKStateURC(String line);

    /** Parse the proactive-command type (e.g. 33 for DISPLAY TEXT) out of the URC. */
    int parseSTKCommandType(String urcLine);

    /**
     * Some URC formats (PLS8 ^SSTR) carry an explicit PAC/WAIT acknowledgement
     * flag; others always require acknowledgement. Drivers return whatever their
     * URC format indicates.
     */
    boolean requiresAcknowledgement(String urcLine);

    // ---- STK Info retrieval --------------------------------------------------

    /** Command to fetch proactive-command details (e.g. "AT^SSTGI=33" or "AT+STGI=21"). */
    String buildGetInfoCommand(int cmdType);

    /** Does this line look like the STGI response for this modem (e.g. "^SSTGI:" / "+STGI:")? */
    boolean isSTKInfoResponse(String line);

    /**
     * Extract the decoded display text (UTF-16 / plain) from a single STGI response
     * line. If the driver cannot decode a text field from this specific line,
     * return null (the caller logs only non-null results).
     */
    String extractDisplayText(String stgiLine);

    // ---- STK Terminal Response actions --------------------------------------
    //
    // Drivers issue the exact AT command via the provided sender. This decouples
    // the action from the wire format (ACK codes, UCS2 vs plaintext, etc).

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

    // ---- STK command-type semantic mapping ---------------------------------
    //
    // Each driver maps the generic STK semantics to its modem's proactive-command
    // type numbers. PLS8-E uses 19/32/33/35/36/37/254/255. SIMCom typically
    // uses 13/20/21/23/24/25 and has no distinct "return to main menu" type
    // (SIMCom re-emits SET UP MENU instead — drivers can map getReturnToMainType
    // to the same value as getSetUpMenuType in that case).

    int getSendMessageType();
    int getPlayToneType();
    int getDisplayTextType();
    int getGetInputType();
    int getSelectItemType();
    int getSetUpMenuType();

    /** Return the proactive-command type that signals "applet back to main menu".
     *  Return -1 if the modem does not emit a distinct event for this. */
    int getReturnToMainType();

    /** Return the proactive-command type for "SIM lost / reset". Return -1 if none. */
    int getSimLostType();

    // ---- PIN encoding --------------------------------------------------------

    /**
     * Format a plain-ASCII PIN string for the modem's GET INPUT response.
     * PLS8-E expects UCS2-hex ("003100320033003400350036" for "123456").
     * SIMCom SIM8262E in decoded mode expects the plaintext PIN as-is.
     */
    String formatPinForResponse(String plainPin);

    // ---- Signal / network probes --------------------------------------------

    /** Command to retrieve extended signal quality, or null if not supported. */
    String buildExtendedSignalCommand();

    /** Command to retrieve serving-cell diagnostic info, or null if not supported. */
    String buildCellInfoCommand();

    /** Build the AT command to request a RAT change according to the driver's mode-code convention. */
    String buildRATSelectionCommand(String mode);

    /** Does this modem support 5G NR registration reporting (+C5GREG)? */
    boolean supports5GRegistration();

    /** Command to query 5G registration, or null if unsupported. */
    String build5GRegistrationQuery();

    // ---- Startup-readiness profile -----------------------------------------

    /**
     * List of AT commands the core should issue during startup to gather all
     * the signals needed by {@link #isStartupReady(Map)}.
     */
    String[] getStartupReadinessCommands();

    /** Driver decides when the modem is "ready" based on collected boolean flags. */
    boolean isStartupReady(Map<String, Boolean> flags);

    /** CSQ threshold below which idle radio is considered degraded. */
    int getDegradedSignalThreshold();

    /** Command to nudge the modem into re-acquiring a cell (radio recovery). */
    String buildRadioRecoveryCommand();

    // ---- Modem identity ------------------------------------------------------

    /**
     * Consume diagnostic response lines (ATI, +CGMI, etc.) and let the driver
     * accumulate vendor/model/revision state. Called from the core's line
     * dispatcher during startup.
     */
    void updateIdentityFromLine(String line);

    /** Human-readable identity summary for log. */
    String getIdentitySummary();

    // ---- Provisioning vs runtime-startup ------------------------------------

    /**
     * Issue the one-shot provisioning sequence (enable STK, set decoded format,
     * full modem restart if required). Intended to run only when the driver's
     * {@link #isProvisioningRequired(ATCommandSender)} check indicates that state
     * is missing. Never call from the routine startup path.
     */
    void provisionSTK(ATCommandSender sender);

    /** Check whether STK is already enabled + decoded mode set. */
    boolean isProvisioningRequired(ATCommandSender sender);

    /** Routine startup commands issued on every ATClient launch (no STK-mode toggles). */
    void sendStartupCommands(ATCommandSender sender);

    // ---- Serial timing tuning -----------------------------------------------

    /**
     * Interval in milliseconds between buffered-reader polls in the core's receive loops.
     * Shorter values reduce STK-response latency at the cost of CPU wake-ups. UART-based
     * modems (9600 baud) need around 150 ms for reliable framing; USB-CDC modems (virtual
     * serial over USB bulk transfer) can safely use around 50 ms.
     */
    int getSerialPollIntervalMillis();

    /**
     * Sleep before issuing each AT command to let the modem finish processing the previous
     * one. Necessary for slow UART-based firmware, typically unnecessary for USB-CDC where
     * commands are framed atomically by the kernel driver. Return 0 to skip.
     */
    int getSerialSendPreSleepMillis();
}
