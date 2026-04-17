package com.swisscom.atclient;

/**
 * Callback used by modem drivers to issue AT commands back through ATresponder's
 * serial send() machinery. Keeps the driver code free of direct PrintStream/BufferedReader
 * ownership and lets the core keep its heartbeat, response matching, and recovery hints.
 *
 * The three overloads mirror the core send() variants so drivers can:
 *   - fire-and-forget commands (send(cmd)) — returns true if OK/expected arrived
 *   - require a specific expected response substring (send(cmd, expected))
 *   - override the default timeout and mark whether a failure should feed into
 *     recovery diagnostics (send(cmd, expected, timeout, recoveryHint))
 */
public interface ATCommandSender {

    /** Send without an expected-response check; use ATresponder's default timeout. */
    boolean send(String cmd);

    /** Send and wait for expected substring (case-insensitive) within the default timeout. */
    boolean send(String cmd, String expectedResponse);

    /**
     * Full-control variant.
     *
     * @param cmd              AT command to issue
     * @param expectedResponse expected substring in the final result (e.g. "OK"); null means "match any final result"
     * @param timeoutMillis    how long to wait for the final result
     * @param recoveryHint     if true and the command does not complete successfully, the core may
     *                         feed the failure into its degraded-radio / radio-recovery pipeline
     * @return true if the expected response was observed in time
     */
    boolean send(String cmd, String expectedResponse, long timeoutMillis, boolean recoveryHint);
}
