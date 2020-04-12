package com.swisscom.atclient;

import com.fazecast.jSerialComm.*;
import java.io.*;
import java.lang.management.ManagementFactory;
import java.net.URL;
import java.net.URLConnection;
import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import org.apache.commons.codec.binary.Base64;

public class ATresponder extends Thread {
	
	private final Logger log = LogManager.getLogger(ATresponder.class.getName());
	
	// Detect incoming Text SMS that contains a specific keyword and forward to target MSISDN. Value "" will forward all SMS.
	private final String smsPattern = "^.*: [A-Z0-9]{4}\\. .*$";
	private String smsTargetMsisdn = null;
	private String smsURL = null;
	private String smsQueryParam = "TXT";
	private String smsAuthName = null;
	private String smsAuthPassword = null;
	
	// Auto detect terminal based on descriptive string representing the serial port or the device connected to it
	// String is retrieved via com.fazecast.jSerialComm.SerialPort.getDescriptivePortName() for both Windows and Linux
	private final String[] portStrArr = { "Gemalto M2M ALSx PLSx LTE USB serial Port", "LTE Modem" };
	
	private final String validPIN = "003100320033003400350036";
	private final String invalidPIN = "003600350034003300320031";
	private final int maxWrongPinAttempts = 5;
	private int cntrWrongPinAttempts = maxWrongPinAttempts;

	private final long heartBeatMillis = 600000; // Heart beat to detect serial port disconnection in milliseconds
	private final int sleepMillis = 50; // Polling interval in milliseconds for incoming requests
	
	private BufferedReader buffReader;
	private PrintStream printStream;
	
	private volatile static boolean cancel;
	private volatile static boolean stk_timeout;
	private volatile static boolean block_pin;
	
	private boolean getInputTimerFlag = false;
	private boolean getInputTimerKeyGenFlag = false;
	private long getInputTimer = 0;

	private String serPortStr = null;
	private SerialPort serPort;
	
	private final int baudrate = 9600;
	private final int databits = 8;
	private final int stopbits = 1;
	private final int parity = 0;
	
	private byte opMode; // Switch: 1=ER, 2=AR
	
	private String msisdn = null;
	
	public volatile static boolean isAlive = true;
	
	private final char quote = 34;
	private final char ctrlz = 26;

	public ATresponder(byte mode) {
		this.opMode = mode;
	}
	
	public ATresponder(byte mode, String serialPort) {
		this.opMode = mode;
		this.serPortStr = serialPort;
	}
	
	public void run() {
		Thread.currentThread().setName(ManagementFactory.getRuntimeMXBean().getName());
		
		try {
			smsTargetMsisdn = System.getProperty("targetMsisdn");
			smsURL = System.getProperty("smsURL");
			smsAuthName = System.getProperty("smsAuthName");
			smsAuthPassword = System.getProperty("smsAuthPassword");
			serPortStr = System.getProperty("serial.port");
		} catch (Exception e1) {
		}

		log.info("Application started...");
		attachShutDownHook();
		
		try {
			if (serPortStr == null) {
				lookupSerialPort();
			} else {
				openPort();
			}
			initAtCmd();
			listenForRx();
		} catch (UnsupportedEncodingException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
		
		log.info("Exiting Application");
	}
	
	public void attachShutDownHook() {
		Runtime.getRuntime().addShutdownHook(new Thread() {
			public void run() {
				Thread.currentThread().setName("ShutdownHook");
				log.debug("Executing Shutdown Hook");
				isAlive = false; // will exit the while loop and terminate the application	
			}
		});
		log.debug("Attached Shutdown Hook");
	}
	
	public void shutdownAndExit(){
		log.info("Send SHUTDOWN Command and exit application");
		send("AT^SMSO"); // Power-off the terminal
		isAlive = false; // will exit the while loop and terminate the application	
	}

	private void lookupSerialPort() throws UnsupportedEncodingException, IOException, InterruptedException {
		log.info("Init serial port.");
		
		SerialPort[] ports; 
		
		boolean portSuccess = false;
		String portDesc;
		
		while (!portSuccess) {
			ports = SerialPort.getCommPorts();
			if (opMode == 0) {
				// automatic serial port detection!
				for (SerialPort port : ports) {
					
					portDesc = port.getDescriptivePortName();
					
					for (String portStr : portStrArr) {
						// Check for known terminal (port string)
						if (portDesc.contains(portStr)) {
							log.debug("Found a serial port: " + port.getSystemPortName() + " " + portDesc);
							serPortStr = port.getSystemPortName();

							// Found a port with matching name... trying to open it
							portSuccess = openPort();
							if (portSuccess)
								break; // success, break iteration for portStrArr
						} 
					}
					if (portSuccess)
						break; // success, break iteration for available serial ports
					else
						log.debug("Unknown serial port: " + port.getSystemPortName() + " " + portDesc);
					
					Thread.sleep(100); // wait before to proceed with next available port in list
				}

			} else {
				// mode 1 (ER), 2 (AR)
				// serialport was manually defined via argument
				portSuccess = openPort();
			}
			
			if (!portSuccess) {
				log.error("No terminal found. Next check in 10 seconds.");
				try {
					Thread.sleep(10000); 
				} catch (InterruptedException e) {
					e.printStackTrace();
				}
			}	
		}	
		Thread.sleep(2000);
	}
	
	private boolean openPort() throws IOException {
		log.debug(serPortStr + " trying to open");
		serPort = SerialPort.getCommPort(serPortStr);
		serPort.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING, 500, 0);
		serPort.setComPortParameters(baudrate, databits, stopbits, parity);
		serPort.setDTR();
		
		// Try to open port..
		if (!serPort.openPort()) {
			// Port not available
			log.error(serPortStr + " is currently not available.");
			return false;			
		} else {
			// Port available
			log.debug(serPortStr + " successfully opened.");

			buffReader = new BufferedReader(new InputStreamReader(serPort.getInputStream(), "UTF-8"));
			printStream = new PrintStream(serPort.getOutputStream(), true, "UTF-8");

			log.info(serPortStr + " connection established.");
			
			// Check if terminal is responding to AT command
			if (send("AT", sleepMillis + 500, false)) {
				log.info(serPortStr + " is responding. Success!");
				Thread.currentThread().setName(ManagementFactory.getRuntimeMXBean().getName() + " " + serPortStr); // Update thread name
				return true; // success
			} else {
				log.error(serPortStr + " wasn't responding.");
				close(false);
				return false; // failed. terminal wasn't responding.
			}
		}
	}

	private void initAtCmd() throws InterruptedException {
		
		if (opMode == 1) {
			// Switch to Explicit Response (ER) and restart device
			log.info("Switch to Explicit Response (ER)");
			send("AT^SSTA=1,1"); // Explicit Response Mode UCS2
			shutdownAndExit();
			return; // exit
		} else if (opMode == 2) {
			// Switch to Automatic Response (AR) and restart device
			log.info("Switch to Automatic Response (AR)");
			send("AT^SSTA=0"); // Automatic Response Mode
			shutdownAndExit();
			return; // exit
		} else {
			
			// User Emulation operation mode
			
			send("AT+CGMM"); // Request model identification
			
			send("ATE0"); // Turn off echo mode
			
			send("AT+CMEE=2"); // Switch on verbose error messages
			
			send("AT+CNUM"); // MSISDN; update thread name
			
			send("AT+CIMI"); // IMSI

			send("AT+CGSN"); // IMEI
			
			send("AT+CMGF=1"); // Set SMS text mode
			
			send("AT+CNMI=1,1"); // Activate the display of a URC on every received SMS
			
			send("AT+CSQ"); // Signal Strength
			
			send("AT+COPS?"); // Provider + access technology
			
			send("AT^SSTR?", null); // Check for STK Menu initialization 
		}
	}
	
	private void listenForRx() throws InterruptedException, UnsupportedEncodingException, IOException {

		// timer variables
		long heartBeatTimerCurrent, rspTimerCurrent;
		heartBeatTimerCurrent = rspTimerCurrent = System.currentTimeMillis();
		
		String code, rx;
		int value = 0;
		boolean ackCmdRequired = false;
		
		// Start endless loop...
		while (isAlive) {
			
			Thread.sleep(sleepMillis);
			
			// Enter this condition if heart beat timer is up
			if ((System.currentTimeMillis() - heartBeatTimerCurrent) >= heartBeatMillis){
				// Check every x milliseconds of inactivity
				
				log.debug(serPortStr + " heart beat test");
				
				send("AT", null); // Send "AT". Next RX shall be received in this thread as it could be some other event coming in.
				
				heartBeatTimerCurrent = System.currentTimeMillis();
			} 
			
			// Condition below should only occur if no RX received even after heart beat timer
			else if ((System.currentTimeMillis() - rspTimerCurrent) >= (heartBeatMillis + 5000)) {
				// Didn't get any response 
				log.error(serPortStr + " down? Trying to re-connect.");
				close(true);
				lookupSerialPort();
				initAtCmd();
				send("AT^SSTR?", null); // Check for STK Menu initialization 
				// reset all timers
				rspTimerCurrent = System.currentTimeMillis();
				heartBeatTimerCurrent = rspTimerCurrent;	
			}
			
			// Listening for incoming notifications (SIM->ME)
			try {
				
				log.trace("Waiting for RX data..");
				
				while (isAlive && buffReader.ready() && (rx = buffReader.readLine()) != null && rx.length() > 0) {
					
					// reset all timers as we have received RX data
					rspTimerCurrent = System.currentTimeMillis();
					heartBeatTimerCurrent = rspTimerCurrent;
					
					log.debug("<<< RX " + rx);
	
					getMeTextAscii(rx); // may set the flag such as CANCEL	
	
					if (rx.toUpperCase().startsWith("+CMTI: ")) {
						value = Integer.parseInt((rx.substring(13, rx.length()))); // +CMTI: "SM", 0
						log.info("TEXT MESSAGE (SMS)");
						send("AT+CMGR=" + value); // read the SMS data
						send("AT+CMGD=0,4"); // delete all stored short messages after reading
					} else if (rx.toUpperCase().startsWith("^SSTR: ")) {	
						// ^SSTR: 3,19
						// ^SSTR: 19,0,"" --> ignore this one (NumberFormatException catched and ignored)
						value = Integer.parseInt(rx.substring(9, rx.length())); // ^SSTR: ?,XX

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
								log.info("SEND MESSAGE (Command Code 19)");
								send("at^sstgi=" + value); // GetInfos		
								send("at^sstr=" + value + ",0"); // Confirm
							}
							ackCmdRequired = false;
							break;
						case 32: // ^SSTR: 3,32
							if (ackCmdRequired) {
								// PLAY TONE
								log.info("PLAY TONE (Command Code 32)");
								send("at^sstgi=" + value); // GetInfos
								send("at^sstr=" + value + ",0"); // Confirm
							}
							ackCmdRequired = false;
							break;
						case 33: // ^SSTR: 3,33
							if (ackCmdRequired) {
								// DISPLAY TEXT
								log.info("DISPLAY TEXT (Command Code 33)");
								send("at^sstgi=" + value); // GetInfos
								getMeTextAscii(rx); // may set the flag such as CANCEL
								code = "0"; // OK
								if (cancel){ 
									setCancel(false); // reset flag
									code = "16"; // Proactive SIM session terminated by user
								} else if (stk_timeout) {
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
								log.info("GET INPUT (Command Code 35)");
								send("at^sstgi=" + value); // GetInfos
								getMeTextAscii(rx); // may set the flag such as CANCEL						
								code = "0,," + validPIN; // OK
								if (cancel){ 
									setCancel(false); // reset flag
									code = "16"; // Proactive SIM session terminated by user
								} else if (block_pin) {
									log.info("BLOCKPIN: Input wrong PIN. Attempt " + (maxWrongPinAttempts-cntrWrongPinAttempts+1) + " out of " + maxWrongPinAttempts + ".");
									if (--cntrWrongPinAttempts == 0) {
										setBlockedPIN(false); // reset flag
										cntrWrongPinAttempts = maxWrongPinAttempts;
									}
									code = "0,," + invalidPIN; 
								} else if (stk_timeout) {
									setStkTimeout(false); // reset flag
									code = "18"; // No response from user
								} else {
									getInputTimerFlag = true;
								}
								send("at^sstr=" + value + "," + code); // Confirm
							}
							ackCmdRequired = false;
							break;
						case 37: // ^SSTR: 3,37
							if (ackCmdRequired) {
								// SET UP MENU
								log.info("SET UP MENU (Command Code 37)");
								send("at^sstgi=" + value, "SSTGI"); // GetInfos
								send("at^sstr=" + value + ",0"); // Confirm
							}
							ackCmdRequired = false;
							break;
						default:
							break;
						}
					} else if (rx.toUpperCase().startsWith("^SSTN: ")) {
						value = Integer.parseInt(rx.substring(7, rx.length())); // ^SSTN: 19

						// Check Proactive Command Type
						switch (value) {
						case 19:
							if (getInputTimerFlag && getInputTimerKeyGenFlag) {
								long timer = System.currentTimeMillis() - getInputTimer;
								int seconds = (int) (timer/1000);
								long millis = timer - seconds * 1000;
								log.info("KeyGen took " + String.format("%02d", seconds) + "," + String.format("%03d", millis) + " seconds");
								getInputTimerFlag = getInputTimerKeyGenFlag = false;
							}
							
							// SEND MESSAGE
							log.info("SEND MESSAGE (Command Code 19)");
							send("at^sstgi=" + value); // GetInfos		
							send("at^sstr=" + value + ",0"); // Confirm
							break;
						case 32:
							// PLAY TONE
							log.info("PLAY TONE (Command Code 32)");
							send("at^sstgi=32");
							send("at^sstr=32,0"); // TerminalResponse=0 (OK)
							break;
						case 33:
							// DISPLAY TEXT
							log.info("DISPLAY TEXT (Command Code 33)");
							send("at^sstgi=33");
							getMeTextAscii(rx); // may set the flag such as CANCEL
							code = "0"; // OK
							if (cancel){ 
								setCancel(false); // reset flag
								code = "16"; // Proactive SIM session terminated by user
							} else if (stk_timeout) {
								setStkTimeout(false); // reset flag
								code = "18"; // No response from user
							}

							send("at^sstr=33," + code); // Confirm
							break;
						case 35:
							// GET INPUT (Input=123456)
							log.info("GET INPUT (Command Code 35)");
							send("at^sstgi=35");
							getMeTextAscii(rx); // may set the flag such as CANCEL							
							code = "0,," + validPIN; // OK
							if (cancel){ 
								setCancel(false); // reset flag
								code = "16"; // Proactive SIM session terminated by user
							}
							else if (stk_timeout) {
								setStkTimeout(false); // reset flag
								code = "18"; // No response from user
							} else if (block_pin) {
								log.info("BLOCKPIN: Input wrong PIN. Attempt " + (maxWrongPinAttempts-cntrWrongPinAttempts+1) + " out of " + maxWrongPinAttempts + ".");
								if (--cntrWrongPinAttempts == 0) {
									setBlockedPIN(false); // reset flag
									cntrWrongPinAttempts = maxWrongPinAttempts;
								}
								code = "0,," + invalidPIN; 
							} else {
								getInputTimerFlag = true;
							}
							send("at^sstr=" + value + "," + code); // Confirm
							break;
						case 36:
							// SELECT ITEM
							log.info("SELECT ITEM (Command Code 36)");
							send("at^sstgi=36"); // GetInformation
							break;
						case 37:
							// SET UP MENU
							log.info("SET UP MENU (Command Code 37)");
							send("at^sstgi=37"); // Get Information
							send("at^sstr=37,0"); // Remote-SAT Response
							break;
						case 254:
							log.info("SIM Applet returns to main menu (Command Code 254)");
							break;
						default:
							break;
						}
					}			
					
				}
			} catch (NumberFormatException e) {
				// Ignore the ^SSTR: 19,0,"" cases
			} 
		}
	}
	
	public boolean send(String cmd, long timeout, boolean sstr) {
		return send(cmd, "ok", timeout, sstr);
	}
	
	public boolean send(String cmd) {
		return send(cmd, "ok");
	}
	
	public boolean send(String cmd, String expectedRsp) {
		return send(cmd, expectedRsp, 0, true);
	}
	
	public boolean send(String cmd, String expectedRsp, boolean sstr) {
		return send(cmd, expectedRsp, 0, sstr);
	}
	
	public boolean send(String cmd, String expectedRsp, long timeout, boolean sstr) {
		try {
			log.debug(">>> TX " + cmd);
			printStream.write((cmd + "\r\n").getBytes());
			
			if (expectedRsp != null)
				return getRx(expectedRsp, timeout, sstr);
			else
				return true;
		} catch (IOException e) {
			log.error("send() IOException : ", e);
			return false;
		}
	}

	private boolean getRx(String expectedRx, long timeout, boolean sstr) {
		try {
			String compareStr;
			if (expectedRx == null)
				compareStr = "OK";
			else
				compareStr = expectedRx.toUpperCase();

			long startTime = System.currentTimeMillis();

			String rx;
			int value;
			
			if (timeout == 0)
				timeout = 5000; // default
			
			Pattern pattern = Pattern.compile(smsPattern);
			Matcher matcher = null;

			while (true) {
				
				// Wait buffered reader to have data available.
				
				if ((System.currentTimeMillis() - startTime) >= timeout){
					log.error(serPortStr + " timeout (" + timeout + "ms) waiting for response '" + expectedRx + "'");
					if (sstr) 
						send("AT^SSTR?", null); // Check status, this may help.
					return false;
				}
		
				while (isAlive && buffReader.ready() && (rx = buffReader.readLine()) != null) {
					
					// Data is available. Read it all.
					
					if (rx.length() > 0) {
						log.debug("<<< RX " + rx);
						
						getMeTextAscii(rx);
						
						matcher = pattern.matcher(rx);
											
						if (matcher.matches() && smsTargetMsisdn != null) {
							
							// Text Short Message Keyword detected
							log.info("Detected Text SMS with keyword: \"" + rx + "\"");
							log.info("Forward Text SMS to " + smsTargetMsisdn);

							// Forward SMS to configured target MSISDN
						    send("AT+CMGS=" + quote + smsTargetMsisdn + quote + ",145"); 
						    Thread.sleep(500);
						    send(rx + ctrlz, "+CMGS");
						    
						    if (smsURL != null && smsAuthName != null && smsAuthPassword != null) {
						    	// Call URL to forward full SMS content
							    log.info("Call URL to forward the SMS value " + rx);
							    publishSMS(rx); // any potential whitespace will be replaced with &nbsp;
						    }
						    
						} else if (rx.toUpperCase().startsWith("+CNUM: ")) {
							// <<< RX +CNUM: ,"+41797373717",145

							msisdn = Arrays.asList(rx.split(",")).get(1).replace("\"", "");
							Thread.currentThread().setName(Thread.currentThread().getName() + " " + msisdn);

						} else if (rx.toUpperCase().startsWith("+COPS: ")) {
							value = Integer.parseInt( Arrays.asList(rx.split(",")).get(3) ); // +COPS: 0,0,"Swisscom",7
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
							if (getInputTimerFlag && getInputTimerKeyGenFlag)
								getInputTimer = System.currentTimeMillis();
							return true; // Got the expected response
						} 
					}
				}	
				Thread.sleep(sleepMillis);
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

			rsp = rsp.substring(rsp.indexOf(",\"") + 2, rsp.indexOf("\",", rsp.indexOf(",\"") + 2));
			rsp = new String(hexToByte(rsp), "UTF-16");
			log.info("TEXT: \"" + rsp + "\"");

			// Check if UI Text contains specific keywords
			if (rsp.indexOf("CANCEL") != -1) {
				setCancel(true);
				log.info("'CANCEL'-keyword detected! Message will be cancelled.");
			} else if (rsp.indexOf("STKTIMEOUT") != -1) {
				setStkTimeout(true);
				log.info("'STKTIMEOUT'-keyword detected! Message will time out.");
			} else if (rsp.indexOf("BLOCKPIN") != -1) {
				setBlockedPIN(true);
				log.info("'BLOCKPIN'-keyword detected! Mobile ID PIN will be blocked.");
			} else if (rsp.indexOf("Confirm your new Mobile ID PIN") != -1) {
				getInputTimerKeyGenFlag = true;
			} 
		}
	}

	private void close(boolean closePort) {
		Thread.currentThread().setName(ManagementFactory.getRuntimeMXBean().getName()); // Update thread name

		if (closePort && serPort.isOpen()) {
			log.debug(serPortStr + " trying to close serial port.");
			
			if (serPort.closePort())
				log.debug(serPortStr + " is now closed.");
			else 
				log.error(serPortStr + " is still open but couldn't be closed.");
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
	
	public static synchronized void setBlockedPIN(boolean flag){
		block_pin = flag;
	}
	
	/**
	 * Forward the OTP code value from the SMS to a URL (GET call)
	 * The code value is set in a URL query parameter ("https://www.example.com/script.sh?TXT=Hello&nbsp;World")
	 * The script.sh on that server may read the query parameter to process the SMS code.
	 * @param smsContent Any whitespace will be replaced with '&nbsp'
	 * @return result is the server response
	 * @throws IOException
	 */
	public String publishSMS(String smsContent) throws IOException {
		String authString = smsAuthName + ":" + smsAuthPassword;
		byte[] authEncBytes = Base64.encodeBase64(authString.getBytes());
		String authStringEnc = new String(authEncBytes);
		
		log.info("Calling URL '" + smsURL + smsContent + "' with basic auth " + authString);
		
		URL url = new URL(smsURL + "?" + smsQueryParam + "=" + smsContent.replaceAll(" ", "&nbsp;"));
		URLConnection urlConnection = url.openConnection();
		urlConnection.setRequestProperty("Authorization", "Basic " + authStringEnc);
		InputStream is = urlConnection.getInputStream();
		InputStreamReader isr = new InputStreamReader(is);

		int numCharsRead;
		char[] charArray = new char[1024];
		StringBuffer sb = new StringBuffer();
		while ((numCharsRead = isr.read(charArray)) > 0) {
			sb.append(charArray, 0, numCharsRead);
		}
		String result = sb.toString();
		
		log.info("Server response was '" + result + "'");

		return result;
	}
	
}