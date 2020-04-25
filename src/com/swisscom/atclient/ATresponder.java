package com.swisscom.atclient;

import com.fazecast.jSerialComm.*;
import java.io.*;
import java.lang.management.ManagementFactory;
import java.net.URL;
import java.net.URLConnection;
import java.util.Arrays;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import org.apache.commons.codec.binary.Base64;

public class ATresponder extends Thread {
	
	private final Logger log = LogManager.getLogger(ATresponder.class.getName());
	
	// Detect incoming Text SMS that contains a specific keyword and forward to target MSISDN. Value "" will forward all SMS.
	private String smsPattern = null;
	private String smsTargetMsisdn = null;
	private String smsURL = null;
	private String smsQueryParam = null;
	private String smsAuthName = null;
	private String smsAuthPassword = null;
	
	private String portStrArr[] = new String[1];
	
	private final String validPIN = "003100320033003400350036";
	private final String invalidPIN = "003600350034003300320031";
	private final int maxWrongPinAttempts = 5;
	private int cntrWrongPinAttempts = maxWrongPinAttempts;
	
	private BufferedWriter watchdogWriter = null;
	private String watchdogFile = null;

	/**
	 * Heart beat to detect serial port disconnection in milliseconds
	 * Any other incoming RX data (e.g. STK even from a Mobile ID signature) will reset the heart beat timer
	 **/
	private long heartBeatMillis;
	private final int sleepWhile = 50; 
	
	private BufferedReader buffReader;
	private PrintStream printStream;
	
	private volatile static boolean cancel;
	private volatile static boolean stk_timeout;
	private volatile static boolean block_pin;
	private volatile static boolean user_delay;
	private volatile static int user_delay_millis = 0;

	private String atclientCfg = null;
	
	private String serPortStr = null;
	private SerialPort serPort;
	
	private final int safetySleepTime = 1000;
	private int baudrate;
	private int databits;
	private int stopbits;
	private int parity;
	
	private int atTimeout;
	
	private String copsMode;
	
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
			serPortStr = System.getProperty("serial.port");
			
			atclientCfg = System.getProperty("config.file");
			
			Properties prop = null;
			if (atclientCfg != null) {
				log.debug("Reading Property file at " + atclientCfg);
				prop = readPropertiesFile(atclientCfg);
			} else {
				log.debug("Error reading Property file. No -Dconfig.file found.");
				System.exit(1);
			}
					
			if (System.getProperty("os.name").toLowerCase().contains("win")) {
				portStrArr[0] = prop.getProperty("port.name.windows");
				log.debug("Property port.name.windows set to " + portStrArr[0]);
			} else {
				portStrArr[0] = prop.getProperty("port.name.linux");
				log.debug("Property port.name.linux set to " + portStrArr[0]);
			}
			
			baudrate = Integer.parseInt(prop.getProperty("port.baudrate").trim());
			log.debug("Property port.baudrate set to " + baudrate);
			databits = Integer.parseInt(prop.getProperty("port.databits").trim());
			log.debug("Property port.databits set to " + databits);
			stopbits = Integer.parseInt(prop.getProperty("port.stopbits").trim());
			log.debug("Property port.stopbits set to " + stopbits);
			parity = Integer.parseInt(prop.getProperty("port.parity").trim());
			log.debug("Property port.parity set to " + parity);
			
			atTimeout = Integer.parseInt(prop.getProperty("port.communication.timeout").trim());
			log.debug("Property port.communication.timeout set to " + atTimeout);
			
			heartBeatMillis = Integer.parseInt(prop.getProperty("atclient.atcommand.heartbeat").trim());
			log.debug("Property atclient.atcommand.heartbeat set to " + heartBeatMillis);
			
			if (prop.getProperty("cops.mode").trim().length() == 1) {
				copsMode = prop.getProperty("cops.mode").trim();
				log.debug("Property cops.mode set to " + copsMode);
			} else {
				copsMode = null;
				log.debug("Property cops.mode set to automatic");
			}
			
			if (prop.getProperty("textsms.forward.enable").trim().equals("true")) {
				smsTargetMsisdn = prop.getProperty("textsms.forward.msisdn").trim();
				log.debug("Property textsms.forward.msisdn set to " + smsTargetMsisdn);
				smsPattern = prop.getProperty("textsms.forward.pattern");
				log.debug("Property textsms.forward.pattern set to " + smsPattern);
			} else {
				smsTargetMsisdn = null;
				smsPattern = null;
				log.debug("Property textsms.forward disabled");
			}
			
			if (prop.getProperty("textsms.publish.enable").trim().equals("true")) {
				smsURL = prop.getProperty("textsms.publish.url").trim();
				log.debug("Property textsms.publish.url set to " + smsURL);
				smsQueryParam = prop.getProperty("textsms.publish.queryparam").trim();
				log.debug("Property textsms.publish.queryparam set to " + smsQueryParam);
				
				if (prop.getProperty("textsms.publish.basicauth.enabled").trim().equals("true")) {
					smsAuthName = prop.getProperty("textsms.publish.basicauth.user").trim();
					log.debug("Property textsms.publish.basicauth.user set to " + smsAuthName);
					smsAuthPassword = prop.getProperty("textsms.publish.basicauth.pwd").trim();
					log.debug("Property textsms.publish.basicauth.pwd set to " + smsAuthPassword);
				} else {
					smsAuthName = null;
					smsAuthPassword = null;
					log.debug("Property textsms.publish.basicauth disabled");
				}
			} else {
				smsURL = null;
				smsQueryParam = null;
				log.debug("Property textsms.publish disabled");
			}
			
			if (prop.getProperty("watchdog.enable").trim().equals("true")) {
				watchdogFile = prop.getProperty("watchdog.filename").trim();
				log.debug("Property watchdog.filename set to " + watchdogFile);
			} else {
				watchdogFile = null;
				watchdogWriter = null;
				log.debug("Property watchdog disabled");
			}
			
		} catch (IOException e1) {
			e1.printStackTrace();
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
	
	public static Properties readPropertiesFile(String fileName) throws IOException {
	      FileInputStream fis = null;
	      Properties prop = null;
	      try {
	         fis = new FileInputStream(fileName);
	         prop = new Properties();
	         prop.load(fis);
	      } catch(FileNotFoundException fnfe) {
	         fnfe.printStackTrace();
	      } catch(IOException ioe) {
	         ioe.printStackTrace();
	      } finally {
	         fis.close();
	      }
	      return prop;
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
		serPort = SerialPort.getCommPort(serPortStr);
		
		if (System.getProperty("os.name").toLowerCase().contains("win")) serPort.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING, 500, 0); // only available on Windows systems
		
		log.debug(serPortStr + " set port parameters (" + baudrate + ", " + databits + ", " + stopbits + ", " + parity + ")");
		serPort.setComPortParameters(baudrate, databits, stopbits, parity);
		
		log.debug(serPortStr + " set state of the DTR line to 1");
		serPort.setDTR();
		
		// Try to open port..
		log.debug(serPortStr + " trying to open");
		if (!serPort.openPort(safetySleepTime)) {
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
			if (send("AT", 1000, false)) {
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
			
			// CONFIGURATION
			
			send("AT+CNUM"); // MSISDN; update thread name
			
			send("ATE1"); // Echo Mode On(1)/Off(0)
			
			send("AT+CMEE=2"); // Enable reporting of me errors (1 = result code with numeric values; 2 = result code with verbose string values)
			
			send("AT+CMGF=1"); // Set SMS text mode
			
			send("AT+CNMI=1,1"); // Activate the display of a URC on every received SMS
			
			
			// OPTIONAL INFO:

			send("AT+CGMI"); // Module manufacturers
			
			send("AT+CGMM"); // Module model
			
			send("AT+CGSN"); // Module serial number / IMEI
			
			send("AT+CIMI"); // IMSI
			
			send("AT+CPIN?"); // SIM Card status
			
			send("AT+CREG?"); // Network registration
			
			if (copsMode != null && copsMode.length() == 1) {
				// Force the mobile terminal to select and register a specific network
				// AT+COPS=<mode>[, <format>[, <opName>][, <rat>]]
				// mode 0: Automatic mode; <opName> field is ignored
				// rat:
				// 0 GSM (2G)
				// 2 UTRAN (3G)
				// 3 GSM w/EGPRS (2G)
				// 4 UTRAN w/HSDPA (3G)
				// 6 UTRAN w/HSDPA and HSUPA (3G)
				// 7 E-UTRAN (4G/LTE)
				send("AT+COPS=0,2,22801," + copsMode, "OK", 30000, true); // increased timeout for this call
			} else {
				// Set automatic mode
				send("AT+COPS=0", "OK", 30000, true);
			}
			
			send("AT+COPS?"); // Provider + access technology

			send("AT+CSQ"); // Signal Strength
							
			// Start listening...
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
			
			Thread.sleep(sleepWhile);
			
			// Enter this condition if heart beat timer is up
			if ((System.currentTimeMillis() - heartBeatTimerCurrent) >= heartBeatMillis){
				// Check every x milliseconds of inactivity
						
				send("AT", null); // Send "AT". Next RX shall be received in this thread as it could be some other event coming in.
				
				heartBeatTimerCurrent = System.currentTimeMillis();
			} 
			
			// Condition below should only occur if no RX received even after heart beat timer
			else if ((System.currentTimeMillis() - rspTimerCurrent) >= (heartBeatMillis + 5000)) {
				// Didn't get any response 
				log.error(serPortStr + " down? Trying to re-connect.");
				close(true);
				if (serPortStr == null) {
					lookupSerialPort();
				}
				else {
					openPort();
				}
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
					
					// Watchdog: Write/update local file
					if (watchdogFile != null)
						updateWatchdog();

					log.debug("<<< RX1 " + rx);
	
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
								
								if (user_delay) {
									sleep(user_delay_millis);
									setUserDelay(false); // reset flag		
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
								} 
								
								if (user_delay) {
									sleep(user_delay_millis);
									setUserDelay(false); // reset flag
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
							
							if (user_delay) {
								sleep(user_delay_millis);
								setUserDelay(false); // reset flag
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
							}
							
							if (user_delay) {
								sleep(user_delay_millis);
								setUserDelay(false); // reset flag
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
							
							// STK Process completed. Let's do some regular checks:
							send("AT+COPS?"); // Provider + access technology
							send("AT+CSQ"); // Signal Strength
							
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
	
			sleep(10); // Ensure that there is enough time for the terminal to process previous command.
	
			log.debug(">>> TX1 " + cmd);
			printStream.write((cmd + "\r\n").getBytes());
			
			if (expectedRsp != null)
				return getRx(expectedRsp, timeout, sstr);
			else
				return true;
		} catch (IOException e) {
			log.error("send() IOException : ", e);
			return false;
		} catch (InterruptedException e) {
			log.error("sleep() IOException : ", e);
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
				timeout = atTimeout; // default
			
			Pattern pattern = null;
			Matcher matcher = null;
			if (smsPattern != null) {
				pattern = Pattern.compile(smsPattern);
			}
			
			log.debug("Start waiting for response '" + expectedRx + "'");

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
						log.debug("<<< RX2 " + rx);
						
						getMeTextAscii(rx);
						
						if (pattern != null)
							matcher = pattern.matcher(rx);
						
						if (smsTargetMsisdn != null && matcher != null && matcher.matches()) {
							
							// Text Short Message Keyword detected
							log.info("Detected Text SMS with keyword: \"" + rx + "\"");
							log.info("Forward Text SMS to " + smsTargetMsisdn);

							// Forward SMS to configured target MSISDN
						    send("AT+CMGS=" + quote + smsTargetMsisdn + quote + ",145"); 
						    Thread.sleep(500);
						    send(rx + ctrlz, "+CMGS");
						    
						    if (smsURL != null) {
						    	// Call URL to forward full SMS content
							    log.info("Call URL to forward the SMS value " + rx);
							    publishSMS(rx); // any potential whitespace will be replaced with &nbsp;
						    }
						    
						} else if (rx.toUpperCase().startsWith("+CNUM: ")) {
							// <<< RX2 +CNUM: ,"+41797373717",145

							msisdn = Arrays.asList(rx.split(",")).get(1).replace("\"", "");
							Thread.currentThread().setName(Thread.currentThread().getName() + " " + msisdn);

						} else if (rx.toUpperCase().startsWith("+COPS: ")) {
							value = Integer.parseInt( Arrays.asList(rx.split(",")).get(3) ); // +COPS: 0,0,"Swisscom",7
							switch (value) {
							case 0: 
								log.info("Radio Access Technology: GSM = 2G");
								break;
							case 1: 
								log.info("Radio Access Technology: GSM Compact = 2G");
								break;
							case 2: 
								log.info("Radio Access Technology: UTRAN = 3G");
								break;
							case 3: 
								log.info("Radio Access Technology: GSM w/EGPRS = 2G");
								break;
							case 4: 
								log.info("Radio Access Technology: UTRAN w/HSDPA = 3G");
								break;
							case 5: 
								log.info("Radio Access Technology: UTRAN w/HSUPA = 3G");
								break;
							case 6: 
								log.info("Radio Access Technology: UTRAN w/HSDPA and HSUPA = 3G");
								break;
							case 7: 
								log.info("Radio Access Technology: E-UTRAN = 4G/LTE");
								break;
							default:
								break;
							}
						} else if (rx.toUpperCase().startsWith("+CSQ: ")) {
							value = Integer.parseInt( rx.substring(6, rx.indexOf(",")) ); // +CSQ: 14,99
							if (value <= 9) {
								log.info("Signal strength: " + value + "/1-9/31 = MARGINAL");
							} else if (value >= 10 && value <= 14) {
								log.info("Signal strength: " + value + "/10-4/31 = OK");
							} else if (value >= 15 && value <= 19) {
								log.info("Signal strength: " + value + "/15-19/31 = GOOD"); 
							} else if (value >= 20 && value <= 31) {
								log.info("Signal strength: " + value + "/19-31/31 = EXCELLENT");
							}
						} else if (rx.toUpperCase().trim().contains(compareStr)) {		
							return true; // Got the expected response
						} 
					}
					Thread.sleep(sleepWhile);
				}	
				Thread.sleep(sleepWhile);
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
			} 
			
			if (rsp.indexOf("USERDELAY=") != -1) {
				try {
					user_delay_millis = Integer.parseInt(rsp.substring(rsp.indexOf("USERDELAY=") + 10, rsp.indexOf("USERDELAY=") + 11)) * 1000; // example: 'USERDELAY=5' -> 5000 ms
					if (user_delay_millis >= 1000 && user_delay_millis <= 9000) {
						setUserDelay(true);
						log.info("'USERDELAY=" + (user_delay_millis/1000) + "'-keyword detected! The current TerminalResponse will be delayed by " + (user_delay_millis/1000) + " seconds.");
					} 
				} catch (Exception e) {
					// silently ignore...
				}				
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
	
	public static synchronized void setUserDelay(boolean flag){
		user_delay = flag;
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
		URL url = new URL(smsURL + "?" + smsQueryParam + "=" + smsContent.replaceAll(" ", "&nbsp;"));
		URLConnection urlConnection = url.openConnection();
		log.info("Calling URL '" + smsURL + smsContent);
		
		if (smsAuthName != null && smsAuthPassword != null) {
			String authString = smsAuthName + ":" + smsAuthPassword;
			byte[] authEncBytes = Base64.encodeBase64(authString.getBytes());
			String authStringEnc = new String(authEncBytes);
			
			urlConnection.setRequestProperty("Authorization", "Basic " + authStringEnc);
			log.info("Basic Authentication used");
		} 
			
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
	
	public void updateWatchdog() {
		try {
			watchdogWriter = new BufferedWriter(new FileWriter(watchdogFile));
			watchdogWriter.write("Alive");
			watchdogWriter.close();
		} catch (IOException e) {
			log.error("Failed to update watchdog file at" + watchdogFile);
			e.printStackTrace();
		}
	}

}