package com.swisscom.atclient;

import com.fazecast.jSerialComm.*;
import java.io.*;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class ATresponder extends Thread {
	
	private final Logger log = LogManager.getLogger(ATresponder.class.getName());

	// Detect incoming Text SMS with specific keyword
	private final String txtSmsKeyword = "OTP Token:";
	
	private final long inactTimerMillis = 600000; // In case of inactivity, get status information such as AT+COPS after x millis
	private final int sleepMillis = 10; // 100ms are recommended in the PLS8-E manual
	
	private BufferedReader buffReader;
	private PrintStream printStream;
	
	private volatile static boolean cancel;
	private volatile static boolean stk_timeout;

	private String serialport;
	private SerialPort comPort;
	private int baudrate = 9600;
	private final int databits = 8;
	private final int stopbits = 1;
	private final int parity = 0;
	
	private final byte mode; // Switch: 1=ER, 2=AR
	
	public volatile static boolean isAlive = true;

	public ATresponder(String serialport, byte mode) {
		Thread.currentThread().setName("CLIENT"); // Client Thread
		this.serialport = serialport;
		this.mode = mode;
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
		
		try {
			initSerialPort();
			Thread.sleep(1000);
			processAtLoop();
		} catch (UnsupportedEncodingException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
		
		// Loop exited. Let's close ports..
		closingPort();
		log.info("Exiting Application");
	}

	private void initSerialPort() throws UnsupportedEncodingException, IOException {
		log.info("Init Serial Port in progress.");
		
		SerialPort[] ports = SerialPort.getCommPorts();
		for (int i = 0; i < ports.length; i++){
			log.debug("Index: " + i + "; " + ports[i].getSystemPortName() + "; " + ports[i].getPortDescription() + "; " + ports[i].getDescriptivePortName() );
		}
		
		comPort = SerialPort.getCommPort(serialport);
		
		log.debug("Selected Port: " + comPort.getSystemPortName());
		
		while (!comPort.isOpen()) {
			comPort.openPort();
			if (!comPort.isOpen()) {
				log.error("Selected Port not available yet. Trying again in 5 seconds.");
				try {
					Thread.sleep(5000);
				} catch (InterruptedException e) {
					e.printStackTrace();
				}
			} else {
				log.debug("Selected Port successfully opened.");
			}
		} 
		
		comPort.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING, 100, 0);
		comPort.setComPortParameters(baudrate, databits, stopbits, parity);

		// LTE Modul set DTR to true
		log.debug("Set DTR: " + comPort.setDTR());
		
		buffReader = new BufferedReader(new InputStreamReader(comPort.getInputStream(), "UTF-8"));
		printStream = new PrintStream(comPort.getOutputStream(), true, "UTF-8");

		log.info("Connection successfully established.");
	}

	private void initAtCmd() throws InterruptedException {
		send("ATE0"); // Turn off echo mode

		send("AT+CMEE=2"); // Switch on verbose error messages
		
		send("AT+CIMI"); // IMSI

		send("AT+CGSN"); // IMEI
		
		send("AT+CNUM"); // MSISDN
		
		send("AT+CMGF=1"); // Set SMS text mode
		
		send("AT+CNMI=1,1"); // Activate the display of a URC on every received SMS
		
		send("AT+COPS?"); // Provider + access technology
		//		 0 GSM
		//		 1 GSM Compact (2G)
		//		 2 UTRAN (3G)
		//		 3 GSM w/EGPRS
		//		 4 UTRAN w/HSDPA
		//		 5 UTRAN w/HSUPA
		//		 6 UTRAN w/HSDPA and HSUPA
		//		 7 E-UTRAN (LTE)
		
		send("AT+CSQ"); // Signal Strength
	}
	
	
	private void initialize(boolean arMode) {
		
		/**
		 * This method is called if mode AR or ER is called
		 * Not used in case of UE mode
		 */

		if (arMode) {
			// Switch to Automatic Response (AR) and restart device
			log.info("### Switch to Automatic Response (AR) ###");
			send("AT^SSTA=0"); // Automatic Response Mode
		} else {
			// Switch to Explicit Response (ER) and restart device
			log.info("### Switch to Explicit Response (ER) ###");
			send("AT^SSTA=1,1"); // Explicit Response Mode UCS2
		}

		shutdownAndExit();
	}
	
	public void shutdownAndExit(){
		log.info("### Send SHUTDOWN Command and exit application ###");
		send("AT^SMSO", "^SHUTDOWN"); // Restart
		closingPort();
		isAlive = false; // will exit the while loop and terminate the application	
	}

	private synchronized void processAtLoop() throws InterruptedException, UnsupportedEncodingException, IOException {

		if (mode == 1) {
			initialize(false); // ER
			return; // exit
		} else if (mode == 2) {
			initialize(true); // AR
			return; // exit
		}

		long inactivityTimerCurrent = System.currentTimeMillis();
		String code;
		String rcvStr;
		int cmdType = 0;
		boolean ackCmdRequired = false;
		
		initAtCmd();

		send("AT^SSTR?", null); // STK Menu initialization
		
		// Start endless loop...
		while (isAlive) {
			
			Thread.sleep(sleepMillis);
			
			if ((System.currentTimeMillis() - inactivityTimerCurrent) >= inactTimerMillis){
				// Check every x seconds of inactivity
				inactivityTimerCurrent = System.currentTimeMillis();
				
				send("AT+COPS?"); // Provider + access technology
			} 
			
			// Listening for incoming notifications (SIM->ME)
			try {
				while (isAlive && buffReader.ready() && (rcvStr = buffReader.readLine()) != null) {
					
					inactivityTimerCurrent = System.currentTimeMillis(); // reset the inactivity timer
					
					if (rcvStr.length() == 0)
						break;
					
					log.debug("<<< RX " + rcvStr);
	
					getMeTextAscii(rcvStr); // may set the flag such as CANCEL	
	
					if (rcvStr.toUpperCase().trim().startsWith("+CMTI: ")) {
						cmdType = new Integer(rcvStr.substring(13, rcvStr.length())).intValue(); // +CMTI: "SM", 0
						log.info("### Incoming Short Message ###");
						
						// read the SMS data
						log.debug("### Read SMS Details ###");
						send("AT+CMGR=" + cmdType);
						
						// delete all stored short messages after reading
						log.debug("### Delete SMS storage ###");
						send("AT+CMGD=0,4");
						
						// delete specific SMS after reading
						//log.debug("### Delete SMS ###");
						//send("AT+CMGD=" + cmdType);
					}
					
					// Check if it is a Remote-SAT Response (SSTR)
					if (rcvStr.toUpperCase().trim().startsWith("^SSTR: ")) {	
						// ^SSTR: 3,19
						// ^SSTR: 19,0,"" --> ignore this one (NumberFormatException catched and ignored)
						cmdType = new Integer(rcvStr.substring(9, rcvStr.length())).intValue(); // ^SSTR: ?,XX

						// ^SSTR: 2,?? | ^SSTR: 3,?? | ^SSTR: 4,??
						// ^SSTR: <state>,<cmdType>
						// <state>: 0=RESET, 1=OFF, 2=IDLE, 3=PAC, 4=WAIT
						// <cmdType>: only valid in case of <state> is PAC or WAIT
						if (rcvStr.substring(7, 8).equals("3") || rcvStr.substring(7, 8).equals("4"))
							ackCmdRequired = true;
						
						// Check Proactive Command Type
						switch (cmdType) {
						case 19: // ^SSTR: 3,19
							if (ackCmdRequired) {
								// SEND MESSAGE
								log.info("### 19: SEND MESSAGE ###");
								send("at^sstgi=" + cmdType); // GetInfos		
								send("at^sstr=" + cmdType + ",0"); // Confirm
							}
							ackCmdRequired = false;
							break;
						case 32: // ^SSTR: 3,32
							if (ackCmdRequired) {
								// PLAY TONE
								log.info("### 32: PLAY TONE ###");
								send("at^sstgi=" + cmdType); // GetInfos
								send("at^sstr=" + cmdType + ",0"); // Confirm
							}
							ackCmdRequired = false;
							break;
						case 33: // ^SSTR: 3,33
							if (ackCmdRequired) {
								// DISPLAY TEXT
								log.info("### 33: DISPLAY TEXT ###");
								send("at^sstgi=" + cmdType); // GetInfos
								getMeTextAscii(rcvStr); // may set the flag such as CANCEL
								log.debug("CANCEL: " + cancel + " | STKTIMEOUT: " + stk_timeout);
								
								code = "0"; // OK
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
								
								send("at^sstr=" + cmdType + "," + code); // Confirm
							}
							ackCmdRequired = false;
							break;
						case 35: // ^SSTR: 3,35
							if (ackCmdRequired) {
								// GET INPUT
								log.info("### 35: GET INPUT ###");
								send("at^sstgi=" + cmdType); // GetInfos
								getMeTextAscii(rcvStr); // may set the flag such as CANCEL
								log.debug("CANCEL: " + cancel + " | STKTIMEOUT: " + stk_timeout);
																
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

								send("at^sstr=" + cmdType + "," + code); // Confirm
							}
							ackCmdRequired = false;
							break;
						case 37: // ^SSTR: 3,37
							if (ackCmdRequired) {
								// SET UP MENU
								log.info("### 37: SET UP MENU ###");
								send("at^sstgi=" + cmdType); // GetInfos
								send("at^sstr=" + cmdType + ",0"); // Confirm
							}
							ackCmdRequired = false;
							break;
						default:
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
							log.info("### 19: SEND MESSAGE ###");
							send("at^sstgi=" + cmdType); // GetInfos		
							send("at^sstr=" + cmdType + ",0"); // Confirm
							break;
						case 32:
							// PLAY TONE
							log.info("### 32: PLAY TONE ####");
							send("at^sstgi=32");
							send("at^sstr=32,0"); // TerminalResponse=0 (OK)
							break;
						case 33:
							// DISPLAY TEXT
							log.info("### 33: DISPLAY TEXT ####");
							send("at^sstgi=33");
							getMeTextAscii(rcvStr); // may set the flag such as CANCEL
							log.debug("CANCEL: " + cancel + " | STKTIMEOUT: " + stk_timeout);
							
							code = "0"; // OK
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

							send("at^sstr=33," + code); // Confirm
							break;
						case 35:
							// GET INPUT (Input=123456)
							log.info("### 35: GET INPUT ####");
							send("at^sstgi=35");
							getMeTextAscii(rcvStr); // may set the flag such as CANCEL
							log.debug("CANCEL: " + cancel + " | STKTIMEOUT: " + stk_timeout);
														
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

							send("at^sstr=" + cmdType + "," + code); // Confirm
							break;
						case 36:
							// SELECT ITEM
							log.info("### 36: SELECT ITEM ####");
							send("at^sstgi=36"); // GetInformation
							break;
						case 37:
							// SET UP MENU
							log.info("### 37: SET UP MENU ####");
							send("at^sstgi=37"); // Get Information
							send("at^sstr=37,0"); // Remote-SAT Response
							break;
						case 254:
							log.info("### 254: SIM Applet returns to main menu ####");
							break;
						default:
							break;
						}
					}			
					
				}
			} catch (NumberFormatException e) {
				// Ignore the ^SSTR: 19,0,"" cases
			} catch (IOException e) {
				log.warn("processAtLoop() IOException: ", e);
				// Possible REBOOT
				log.debug("Waiting 20s for GSM Module to be back on...");
				Thread.sleep(20000); 
				log.debug("Now closing serial ports");
				closingPort();
			}
		}
	}
	
	public void send(String cmd) {
		send(cmd, "ok");
	}

	public void send(String cmd, String expectedRsp) {
		try {
			log.debug(">>> TX " + cmd);
			printStream.write((cmd + "\r\n").getBytes());
		} catch (IOException e) {
			log.error("send() IOException : ", e);
		}
		
		if (expectedRsp != null)
			receiveExpectedRsp(expectedRsp);
	}

	private synchronized boolean receiveExpectedRsp(String expectedRsp) {
		try {
			String compareStr;
			if (expectedRsp == null)
				compareStr = "OK";
			else
				compareStr = expectedRsp.toUpperCase();

			long startTime = System.currentTimeMillis();

			String rsp;

			while (true) {
				
				Thread.sleep(sleepMillis);
				
				if ((System.currentTimeMillis() - startTime) >= 10000){
					log.error("Didn't get expected response '" + compareStr + "' in 10 seconds.");
					return false;
				}
		
				while (isAlive && buffReader.ready() && (rsp = buffReader.readLine()) != null) {
					
					if (rsp.length() > 0) {
						log.debug("<<< RX " + rsp);
						
						getMeTextAscii(rsp);
											
						if (rsp.contains(txtSmsKeyword)) {
							// Text Short Message Keyword detected
							log.info("Text SMS: \"" + rsp + "\"");
							// TODO: Do something with the text content... 
						}
						
						else if (rsp.toUpperCase().trim().contains("ERROR")) {
							log.error("Got ERROR: '" + rsp.toUpperCase().trim() + "'");
						} 		
						
						else if (rsp.toUpperCase().trim().contains(compareStr)) {			
							return true; // Got the expected response
						} 
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
		// Only in case of UCS2 Mode: Convert to ASCII
		if (rsp.contains("^SSTGI:") && rsp.indexOf(",\"") != -1 && rsp.indexOf("\",") != -1 && rsp.charAt(rsp.indexOf(",\"") + 2) != '"') {
			// Found some possible text content
			try {
				rsp = rsp.substring(rsp.indexOf(",\"") + 2, rsp.indexOf("\",", rsp.indexOf(",\"") + 2));
				rsp = new String(hexToByte(rsp), "UTF-16");
				log.info("UI Text=\"" + rsp + "\"");
			} catch (Exception e) {
				//do nothing...
			}
		}
		
		// Check if UI Text contains specific keywords
		if (rsp.indexOf("CANCEL") != -1) {
			setCancel(true);
			log.debug("'CANCEL'-keyword detected! Message will be cancelled.");
		}
		else if (rsp.indexOf("STKTIMEOUT") != -1) {
			setStkTimeout(true);
			log.debug("'STKTIMEOUT'-keyword detected! Message will time out.");
		}
		
	}

	private void closingPort() {
		if (comPort.closePort()) {
			log.debug("Serial Port closed.");
		}
		
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
	
}