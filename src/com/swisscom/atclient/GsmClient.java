package com.swisscom.atclient;

import com.swisscom.atclient.ATresponder;

public class GsmClient {

	public static void main(String[] args) throws InterruptedException {
		
		/**
		 * Any of the UI Text Content (DTBD/ReceiptMsg) may contain following keywords to invoke specific Applet/Terminal Responses.
		 * 
		 * 'CANCEL'     : TerminalResponse '16' - Proactive SIM session terminated by user
		 * 
		 * 'STKTIMEOUT' : TerminalResponse '18' - No response from user
		 * 
		 * 'BLOCKPIN'   : Wrong PIN input. Mobile ID PIN will be blocked.
		 * 
		 */
		
		ATresponder atClient = null;

		if (args.length == 2 && args[0].toUpperCase().equals("ER")) {
			atClient = new ATresponder((byte) 1);
			new Thread(atClient).start();
		}

		else if (args.length == 2 && args[0].toUpperCase().equals("AR")) {
			atClient = new ATresponder((byte) 2);
			new Thread(atClient).start();
		}

		else if (args.length == 0) {
			atClient = new ATresponder((byte) 0);
			new Thread(atClient).start();
		}
		
		else if (args.length == 1 && args[0].toUpperCase().equals("--HELP")) {
			System.out
					.println("\n*** GSM AT Client ***\n\n"
							+ "Usage: GsmClient [<MODE>]\n\n"
							+ "<MODE>\tSwitch operation mode:\n"
							+ "\tER\tSwitch to Explicit Response (ER) and shutdown.\n"
							+ "\tAR\tSwitch to Automatic Response (AR) and shutdown.\n"
							+ "\n"
							+ "If no <MODE> argument found: Run user emulation with automatic serial port detection (ER operation mode only)\n"
							+ "\n"
							+ "Optional system properties with example values:\n"
							+ "\t-Dlog.file=GsmClient.log               # Application log file\n"
							+ "\t-Dlog4j.configurationFile=log4j2.xml   # Location of log4j.xml configuration file\n"
							+ "\t-DtargetMsisdn=+41791234567            # Target phone number to forward text sms content\n"
							+ "\t-Dserial.port=COM16                    # Select specific serial port (no automatic port detection)\n"
							);

			System.exit(0);
		}
		
	}
}
