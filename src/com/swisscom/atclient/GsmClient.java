/**
 * @author <a href="mailto:Philipp.Haupt@swisscom.com">Philipp Haupt</a>
 */

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

		if (args.length == 2 && args[1].toUpperCase().equals("ER")) {
			atClient = new ATresponder(args[0], (byte) 1);
			new Thread(atClient).start();
		} 
		
		else if (args.length == 2 && args[1].toUpperCase().equals("AR")) {
			atClient = new ATresponder(args[0], (byte) 2);
			new Thread(atClient).start();
		} 
		
		else if (args.length == 2 && args[1].toUpperCase().equals("UE")) {
			atClient = new ATresponder(args[0], (byte) 0);
			new Thread(atClient).start();
		} 
		
		else {
			System.out
					.println("\n*** GSM AT Client ***\n\n"
							+ "Usage: GsmClient <PORT> <CMD>\n\n"
							+ "<PORT>\tSerial Port\n"
							+ "<CMD>\tList of supported commands:\n"
							+ "\tER\tSwitch to Explicit Response (ER) and shutdown\n"
							+ "\tAR\tSwitch to Automatic Response (AR, Factory Default) and shutdown\n"
							+ "\tUE\tRun continues Alauda User Emulation (UE) in Explicit Response Mode\n"
							);

			System.exit(0);
		}
		
	}
}
