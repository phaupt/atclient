/**
 * @author <a href="mailto:philipp.haupt@swisscom.com">Philipp Haupt</a>
 */

package com.swisscom.atclient;

import gnu.io.CommPortIdentifier;
import gnu.io.NoSuchPortException;
import gnu.io.PortInUseException;
import gnu.io.SerialPort;
import gnu.io.UnsupportedCommOperationException;
import java.io.*;
import org.apache.log4j.*;

/*
 * send("AT+COPS?", "OK"); // Provider
 * send("AT+CSQ", "OK"); // Signal Strength
 * send("AT+WS46=?", "OK"); // Wireless Data Service (WDS)
 *     12 GSM Digital Cellular Systems (GERAN only) --> 2G
 *     22 UTRAN only --> 3G
 *     25 3GPP Systems (GERAN, UTRAN and E-UTRAN) --> 4G/LTE
 *     28 E-UTRAN only
 *     29 GERAN and UTRAN
 *     30 GERAN and E-UTRAN
 *     31 UTRAN and E-UTRAN
 * send("AT+WS46?", "OK"); // Selected Wireless Data Service (WDS)
 * send("AT+WS46=12", "OK"); // Wireless Data Service (WDS)
 * send("AT^SMSO", "^SHUTDOWN"); // Restart
 * send("AT+WS46?", "OK"); // Selected Wireless Data Service (WDS)
 * send("AT+CIMI", "OK"); // IMSI
 * send("AT+CGSN", "OK"); // IMEI
 */

public class ATresponder extends Thread {
	private Logger log = Logger.getLogger(ATresponder.class);

	private int sleepMillis = 100;
	
	private SerialPort serPort;
	private BufferedReader buffReader;
	private PrintStream printStream;
	
	private int pin;
	
	// User specific behaviour for automated testing
	private volatile static boolean cancel;
	private volatile static boolean stk_timeout;
	private volatile static boolean reset;

	private boolean dtrFlag = true;
	private String serialport;
	private int baudrate = 230400; // Please check also your device settings
	private int databits = 8;
	private int stopbits = 1;
	private int parity = 0;
	
	private byte mode; // Switch: 1=ER, 2=AR
	
	private String[] stampString = new String[5];
	
	public volatile static boolean isAlive = true;

	public ATresponder(String serialport, byte mode, int pin) {
		Thread.currentThread().setName("CLIENT"); // Client Thread
		this.serialport = serialport;
		this.mode = mode;
		if (pin != 0)
			this.pin = pin;
	}
	
	public void attachShutDownHook() {
		Runtime.getRuntime().addShutdownHook(new Thread() {
			public void run() {
				Thread.currentThread().setName("SDHOOK");
				log.debug("Executing Shutdown Hook");
				System.out.println("Shutdown in progress...");
				isAlive = false; // will exit the while loop and terminate the application	
				try {
					Thread.sleep(2500); // Give some time to complete the socket closing
				} catch (InterruptedException e) {
				}
				closingPort(); // Double check if ports are closed
				System.out.println("Done.");
			}
		});
		log.debug("Attached Shutdown Hook");
	}
	
	public void run() {
		Thread.currentThread().setName("ATRESP");
		log.info("Application started...");

		attachShutDownHook();

		// WORKAROUND to avoid annoying Library Version output (of RXTX library) to System.out...
		PrintStream out = System.out;
		PrintStream silent = null;
		try {
			silent = new PrintStream(File.createTempFile("foo", "tmp"));
		} catch (Exception e) {
		} 
		if (silent != null)
			System.setOut(silent); // Silent System.out
		CommPortIdentifier pID = null;
		try {
			pID = CommPortIdentifier.getPortIdentifier(serialport);
			log.debug("Found Serial Port: " + pID.getName());
		} catch (NoSuchPortException e) {
			log.fatal("run() NoSuchPortException: ", e);
		}
		System.setOut(out); // Set output back to System.out
		
		if (pID != null){
			log.info("Trying to connect to '" + pID.getName() + "' ...");
			try {
				initSerialPort(pID);
				processAtLoop(pID);
			} catch (UnsupportedEncodingException e) {
				log.fatal("run() UnsupportedEncodingException: ", e);
			} catch (IOException e) {
				log.fatal("run() IOException: ", e);
			} catch (InterruptedException e) {
				log.fatal("run() InterruptedException: ", e);
			} catch (PortInUseException e) {
				log.fatal("run() PortInUseException: ", e);
			} catch (UnsupportedCommOperationException e) {
				log.fatal("run() UnsupportedCommOperationException: ", e);
			}
		} else {
			log.fatal("Serial Port '" + serialport + "' not found. Please check your Serial Port configuration.");
		}

		closingPort();
		log.info("Exiting Application");
	}

	private void initSerialPort(CommPortIdentifier pID) throws PortInUseException, UnsupportedCommOperationException, UnsupportedEncodingException, IOException {
		serPort = (SerialPort) pID.open(serialport, 0);
		serPort.setSerialPortParams(baudrate, databits, stopbits, parity);
		
		// Windows without received data returns if no data available.
		// Linux waits in endless loop when no data, so ReceiveTimeout should be configured
		// It works in Windows as well
		// WARNING: Seems like short timeouts can lead to IOException (Underlying input stream returned zero bytes)
		serPort.enableReceiveTimeout(500); // milliseconds
		
		serPort.setDTR(dtrFlag);

		buffReader = new BufferedReader(new InputStreamReader(serPort.getInputStream(), "UTF-8"));
		printStream = new PrintStream(serPort.getOutputStream(), true, "UTF-8");

		log.info("Connection successfully established.");
	}
	
	
	private void initialize(boolean arMode){
			// Turn off echo mode
			log.info("### Turn Off Echo Mode ###");
			send("ATE0", "ok");

			// Switch on verbose error messages
			log.info("### Switch On Verbose Error Messages ###");
			send("AT+CMEE=2", "ok");
			
			if (arMode){
				// Switch to Automatic Response (AR) and restart device
				log.info("### Switch to Automatic Response (AR) ###");
				send("AT^SSTA=0", "ok"); // Automatic Response Mode
			} else {
				// Switch to Explicit Response (ER) and restart device
				log.info("### Switch to Explicit Response (ER) ###");
				send("AT^SSTA=1,1", "ok"); // Explicit Response Mode UCS2
			}

			rebootAndExit();
	}
	
	public void rebootAndExit(){
		log.info("### Send SHUTDOWN Command ###");
		send("AT^SMSO", "^SHUTDOWN"); // Restart
		
		log.info("The HIT55 Module will now perform a reboot.");
		
		isAlive = false; // will exit the while loop and terminate the application	
	}

	private synchronized void processAtLoop(CommPortIdentifier pID) throws InterruptedException, UnsupportedEncodingException, PortInUseException, UnsupportedCommOperationException, IOException {

		if (mode == 1) {
			initialize(false); // ER
			return; // exit
		} else if (mode == 2) {
			initialize(true); // AR
			return; // exit
		}

		String rcvStr;
		int cmdType = 0;
		boolean ackCmdRequired = false;
		
		log.info("### Set SMS text mode ###");
		send("AT+CMGF=1", "OK");
		
		//log.info("### Activate the display of a URC on every received SMS ###");
		send("AT+CNMI=1,1", "OK", false);
		
		send("AT+COPS?", "OK", false); // Provider
		send("AT+CSQ", "OK", false); // Signal Strength
		send("AT+WS46=?", "OK", false); // Wireless Data Service (WDS)
		// * 12 GSM Digital Cellular Systems (GERAN only) --> 2G
		// * 22 UTRAN only --> 3G
		// * 25 3GPP Systems (GERAN, UTRAN and E-UTRAN) --> 4G/LTE
		// * 28 E-UTRAN only
		// * 29 GERAN and UTRAN
		// * 30 GERAN and E-UTRAN
		// * 31 UTRAN and E-UTRAN
		send("AT+CIMI", "OK", false); // IMSI
		send("AT+CGSN", "OK", false); // IMEI

		// Start endless loop...
		while (isAlive) {
			
			Thread.sleep(sleepMillis);

			send("AT^SSTR?");

			// Listening for incoming notifications (SIM->ME)
			try {
				while (isAlive && buffReader.ready() && (rcvStr = buffReader.readLine()) != null) {

					if (rcvStr != null && rcvStr.length() > 0) {
						
						/*
						 * Hide these content (status response):
						 * 2018-05-29 09:41:06,732 [ATRESP] DEBUG hit55.ATresponder - TX?: AT^SSTR?
						 * 2018-05-29 09:41:06,983 [ATRESP] DEBUG hit55.ATresponder - RX1: AT^SSTR?
						 * 2018-05-29 09:41:06,983 [ATRESP] DEBUG hit55.ATresponder - RX1: ^SSTR: 2,0
						 * 2018-05-29 09:41:06,983 [ATRESP] DEBUG hit55.ATresponder - RX1: OK
						 */
						//TODO: DEBUG
						if (!rcvStr.contains("^SSTR") && !rcvStr.contains("OK")){
							log.debug("RX1: " + rcvStr);
							getMeTextAscii(rcvStr); // may set the flag such as CANCEL
						}
					}	
	
					if (rcvStr.toUpperCase().trim().startsWith("+CMTI: ")) {
						cmdType = new Integer(rcvStr.substring(13, rcvStr.length())).intValue(); // +CMTI: "SM", 0
						log.info("### SIM message storage ###");
						
						// To read the SMS data use
						send("AT+CMGR=" + cmdType, "ok");
						
						// To list all stored short messages use
						//send("AT+CMGL=ALL");
						
						// To delete SMS after reading use
						send("AT+CMGD=" + cmdType, "ok");
					}
					
					// Check if it is a Remote-SAT Response (SSTR)
					if (rcvStr.toUpperCase().trim().startsWith("^SSTR: ")) {						
						cmdType = new Integer(rcvStr.substring(9, rcvStr.length())).intValue(); // ^SSTR: ?,XX 

						// ^SSTR: 2,?? | ^SSTR: 3,?? | ^SSTR: 4,??
						// ^SSTR: <state>,<cmdType>
						// <state>: 0=RESET, 1=OFF, 2=IDLE, 3=PAC, 4=WAIT
						// <cmdType>: only valid in case of <state> is PAC or WAIT
						if (rcvStr.substring(7, 8).equals("3") || rcvStr.substring(7, 8).equals("4"))
							ackCmdRequired = true;
						
						// Check Proactive Command Type
						switch (cmdType) {
						case 0: // ^SSTR: 2,0
							// Verify PIN is probably required in case of IDLE
							if (pin != 0){
								log.info("### Verify SIM PIN ###");
								send("AT+CPIN=" + pin);
							} else {
								//log.fatal("SIM PIN unknown! Please check your application parameters.");
							}
							break;
						case 19: // ^SSTR: 3,19
							if (ackCmdRequired) {
								// SEND MESSAGE
								log.info("### 19: SEND MESSAGE (Acknowledge) ###");
								send("at^sstgi=" + cmdType, "ok"); // GetInfos		
								send("at^sstr=" + cmdType + ",0", "ok"); // Confirm
							}
							ackCmdRequired = false;
							break;
						case 32: // ^SSTR: 3,32
							if (ackCmdRequired) {
								// PLAY TONE
								log.info("### 32: PLAY TONE (Acknowledge) ###");
								send("at^sstgi=" + cmdType, "ok"); // GetInfos
								send("at^sstr=" + cmdType + ",0", "ok"); // Confirm
								// Check STATUS
								//log.info("### GET STATUS ###");
								//send("AT^SSTR?"); // The response will be caught by the receiver below
							}
							ackCmdRequired = false;
							break;
						case 33: // ^SSTR: 3,33
							if (ackCmdRequired) {
								// DISPLAY TEXT
								log.info("### 33: DISPLAY TEXT (Acknowledge) ###");
								send("at^sstgi=" + cmdType, "SSTGI:"); // GetInfos
								getMeTextAscii(rcvStr); // may set the flag such as CANCEL
								log.debug("CANCEL: " + cancel + " | STKTIMEOUT: " + stk_timeout + " | RESET: " + reset);
								
								String code = "0"; // OK
								if (cancel){ 
									log.debug("Reset CANCEL Flag...");
									setCancel(false); // reset flag
									code = "16"; // Proactive SIM session terminated by user
								}
								else if (stk_timeout) {
									log.debug("Reset STKTIMEOUT Flag...");
									setStkTimeout(false); // reset flag
									code = "18"; // No response from user
								}
								else if (reset) {
									log.debug("Reset RESET Flag...");
									setReset(false); // reset flag
									send("AT^SMSO");
									try {
										Thread.sleep(30000);
									} catch (InterruptedException e) {
										log.error(e.getMessage());
									}
								}
								
								send("at^sstr=" + cmdType + "," + code, "ok"); // Confirm
								// Check STATUS
								//log.info("### GET STATUS ###");
								//send("AT^SSTR?"); // The response will be caught by the receiver below
							}
							ackCmdRequired = false;
							break;
						case 35: // ^SSTR: 3,35
							if (ackCmdRequired) {
								// GET INPUT
								log.info("### 35: GET INPUT (Acknowledge) ###");
								send("at^sstgi=" + cmdType, "ok"); // GetInfos
								getMeTextAscii(rcvStr); // may set the flag such as CANCEL
								log.debug("CANCEL: " + cancel + " | STKTIMEOUT: " + stk_timeout + " | RESET: " + reset);
																
								String code = "0,,003100320033003400350036"; // OK
								if (cancel){ 
									log.debug("Reset CANCEL Flag...");
									setCancel(false); // reset flag
									code = "16"; // Proactive SIM session terminated by user
								}
								else if (stk_timeout) {
									log.debug("Reset STKTIMEOUT Flag...");
									setStkTimeout(false); // reset flag
									code = "18"; // No response from user
								}
								else if (reset) {
									log.debug("Reset RESET Flag...");
									setReset(false); // reset flag
									send("AT^SMSO");
									try {
										Thread.sleep(30000);
									} catch (InterruptedException e) {
										log.error(e.getMessage());
									}
								} 

								send("at^sstr=" + cmdType + "," + code); // Confirm
								
								//send("at^sstr=" + cmdType + ",0" + ",," + "003100320033003400350036", "ok"); // Confirm
								// Check STATUS
								//log.info("### GET STATUS ###");
								//send("AT^SSTR?"); // The response will be caught by the receiver below
							}
							ackCmdRequired = false;
							break;
						case 37: // ^SSTR: 3,37
							if (ackCmdRequired) {
								// SET UP MENU
								log.info("### 37: SET UP MENU (Acknowledge) ###");
								send("at^sstgi=" + cmdType, "ok"); // GetInfos
								send("at^sstr=" + cmdType + ",0", "ok"); // Confirm
								// Check STATUS
								//log.info("### GET STATUS ###");
								//send("AT^SSTR?"); // The response will be caught by the receiver below
							}
							ackCmdRequired = false;
							break;
						default:
							//log.debug("### UNKNOWN MSG ###");
							break;
						}
					}

					// Check if it is a Remote-SAT Notification (SSTN)
					else if (rcvStr.toUpperCase().trim().startsWith("^SSTN: ")) {
						cmdType = new Integer(rcvStr.substring(7, rcvStr.length())).intValue(); // ^SSTN: 19

						// Check Proactive Command Type
						switch (cmdType) {
						case 19:
							// SEND MESSAGE
							//log.info("### 19: SEND MESSAGE ####");
							//send("at^sstgi=19", "ok");
							//send("at^sstr=19,0"); // TerminalResponse=0 (OK)
							break;
						case 32:
							// PLAY TONE
							log.info("### 32: PLAY TONE ####");
							send("at^sstgi=32", "ok");
							send("at^sstr=32,0"); // TerminalResponse=0 (OK)
							break;
						case 33:
							// DISPLAY TEXT
							log.info("### 33: DISPLAY TEXT ####");
							send("at^sstgi=33", "SSTGI:");
							getMeTextAscii(rcvStr); // may set the flag such as CANCEL
							log.debug("CANCEL: " + cancel + " | STKTIMEOUT: " + stk_timeout + " | RESET: " + reset);
							
							String code = "0"; // OK
							if (cancel){ 
								log.debug("Reset CANCEL Flag...");
								setCancel(false); // reset flag
								code = "16"; // Proactive SIM session terminated by user
							}
							else if (stk_timeout) {
								log.debug("Reset STKTIMEOUT Flag...");
								setStkTimeout(false); // reset flag
								code = "18"; // No response from user
							}
							else if (reset) {
								log.debug("Reset RESET Flag...");
								setReset(false); // reset flag
								send("AT^SMSO");
								try {
									Thread.sleep(30000);
								} catch (InterruptedException e) {
									log.error(e.getMessage());
								}
							}

							send("at^sstr=33," + code); // Confirm
							//send("at^sstr=33,0"); // TerminalResponse=0 (OK)
							break;
						case 35:
							// GET INPUT (Input=123456)
							log.info("### 35: GET INPUT ####");
							send("at^sstgi=35", "ok");
							getMeTextAscii(rcvStr); // may set the flag such as CANCEL
							log.debug("CANCEL: " + cancel + " | STKTIMEOUT: " + stk_timeout + " | RESET: " + reset);
														
							code = "0,,003100320033003400350036"; // OK
							if (cancel){ 
								log.debug("Reset CANCEL Flag...");
								setCancel(false); // reset flag
								code = "16"; // Proactive SIM session terminated by user
							}
							else if (stk_timeout) {
								log.debug("Reset STKTIMEOUT Flag...");
								setStkTimeout(false); // reset flag
								code = "18"; // No response from user
							}
							else if (reset) {
								log.debug("Reset RESET Flag...");
								setReset(false); // reset flag
								send("AT^SMSO");
								try {
									Thread.sleep(30000);
								} catch (InterruptedException e) {
									log.error(e.getMessage());
								}
							} 

							send("at^sstr=" + cmdType + "," + code); // Confirm
							
							//send("at^sstr=35,0" + ",," + "003100320033003400350036"); // TerminalResponse=0 (OK)
							break;
						case 36:
							// SELECT ITEM
							log.info("### 36: SELECT ITEM ####");
							send("at^sstgi=36"); // GetInformation
							break;
						case 37:
							// SET UP MENU
							log.info("### 37: SET UP MENU ####");
							send("at^sstgi=37", "ok"); // Get Information
							send("at^sstr=37,0"); // Remote-SAT Response
							break;
						case 254:
							log.info("### 254: SIM Applet returns to main menu ####");
							//Below is a SHUTDOWN for testing purpose only (RESET event test)
							//log.info("### Send SHUTDOWN Command ###");
							//send("AT^SMSO", "^SHUTDOWN"); // Restart
							break;
						default:
							//log.debug("### UNKNOWN MSG ###");
							break;
						}
					}			
					
				}
			} catch (NumberFormatException e) {
				// log.error("processAtLoop() NumberFormatException: ", e);
			} catch (IOException e) {
				log.warn("processAtLoop() IOException: ", e);
				// Possible REBOOT of HIT55 Module
				log.debug("Waiting 20s for HIT55 Module to be back on...");
				Thread.sleep(20000); // Wait 20s for HIT55 to be back on...
				log.debug("Now closing serial ports");
				closingPort();
				log.debug("Now re-initializing serial port");
				initSerialPort(pID);
			}
		}
	}
	
	public boolean send(String cmd, String expectedRsp, boolean logTx) {
		send(cmd, logTx);
		return receive(expectedRsp);
	}

	public boolean send(String cmd, String expectedRsp) {
		send(cmd, true);
		return receive(expectedRsp);
	}
	
	public void send(String cmd) {
		send(cmd, true);
	}

	public void send(String cmd, boolean logTx) {
		try {
			//TODO: DEBUG
			if (!cmd.contains("SSTR?") && logTx){
				log.debug("TX1: " + cmd.toUpperCase().trim());
			}
			printStream.write(cmd.getBytes());
			printStream.write("\r\n".getBytes()); // append return
		} catch (IOException e) {
			log.error("send() IOException : ", e);
		}
	}

	private synchronized boolean receive(String expectedRsp) {
		//log.trace("receive(expectedRsp) called");
		try {
			String compareStr;
			if (expectedRsp == null)
				compareStr = "OK";
			else
				compareStr = expectedRsp.toUpperCase();

			long startTime = System.currentTimeMillis();

			String rsp;
			//Endless loop until expected response is found, or timeout occurred
			while (true) {
				if ((System.currentTimeMillis() - startTime) >= 15000){
					log.error("Timeout when waiting for expected response = '" + compareStr + "'");
					return false;
				}
					
				while (isAlive && buffReader.ready() && (rsp = buffReader.readLine()) != null) {
					
					if (rsp != null && rsp.length() > 0 && !rsp.contains("OK") && !rsp.contains("^SSTR")) {
						log.debug("RX2: " + rsp);
						getMeTextAscii(rsp);
					}
					
					// Check if the response is part of the state file content:
					if (rsp.toUpperCase().trim().startsWith("+COPS: ")) {
						// Check if COPS response contains valid operator information
						if (rsp.trim().length() >= 13 && rsp.trim().substring(7,12).equals("0,0,\"")){
							String operator = rsp.trim().substring(12, rsp.trim().indexOf("\"", 13));
							//+COPS: 0,0,"Swisscom",2
							log.info("Operator: " + operator);
							stampString[1] = operator;
						} else {
							// Not getting expected COPS response content
							log.info("Operator: <unknown>");
							stampString[1] = ""; // empty string
						}
					} else if (rsp.trim().startsWith("+CSQ: ")) {
						if (rsp.trim().length() > 6){
							String strength = rsp.toUpperCase().trim().substring(6);
							//+CSQ: 29,99
							log.info("SignalStrength: " + strength);
							stampString[2] = strength;
						} else {
							log.info("SignalStrength: <unknown>");
							stampString[2] = "";
						}
						
					} else if (rsp.toUpperCase().trim().matches("[0-9]{15}")) {
						//IMSI (228012122509247) or IMEI (356497049143777)
						if (rsp.toUpperCase().trim().startsWith("228")){
							// First 3 digits = MCC (Mobile Country Code)
							log.info("IMSI: " + rsp.toUpperCase().trim());
							stampString[3] = rsp.toUpperCase().trim();
						} else {
							log.info("IMEI: " + rsp.toUpperCase().trim());
							stampString[4] = rsp.toUpperCase().trim();
						}
					}
					
					else if (rsp.toUpperCase().trim().contains(compareStr) || rsp.toUpperCase().trim().contains("ERROR")) {
						if (rsp.toUpperCase().trim().contains("ERROR"))
							log.trace("Got ERROR: '" + rsp.toUpperCase().trim() + "'");
						Thread.sleep(sleepMillis);
						return true; // GOT RESPONSE!
					} 		
				}	
			}
		} catch (IOException e) {
			log.error("receive() IOException : ", e);
		} catch (InterruptedException e) {
			log.error("receive() InterruptedException : ", e);
		}
		return false;
	}

	private void getMeTextAscii(String rsp) throws UnsupportedEncodingException {
		String textUcs2 = rsp;
		// Only in case of UCS2 Mode: Convert to ASCII
		if (!rsp.contains("+CMGR") && rsp.indexOf(",\"") != -1 && rsp.indexOf("\",") != -1 && rsp.charAt(rsp.indexOf(",\"") + 2) != '"') {
			// Found some possible text content
			try {
				//log.info(">>> " + textUcs2);
				textUcs2 = rsp.substring(rsp.indexOf(",\"") + 2, rsp.indexOf("\",", rsp.indexOf(",\"") + 2));
				textUcs2 = new String(hexToByte(textUcs2), "UTF-16");
				log.debug("UI Text=\"" + textUcs2 + "\"");
			} catch (Exception e) {
				//do nothing...
			}
		}
		
		// Check if UI Text contains specific keywords
		if (textUcs2.indexOf("CANCEL") != -1) {
			setCancel(true);
			log.debug("'CANCEL'-keyword detected! Message will be cancelled.");
		}
		else if (textUcs2.indexOf("STKTIMEOUT") != -1) {
			setStkTimeout(true);
			log.debug("'STKTIMEOUT'-keyword detected! Message will time out.");
		}
		else if (textUcs2.indexOf("RESET") != -1) {
			setReset(true);
			log.debug("'RESET'-keyword detected! Message will be dropped and GSM Client will reboot.");
		}
		
	}

	private void closingPort() {
		try {
			if (buffReader != null){
				buffReader.close();
				buffReader = null;
				log.debug("BufferedReader closed.");
			}
		} catch (IOException e) {
			log.error("closingPort() IOException: ", e);
		}

		try {
			if (printStream != null){
				printStream.close();
				printStream = null;
				log.debug("PrintStream closed.");
			}
		} catch (Exception e) {
			log.error("closingPort() Exception: ", e);
		}

		try {
			if (serPort != null){
				serPort.close();
				serPort = null;
				log.debug("SerialPort closed.");
			}
		} catch (Exception e) {
			log.error("closingPort() Exception: ", e);
		}
	}

	public byte[] hexToByte(String hexString) {
		String strippedData = hexString.replaceAll(" ", "");
		byte[] dataBytes = new byte[strippedData.length() / 2];
		for (int i = 0; i < dataBytes.length; i++) {
			int endIndex = 2 * i + 2;
			short shortValue = Short.parseShort(strippedData.substring(i * 2, endIndex), 16);
			dataBytes[i] = (byte) (shortValue);
		}
		return dataBytes;
	}
	
	public static synchronized void setCancel(boolean flag){
		cancel = flag;
	}

	public static synchronized void setStkTimeout(boolean flag){
		stk_timeout = flag;
	}
	
	public static synchronized void setReset(boolean flag){
		reset = flag;
	}
}