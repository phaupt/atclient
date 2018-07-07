/**
 *
 * @author <a href="mailto:Philipp.Haupt@swisscom.com">Philipp Haupt</a>
 * 
 */

package com.swisscom.atclient;

public class GsmClient {

	public static void main(String[] args) throws InterruptedException {
		
		/**
		 * Any of the UI Text Content (DTBD/ReceiptMsg) may contain following keywords to invoke specific Applet/Terminal Responses.
		 * 
		 * 'CANCEL'     : TerminalResponse '16'  - Proactive SIM session terminated by user
		 * 
		 * 'STKTIMEOUT' : TerminalResponse '18' - No response from user
		 * 
		 * 'RESET'      : A reset of the GSM Module will be invoked (AT^SMSO), so the current STK interaction will not be answered at all
		 * 
		 */
		
		/*
		 * 
		 * MU Provisioning / Use 20.sd2 content for "..." below:
		 * 
		 * echo "..." | awk ' { print "mutk -activatesimcard -timsi " $2 " -msisdn " $1 "\nmutk -createmobileuser -msisdn " $1 " -customerid B2C_Customers -attrlist CertificateProfile http://mid.swisscom.ch/MID/v1/CertProfileAnonymous1 signatureprofiles http://mid.swisscom.ch/MID/v1/AuthProfile1"}'
		 * 
		 */
		
		ATresponder atClient = null;

		if (args.length == 2 && args[1].toUpperCase().equals("ER")) {
			atClient = new ATresponder(args[0], (byte) 1, 0);
			new Thread(atClient).start();
		} 
		
		else if (args.length == 2 && args[1].toUpperCase().equals("AR")) {
			atClient = new ATresponder(args[0], (byte) 2, 0);
			new Thread(atClient).start();
		} 
		
		else if (args.length == 2 && args[1].toUpperCase().equals("UE")) {
			atClient = new ATresponder(args[0], (byte) 0, 0);
			new Thread(atClient).start();
		} 
		
		else if (args.length == 3 && args[1].toUpperCase().equals("UE")) {
			try {
				atClient = new ATresponder(args[0], (byte) 0, Integer.parseInt(args[2]));
				new Thread(atClient).start();
			} catch (NumberFormatException e) {
				System.err.println("<PIN> must be of type integer");
				System.exit(0);
			}
		}
		
		else {
			System.out
					.println("\n*** HCP HIT GSM/GPRS Modem Responder v1.08 ***\n\n"
							+ "Usage: GsmClient <PORT> <CMD> [PIN]\n\n"
							+ "<PORT>\tSerial Port\n"
							+ "<CMD>\tList of supported commands:\n"
							+ "\tER\tSwitch to Explicit Response (ER) and reboot\n"
							+ "\tAR\tSwitch to Automatic Response (AR, Factory Default) and reboot\n"
							+ "\tUE\tRun Alauda User Emulation (UE) in Explicit Response Mode\n"
							+ "<PIN>\tSIM PIN (optional, only required if PIN is enabled)\n"
							+ "\n"
							+ "Note that before to start this client, the HIT55 must be connected for at least 30s.\n");

			System.exit(0);
		}
		
	}
}
