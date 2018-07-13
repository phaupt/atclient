package com.swisscom.atclient;

import com.fazecast.jSerialComm.*;
import java.io.*;
import java.lang.management.ManagementFactory;
import java.util.Arrays;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class ATresponder extends Thread {
	
	private final Logger log = LogManager.getLogger(ATresponder.class.getName());
	
	// Detect incoming Text SMS with specific keyword
	private final String txtSmsKeyword = "OTP Token:";
	
	private final long heartBeatMillis = 10000; // Heart beat to detect serial port disconnection in milliseconds
	private final int sleepMillis = 10; // Polling interval in milliseconds for incoming requests
	
	private BufferedReader buffReader;
	private PrintStream printStream;
	
	private volatile static boolean cancel;
	private volatile static boolean stk_timeout;

	private String serialport = null;
	private SerialPort comPort;
	
	private final int baudrate = 9600;
	private final int databits = 8;
	private final int stopbits = 1;
	private final int parity = 0;
	
	private byte mode; // Switch: 1=ER, 2=AR
	
	public volatile static boolean isAlive = true;

	public ATresponder(byte mode) {
		this.mode = mode;
	}
	
	public ATresponder(byte mode, String serialPort) {
		this.mode = mode;
		this.serialport = serialPort;
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
				close(); // Double check if ports are closed
				System.out.println("Done.");
			}
		});
		log.debug("Attached Shutdown Hook");
	}
	
	public void run() {
		Thread.currentThread().setName(ManagementFactory.getRuntimeMXBean().getName());

		log.info("Application started...");
		attachShutDownHook();
		
		try {
			initSerialPort();
			Thread.sleep(1000);
			initAtCmd();
			listenForRx();
		} catch (UnsupportedEncodingException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
		
		// Loop exited. Let's close ports..
		close();
		log.info("Exiting Application");
	}

	private void initSerialPort() throws UnsupportedEncodingException, IOException, InterruptedException {
		log.info("Init Serial Port in progress.");
		
		SerialPort[] ports; 
		
		boolean portSuccess = false;
		
		while (!portSuccess) {
			
			ports = SerialPort.getCommPorts();
			
			if (mode == 0) {

				// automatic serial port detection!
				for (SerialPort port : ports) {
					log.debug("Found serial port: " + port.getSystemPortName() + " | " + port.getDescriptivePortName());
					serialport = port.getSystemPortName();
					// Found a port... trying to open it
					portSuccess = openPort();
					if (portSuccess)
						break; // success, break for loop
					Thread.sleep(500);
				}

			} else {
				// mode 1 (ER), 2 (AR)
				// serialport was manually defined via argument
				portSuccess = openPort();
			}
			
			if (!portSuccess) {
				log.error("No luck yet to find the proper terminal. Will try again in 3 seconds.");
				try {
					Thread.sleep(3000);
				} catch (InterruptedException e) {
					e.printStackTrace();
				}
			}
			
		}
			
	}
	
	private boolean openPort() throws IOException {
		log.debug(serialport + " tryig to open");
		comPort = SerialPort.getCommPort(serialport);
		comPort.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING, 500, 500);
		comPort.setComPortParameters(baudrate, databits, stopbits, parity);
		comPort.setDTR();
		comPort.openPort();
		
		if (!comPort.isOpen()) {
			// Port not available
			log.error(serialport + " is currently not available.");
			return false;			
		} else {
			// Port available
			log.debug(serialport + " successfully opened.");

			buffReader = new BufferedReader(new InputStreamReader(comPort.getInputStream(), "UTF-8"));
			printStream = new PrintStream(comPort.getOutputStream(), true, "UTF-8");

			log.info(serialport + " connection established.");
			
			if (send("AT+CGMM", 500)) {
				log.info(serialport + " is responding. Success!");
				Thread.currentThread().setName(ManagementFactory.getRuntimeMXBean().getName() + " " + serialport); // Update thread name
				return true;
			} else {
				log.error(serialport + " wasn't responding.");
				close();
				return false;
			}
			
		}
	}

	private void initAtCmd() throws InterruptedException {
		
		boolean success = false;
		while (!success) {
			Thread.sleep(1000); // Give some time for the terminal to be ready..
			// Send the first AT command. In case the terminal is not yet ready, repeat the command
			success = send("AT+CGMM"); // Request model identification	
		}
		
		send("ATE0"); // Turn off echo mode
		
		send("AT+CMEE=2"); // Switch on verbose error messages
		
		send("AT+CIMI"); // IMSI

		send("AT+CGSN"); // IMEI
		
		send("AT+CNUM"); // MSISDN
		
		send("AT+CMGF=1"); // Set SMS text mode
		
		send("AT+CNMI=1,1"); // Activate the display of a URC on every received SMS
		
		send("AT+COPS?"); // Provider + access technology
		
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
		send("AT^SMSO"); // Power-off the terminal
		close();
		isAlive = false; // will exit the while loop and terminate the application	
	}

	private void listenForRx() throws InterruptedException, UnsupportedEncodingException, IOException {

		if (mode == 1) {
			initialize(false); // ER
			return; // exit
		} else if (mode == 2) {
			initialize(true); // AR
			return; // exit
		}

		long inactivityTimerCurrent = System.currentTimeMillis();
		String code;
		String rx;
		int value = 0;
		boolean ackCmdRequired = false;
		
		send("AT^SSTR?", null); // STK Menu initialization
		
		// Start endless loop...
		while (isAlive) {
			
			Thread.sleep(sleepMillis);
			
			if ((System.currentTimeMillis() - inactivityTimerCurrent) >= heartBeatMillis){
				// Check every x milliseconds of inactivity
				
				log.debug(serialport + " heart beat check...");
				
				// Provider + access technology
				if (!comPort.isOpen() || !send("AT+COPS?")) {
					log.error("Trying to re-connect serial port.");
					close();
					initSerialPort();
					initAtCmd();
					send("AT^SSTR?", null); // STK Menu initialization
				}
				
				inactivityTimerCurrent = System.currentTimeMillis();
			} 
			
			// Listening for incoming notifications (SIM->ME)
			try {
				while (isAlive && buffReader.ready() && (rx = buffReader.readLine()) != null) {
					
					inactivityTimerCurrent = System.currentTimeMillis(); // reset the inactivity timer
					
					if (rx.length() == 0)
						break;
					
					log.debug("<<< RX " + rx);
	
					getMeTextAscii(rx); // may set the flag such as CANCEL	
	
					if (rx.toUpperCase().startsWith("+CMTI: ")) {
						value = new Integer(rx.substring(13, rx.length())).intValue(); // +CMTI: "SM", 0
						log.info("### TEXT MESSAGE (SMS) ###");
						send("AT+CMGR=" + value); // read the SMS data
						send("AT+CMGD=0,4"); // delete all stored short messages after reading
					} else if (rx.toUpperCase().startsWith("^SSTR: ")) {	
						// ^SSTR: 3,19
						// ^SSTR: 19,0,"" --> ignore this one (NumberFormatException catched and ignored)
						value = new Integer(rx.substring(9, rx.length())).intValue(); // ^SSTR: ?,XX

						// ^SSTR: 2,?? | ^SSTR: 3,?? | ^SSTR: 4,??
						// ^SSTR: <state>,<cmdType>
						// <state>: 0=RESET, 1=OFF, 2=IDLE, 3=PAC, 4=WAIT
						// <cmdType>: only valid in case of <state> is PAC or WAIT
						if (rx.substring(7, 8).equals("3") || rx.substring(7, 8).equals("4"))
							ackCmdRequired = true;
						
						// Check Proactive Command Type
						switch (value) {
						case 19: // ^SSTR: 3,19
							if (ackCmdRequired) {
								// SEND MESSAGE
								log.info("### 19: SEND MESSAGE ###");
								send("at^sstgi=" + value); // GetInfos		
								send("at^sstr=" + value + ",0"); // Confirm
							}
							ackCmdRequired = false;
							break;
						case 32: // ^SSTR: 3,32
							if (ackCmdRequired) {
								// PLAY TONE
								log.info("### 32: PLAY TONE ###");
								send("at^sstgi=" + value); // GetInfos
								send("at^sstr=" + value + ",0"); // Confirm
							}
							ackCmdRequired = false;
							break;
						case 33: // ^SSTR: 3,33
							if (ackCmdRequired) {
								// DISPLAY TEXT
								log.info("### 33: DISPLAY TEXT ###");
								send("at^sstgi=" + value); // GetInfos
								getMeTextAscii(rx); // may set the flag such as CANCEL
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
								
								send("at^sstr=" + value + "," + code); // Confirm
							}
							ackCmdRequired = false;
							break;
						case 35: // ^SSTR: 3,35
							if (ackCmdRequired) {
								// GET INPUT
								log.info("### 35: GET INPUT ###");
								send("at^sstgi=" + value); // GetInfos
								getMeTextAscii(rx); // may set the flag such as CANCEL						
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

								send("at^sstr=" + value + "," + code); // Confirm
							}
							ackCmdRequired = false;
							break;
						case 37: // ^SSTR: 3,37
							if (ackCmdRequired) {
								// SET UP MENU
								log.info("### 37: SET UP MENU ###");
								send("at^sstgi=" + value); // GetInfos
								send("at^sstr=" + value + ",0"); // Confirm
							}
							ackCmdRequired = false;
							break;
						default:
							break;
						}
					} else if (rx.toUpperCase().startsWith("^SSTN: ")) {
						value = new Integer(rx.substring(7, rx.length())).intValue(); // ^SSTN: 19

						// Check Proactive Command Type
						switch (value) {
						case 19:
							// SEND MESSAGE
							log.info("### 19: SEND MESSAGE ###");
							send("at^sstgi=" + value); // GetInfos		
							send("at^sstr=" + value + ",0"); // Confirm
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
							getMeTextAscii(rx); // may set the flag such as CANCEL
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
							getMeTextAscii(rx); // may set the flag such as CANCEL							
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

							send("at^sstr=" + value + "," + code); // Confirm
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
				close();
			}
		}
	}
	
	public boolean send(String cmd, long timeout) {
		return send(cmd, "ok", timeout);
	}
	
	public boolean send(String cmd) {
		return send(cmd, "ok");
	}
	
	public boolean send(String cmd, String expectedRsp) {
		return send(cmd, expectedRsp, 0);
	}
	
	public boolean send(String cmd, String expectedRsp, long timeout) {
		if (!comPort.isOpen()) {
			log.error(serialport + " is not open. Cannot send TX.");
			return false;
		}
		
		try {
			log.debug(">>> TX " + cmd);
			printStream.write((cmd + "\r\n").getBytes());
		} catch (IOException e) {
			log.error("send() IOException : ", e);
		}
		
		if (expectedRsp != null)
			return receiveExpectedRsp(expectedRsp, timeout);
		
		return true; // no expected response
	}

	private boolean receiveExpectedRsp(String expectedRsp, long timeout) {
		try {
			String compareStr;
			if (expectedRsp == null)
				compareStr = "OK";
			else
				compareStr = expectedRsp.toUpperCase();

			long startTime = System.currentTimeMillis();

			String rx;
			int value;
			
			if (timeout == 0)
				timeout = 5000; // default

			while (true) {
				
				Thread.sleep(sleepMillis);
				
				if ((System.currentTimeMillis() - startTime) >= timeout){
					log.error(serialport + " timeout waiting for response.");
					return false;
				}
		
				while (isAlive && buffReader.ready() && (rx = buffReader.readLine()) != null) {
					
					if (rx.length() > 0) {
						log.debug("<<< RX " + rx);
						
						getMeTextAscii(rx);
											
						if (rx.contains(txtSmsKeyword)) {
							// Text Short Message Keyword detected
							log.info("Text SMS: \"" + rx + "\"");
							// TODO: Do something with the text content... 
						} else if (rx.toUpperCase().startsWith("+COPS: ")) {
							value = new Integer( Arrays.asList(rx.split(",")).get(3) ).intValue(); // +COPS: 0,0,"Swisscom",7
							switch (value) {
							case 0: 
								log.info("Radio Access Technology: GSM (2G)");
								break;
							case 1: 
								log.info("Radio Access Technology: GSM Compact (2G)");
								break;
							case 2: 
								log.info("Radio Access Technology: UTRAN (3G)");
								break;
							case 3: 
								log.info("Radio Access Technology: GSM w/EGPRS (2G)");
								break;
							case 4: 
								log.info("Radio Access Technology: UTRAN w/HSDPA (3G)");
								break;
							case 5: 
								log.info("Radio Access Technology: UTRAN w/HSUPA (3G)");
								break;
							case 6: 
								log.info("Radio Access Technology: UTRAN w/HSDPA and HSUPA (3G)");
								break;
							case 7: 
								log.info("Radio Access Technology: E-UTRAN (4G/LTE)");
								break;
							default:
								break;
							}
						} else if (rx.toUpperCase().trim().contains(compareStr)) {			
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
				log.info("TEXT: \"" + rsp + "\"");
			} catch (Exception e) {
				//do nothing...
			}
		}
		
		// Check if UI Text contains specific keywords
		if (rsp.indexOf("CANCEL") != -1) {
			setCancel(true);
			log.info("'CANCEL'-keyword detected! Message will be cancelled.");
		}
		else if (rsp.indexOf("STKTIMEOUT") != -1) {
			setStkTimeout(true);
			log.info("'STKTIMEOUT'-keyword detected! Message will time out.");
		}
		
	}

	private void close() {
		Thread.currentThread().setName(ManagementFactory.getRuntimeMXBean().getName()); // Update thread name
		
		if (comPort.isOpen()) {
			log.debug(serialport + " trying to close serial port.");
			if (comPort.closePort())
				log.debug(serialport + " is now closed.");
			else 
				log.error(serialport + " is still open but couldn't be closed.");
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