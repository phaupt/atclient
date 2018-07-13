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
		 */
		
		ATresponder atClient = null;

		if (args.length == 2 && args[0].toUpperCase().equals("ER")) {
			atClient = new ATresponder((byte) 1, args[1]);
			new Thread(atClient).start();
		}

		else if (args.length == 2 && args[0].toUpperCase().equals("AR")) {
			atClient = new ATresponder((byte) 2, args[1]);
			new Thread(atClient).start();
		}

		else if (args.length == 0) {
			atClient = new ATresponder((byte) 0);
			new Thread(atClient).start();
		}
		
		else if (args.length == 1 && args[0].toUpperCase().equals("--HELP")) {
			System.out
					.println("\n*** GSM AT Client ***\n\n"
							+ "Usage: GsmClient [<MODE>] [<PORT>]\n\n"
							+ "<PORT>\tSerial Port\n"
							+ "<MODE>\tSwitch operation mode:\n"
							+ "\tER\tSwitch to Explicit Response (ER) and shutdown. Requires <PORT> argument.\n"
							+ "\tAR\tSwitch to Automatic Response (AR) and shutdown. Requires <PORT> argument.\n"
							+ "\n"
							+ "Default: User emulation with automatic port detection. Requires prior switch to ER operation mode.\n"
							);

			System.exit(0);
		}
		
	}
}
