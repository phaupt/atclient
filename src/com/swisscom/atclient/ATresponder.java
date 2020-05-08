package com.swisscom.atclient;

import com.fazecast.jSerialComm.*;
import java.io.*;
import java.lang.management.ManagementFactory;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
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
	private String maintenanceFile = null;

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
	private volatile static boolean rat;
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
	
	private String actualCopsMode;
	private String newCopsMode;
	
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
				log.info("Reading Property file at " + atclientCfg);
				prop = readPropertiesFile(atclientCfg);
			} else {
				log.error("Error reading Property file. No -Dconfig.file found.");
				return;
			}
					
			if (System.getProperty("os.name").toLowerCase().contains("win")) {
				portStrArr[0] = prop.getProperty("port.name.windows");
				log.info("Property port.name.windows set to " + portStrArr[0]);
			} else {
				portStrArr[0] = prop.getProperty("port.name.linux");
				log.info("Property port.name.linux set to " + portStrArr[0]);
			}
			
			baudrate = Integer.parseInt(prop.getProperty("port.baudrate").trim());
			log.info("Property port.baudrate set to " + baudrate);
			databits = Integer.parseInt(prop.getProperty("port.databits").trim());
			log.info("Property port.databits set to " + databits);
			stopbits = Integer.parseInt(prop.getProperty("port.stopbits").trim());
			log.info("Property port.stopbits set to " + stopbits);
			parity = Integer.parseInt(prop.getProperty("port.parity").trim());
			log.info("Property port.parity set to " + parity);
			
			atTimeout = Integer.parseInt(prop.getProperty("port.communication.timeout").trim());
			log.info("Property port.communication.timeout set to " + atTimeout);
			
			heartBeatMillis = Integer.parseInt(prop.getProperty("atclient.atcommand.heartbeat").trim());
			log.info("Property atclient.atcommand.heartbeat set to " + heartBeatMillis);
			
			if (prop.getProperty("cops.mode").trim().length() == 1) {
				actualCopsMode = prop.getProperty("cops.mode").trim();
				log.info("Property cops.mode set to " + actualCopsMode);
			} else {
				actualCopsMode = "A";
				log.info("Property cops.mode set to automatic");
			}
			
			if (prop.getProperty("textsms.forward.enable").trim().equals("true")) {
				smsTargetMsisdn = prop.getProperty("textsms.forward.msisdn").trim();
				log.info("Property textsms.forward.msisdn set to " + smsTargetMsisdn);
				smsPattern = prop.getProperty("textsms.forward.pattern");
				log.info("Property textsms.forward.pattern set to " + smsPattern);
			} else {
				smsTargetMsisdn = null;
				smsPattern = null;
				log.info("Property textsms.forward disabled");
			}
			
			if (prop.getProperty("textsms.publish.enable").trim().equals("true")) {
				smsURL = prop.getProperty("textsms.publish.url").trim();
				log.info("Property textsms.publish.url set to " + smsURL);
				smsQueryParam = prop.getProperty("textsms.publish.queryparam").trim();
				log.info("Property textsms.publish.queryparam set to " + smsQueryParam);
				
				if (prop.getProperty("textsms.publish.basicauth.enabled").trim().equals("true")) {
					smsAuthName = prop.getProperty("textsms.publish.basicauth.user").trim();
					log.info("Property textsms.publish.basicauth.user set to " + smsAuthName);
					smsAuthPassword = prop.getProperty("textsms.publish.basicauth.pwd").trim();
					log.info("Property textsms.publish.basicauth.pwd set to " + smsAuthPassword);
				} else {
					smsAuthName = null;
					smsAuthPassword = null;
					log.info("Property textsms.publish.basicauth disabled");
				}
			} else {
				smsURL = null;
				smsQueryParam = null;
				log.info("Property textsms.publish disabled");
			}
			
			if (prop.getProperty("watchdog.enable").trim().equals("true")) {
				watchdogFile = prop.getProperty("watchdog.file").trim();
				log.info("Property watchdog.file set to " + watchdogFile);
			} else {
				watchdogFile = null;
				watchdogWriter = null;
				log.info("Property watchdog disabled");
			}
			
			if (prop.getProperty("maintenance.enable").trim().equals("true")) {
				maintenanceFile = prop.getProperty("maintenance.script.file").trim();
				log.info("Property maintenance.script.file set to " + maintenanceFile);
			} else {
				maintenanceFile = null;
				log.info("Property maintenance disabled");
			}
			
		} catch (Exception e) {
			log.error("Internal error", e);
		}

		log.info("Application started...");
		attachShutDownHook();
		
		boolean portFound = false;
		try {
			if (serPortStr == null) {
				portFound = lookupSerialPort();
			} else {
				portFound = openPort();
			}
			if (portFound) {
				initAtCmd();
				listenForRx(); // program will stay in the while loop inside this method...
			}
		} catch (Exception e) {
			log.error("Internal error", e);
		}
		
		// The while loop has been normally terminated.
		log.info("Exiting Application");
	}
	
	public static Properties readPropertiesFile(String fileName) throws IOException {
	      FileInputStream fis = null;
	      Properties prop = null;
	      try {
	         fis = new FileInputStream(fileName);
	         prop = new Properties();
	         prop.load(fis);
	      } catch(Exception e) {
	    	  e.printStackTrace();
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
	}
	
	public void shutdownAndExit(){
		log.info("Send SHUTDOWN Command and exit application");
		send("AT+CFUN=1,1"); // force UE restart
		//send("AT^SMSO"); // Power-off the terminal
		isAlive = false; // will exit the while loop and terminate the application	
	}

	private boolean lookupSerialPort() throws UnsupportedEncodingException, IOException, InterruptedException {
		log.info("Start serial port initialization.");
		
		SerialPort[] ports; 
		
		boolean portSuccess = false;
		String portDesc;
		
		while (!portSuccess) {
			ports = SerialPort.getCommPorts();
			
			// reverse the list to have the matching port first
			List<SerialPort> list = Arrays.asList(ports);
		    Collections.reverse(list);
			
			if (opMode == 0) {
				// automatic serial port detection!
				for (SerialPort port : list) {
					
					portDesc = port.getDescriptivePortName();
					
					for (String portStr : portStrArr) {
						// Check for known terminal (port string)
						if (portDesc.contains(portStr)) {
							log.info("Found a serial port: " + port.getSystemPortName() + " " + portDesc);
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
					log.error("Internal error", e);
				}
			}	
		}	
		Thread.sleep(2000);
		return true;
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
			log.debug(serPortStr + " is currently not available.");
			return false;			
		} else {
			// Port available
			log.debug(serPortStr + " successfully opened.");

			buffReader = new BufferedReader(new InputStreamReader(serPort.getInputStream(), "UTF-8"));
			printStream = new PrintStream(serPort.getOutputStream(), true, "UTF-8");

			log.info(serPortStr + " connection established. Let's see if it responds to AT commands.");
			
			// Check if terminal is responding to AT command
			if (send("AT", 1000, false)) {
				log.info(serPortStr + " is responding. Success!");
				Thread.currentThread().setName(ManagementFactory.getRuntimeMXBean().getName() + " " + serPortStr); // Update thread name
				return true; // success
			} else {
				log.info(serPortStr + " wasn't responding. Closing this port.");
				close(false);
				return false; // failed. terminal wasn't responding.
			}
		}
	}

	private void initAtCmd() throws InterruptedException {
		
		if (opMode == 1) {	
			log.info("Switch to Explicit Response (ER) and enable modem usage");
			send("AT^SSTA=1,1"); // enable Explicit Response (ER) Mode with alphabet type UCS2
			
			send("ATI1"); // display product identification information
			
			send("AT^SCFG?"); // Extended Configuration Settings: read command returns a list of all supported parameters and their current values.
			
			send("AT^SSRVSET?"); // list possible settings for the Service Interface Configuration
			send("AT^SSRVSET=\"current\""); // check currently active settings
			
			// Set 1: MDM=ASC0,MUX0 | APP=USB1,MUX1 | NMEA=USB2,MUX2 | RSA=USB3,MUX3
			// Set 2: MDM=USB0,MUX0 | APP=USB1,MUX1 | NMEA=USB2,MUX2 | RSA=USB3,MUX3
			// Set 3: MDM=ASC0,MUX0 | APP=NONE,MUX1 | NMEA=NONE,MUX2 | RSA=NONE,MUX3
			// ASC0 = UART (async serial interface)
			// MDM = Modem, APP = Application
			send("AT^SSRVSET=\"actSrvSet\",2"); // set service set number 2 (USB only; enables modem usage). activated after next UE restart only.
			
			shutdownAndExit();
			return; // exit
		} else if (opMode == 2) {
			log.info("Switch to Automatic Response (AR) and reset AT command settings to factory default values");
			send("AT^SSTA=0"); // enable Automatic Response (AR) Mode
			
			send("AT&F[0]"); // reset AT Command Settings to Factory Default Values
			
			shutdownAndExit();
			return; // exit
		} else {
			
			// CONFIGURATION
			
			if (!send("AT+CPIN?")) // Check if SIM is correctly inserted and SIM PIN is ready
				return;
						
			send("AT+CNUM"); // MSISDN; update thread name
						
			if (!send("ATE1")) // Echo Mode On(1)/Off(0)
				return;
			
			send("AT+CMEE=2"); // Enable reporting of me errors (1 = result code with numeric values; 2 = result code with verbose string values)
			send("AT+CMGF=1"); // Set SMS text mode			
			send("AT+CNMI=1,1"); // Activate the display of a URC on every received SMS
			
			send("AT+CMGD=0,4"); // delete all stored short messages
			
			send("ATI1"); // display product identification information
			send("AT^SCFG?"); // Extended Configuration Settings: read command returns a list of all supported parameters and their current values.
			send("AT^SSRVSET=\"current\""); // check currently active settings			
			
			send("AT+CGMI"); // Module manufacturers
			send("AT+CGMM"); // Module model			
			send("AT+CGSN"); // Module serial number / IMEI			
			send("AT+CIMI"); // IMSI		
			send("AT+CPIN?"); // SIM Card status			
			send("AT+CREG?"); // Network registration
			
			if (!actualCopsMode.contentEquals("A") && actualCopsMode.length() == 1) {
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
				send("AT+COPS=0,2,00000," + actualCopsMode, "OK", 60000, true); // increase the time waiting for "OK" as it usually takes a few seconds to switch the mode
			} else if (actualCopsMode.contentEquals("A")) {
				// Set automatic mode
				send("AT+COPS=0", "OK", 60000, true);
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
				log.error(serPortStr + " down! Trying to find the terminal on a different serial port...");
				close(true);
				
				lookupSerialPort(); // try to find and init the new port

				// reset all timers
				rspTimerCurrent = System.currentTimeMillis();
				heartBeatTimerCurrent = rspTimerCurrent;

				initAtCmd(); // try to init the AT commands
				
				// now continue in this listenForRx() loop...
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

					log.debug("RX1 <<< " + rx);
	
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
								log.info("STK019: SEND MESSAGE");
								send("at^sstgi=" + value); // GetInfos
								send("at^sstr=" + value + ",0"); // Confirm
							}
							ackCmdRequired = false;
							break;
						case 32: // ^SSTR: 3,32
							if (ackCmdRequired) {
								// PLAY TONE
								log.info("STK032: PLAY TONE");
								send("at^sstgi=" + value); // GetInfos
								send("at^sstr=" + value + ",0"); // Confirm
							}
							ackCmdRequired = false;
							break;
						case 33: // ^SSTR: 3,33
							if (ackCmdRequired) {
								// DISPLAY TEXT
								log.info("STK033: DISPLAY TEXT");
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
								log.info("STK035: GET INPUT");
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
								log.info("STK037: SET UP MENU");
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
							log.info("STK019: SEND MESSAGE");
							send("at^sstgi=" + value); // GetInfos		
							send("at^sstr=" + value + ",0"); // Confirm
							break;
						case 32:
							// PLAY TONE
							log.info("STK032: PLAY TONE");
							send("at^sstgi=32");
							send("at^sstr=32,0"); // TerminalResponse=0 (OK)
							break;
						case 33:
							// DISPLAY TEXT
							log.info("STK033: DISPLAY TEXT");
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
							log.info("STK035: GET INPUT");
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
							log.info("STK036: SELECT ITEM");
							send("at^sstgi=36"); // GetInformation
							break;
						case 37:
							// SET UP MENU
							log.info("STK037: SET UP MENU");
							send("at^sstgi=37"); // Get Information
							send("at^sstr=37,0"); // Remote-SAT Response
							break;
						case 254:
							log.info("STK254: SIM Applet returns to main menu");
							
							// STK Process completed. Let's do some regular checks:
							setRAT();
							
							break;
						case 255:
							log.error("SIM is lost!");

							send("AT+CFUN=1,1"); // force UE restart
							
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

	private void setRAT() {
		if (rat) {
			setRAT(false); // reset flag
			
			// Values can be: A=Automatic, 0=2G, 2=3G, 7=4G
			// Compare 'actualCopsMode' and 'newCopsMode'
			
			if (!actualCopsMode.contentEquals(newCopsMode)) {
				// RAT needs to be changed
				
				if (!newCopsMode.contentEquals("A")) {
					log.info("RADIOT: Force new Radio Access Technology");
					
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
					if (send("AT+COPS=0,2,00000," + newCopsMode, "OK", 60000, true)) // increase the time waiting for "OK" as it usually takes a few seconds to switch the mode
						actualCopsMode = newCopsMode; // update actual mode if command was successful
	
				} else if (newCopsMode.contentEquals("A")) {
					log.info("RADIOT: Set Radio Access Technology to automatic mode");
					
					// Set automatic mode
					if (send("AT+COPS=0", "OK", 60000, true))
						actualCopsMode = newCopsMode; // update actual mode if command was successful
					
				}
				
			}
			
		} 
		
		send("AT+COPS?"); // Provider + access technology
		send("AT+CSQ"); // Signal Strength
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
	
			log.debug("TX0 >>> " + cmd);
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
			
			log.trace("Start waiting for response '" + expectedRx + "'");

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
						log.debug("RX2 <<< " + rx);
						
						getMeTextAscii(rx);
						
						if (pattern != null)
							matcher = pattern.matcher(rx);
						
						if (smsTargetMsisdn != null && matcher != null && matcher.matches()) {
							send("AT+CMGD=0,4"); // delete all stored
							
							// Text Short Message Keyword detected
							log.info("Detected Text SMS with keyword: \"" + rx + "\"");
							log.info("Forward Text SMS to " + smsTargetMsisdn);

							// Forward SMS to configured target MSISDN
						    send("AT+CMGS=" + quote + smsTargetMsisdn + quote + ",145"); 
						    Thread.sleep(500);
						    send(rx + ctrlz, "+CMGS");
						    
						    if (smsURL != null) {
						    	// Call URL to forward full SMS content
							    publishSMS(rx); // any potential whitespace will be replaced with &nbsp;
						    }
						    
						} else if (rx.toUpperCase().startsWith("+CNUM: ") && rx.length() > 22) {
							// +CNUM: ,"+41797373717",145
							
							// Be sure that we have expected response format
							if (rx.length() - rx.replaceAll(",","").length() < 2)
								break;

							msisdn = Arrays.asList(rx.split(",")).get(1).replace("\"", "");
							Thread.currentThread().setName(Thread.currentThread().getName() + " " + msisdn);

						} else if (rx.toUpperCase().startsWith("+COPS: ")) {
							// +COPS: 0,0,"Swisscom",7
							
							// Be sure that we have expected response format
							if (rx.length() - rx.replaceAll(",","").length() < 3) {
								if (rx.contentEquals("+COPS: 0"))
									log.error("It seems there is currently no mobile radio reception");
								break;
							}
								
							
							value = Integer.parseInt( Arrays.asList(rx.split(",")).get(3) ); 
							switch (value) {
							case 0: 
								log.info("RADIOT: GSM (2G)");
								break;
							case 1: 
								log.info("RADIOT: GSM Compact (2G)");
								break;
							case 2: 
								log.info("RADIOT: UTRAN (3G)");
								break;
							case 3: 
								log.info("RADIOT: GSM w/EGPRS (2G)");
								break;
							case 4: 
								log.info("RADIOT: UTRAN w/HSDPA (3G)");
								break;
							case 5: 
								log.info("RADIOT: UTRAN w/HSUPA (3G)");
								break;
							case 6: 
								log.info("RADIOT: UTRAN w/HSDPA and HSUPA (3G)");
								break;
							case 7: 
								log.info("RADIOT: E-UTRAN (4G/LTE)");
								break;
							default:
								break;
							}
						} else if (rx.toUpperCase().startsWith("+CSQ: ")) {
							value = Integer.parseInt( rx.substring(6, rx.indexOf(",")) ); // +CSQ: 14,99
							if (value <= 9) {
								log.info("SIGNAL: " + value + "/1-9/31 [#---]");
							} else if (value >= 10 && value <= 14) {
								log.info("SIGNAL: " + value + "/10-14/31 [##--]");
							} else if (value >= 15 && value <= 19) {
								log.info("SIGNAL: " + value + "/15-19/31 [###-]"); 
							} else if (value >= 20 && value <= 31) {
								log.info("SIGNAL: " + value + "/20-31/31 [####]");
							}
						} else if (rx.toUpperCase().startsWith("+CPIN: SIM")) {
							log.error("SIM requires PIN authentication. Please disable SIM PIN.");
							shutdownAndExit();
							return false;
						} else if (rx.toUpperCase().startsWith("+CME ERROR: SIM")) {
							log.error("Please check if SIM is properly inserted.");
							shutdownAndExit();
							return false;
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
			log.info("UI-TXT: \'" + rsp + "\'");

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
			
			if (rsp.indexOf("RAT=") != -1) {
				try {
					String value = rsp.substring(rsp.indexOf("RAT=") + 4, rsp.indexOf("RAT=") + 5);
					// A=Automatic, 0=2G, 2=3G, 7=4G
					if (value.contentEquals("A") || value.contentEquals("0") || value.contentEquals("2") || value.contentEquals("7")) {
						newCopsMode = value;
						setRAT(true);
						log.info("'RAT=" + newCopsMode + "'-keyword detected.");
					} 
				} catch (Exception e) {
					// silently ignore...
				}				
			}
			
			if (rsp.indexOf("REBOOT") != -1) {
				log.info("'REBOOT'-keyword detected. Will invoke 'sudo reboot' command and terminate this program.");
				
				try {
					java.lang.Runtime.getRuntime().exec("sudo reboot");
				} catch (IOException e) {
					log.error("Failed to execute linux command", e);
				}
				
				log.info("Exiting program.");
				System.exit(0);	// Just in case the reboot doesn't work as expected, the watchdog-reboot would be the fall-back
			}
			
			if (rsp.indexOf("MAINTENANCE") != -1 && maintenanceFile != null) {
				log.info("'MAINTENANCE'-keyword detected. Will invoke " + maintenanceFile);

				try {
					ProcessBuilder pb = new ProcessBuilder(maintenanceFile);
					Process p = pb.start();
			        p.waitFor();
			        log.info("Script executed.");
				} catch (IOException | InterruptedException e) {
					log.error("Failed to execute maintenance script.", e);
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
	
	public static synchronized void setRAT(boolean flag){
		rat = flag;
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
	public String publishSMS(String smsContent) {
		try {
			URL url = new URL(smsURL + "?" + smsQueryParam + "=" + smsContent.replaceAll(" ", "&nbsp;"));
			URLConnection urlConnection = url.openConnection();
			log.info("Calling URL '" + smsURL + smsContent);
			
			if (smsAuthName != null && smsAuthPassword != null) {
				String authString = smsAuthName + ":" + smsAuthPassword;
				byte[] authEncBytes = Base64.encodeBase64(authString.getBytes());
				String authStringEnc = new String(authEncBytes);
				urlConnection.setRequestProperty("Authorization", "Basic " + authStringEnc);
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
			
			log.debug("Server response was '" + result + "'");

			return result;
		} catch (Exception e) {
			log.error("Failed to call URL " + smsURL + smsContent);
		} 
		return "";
	}
	
	public void updateWatchdog() {
		try {
			log.trace("Update watchdog file \'" + watchdogFile + "\'");
			watchdogWriter = new BufferedWriter(new FileWriter(watchdogFile));
			watchdogWriter.write("Alive");
			watchdogWriter.close();
		} catch (IOException e) {
			log.error("Failed to update watchdog file at" + watchdogFile, e);
		}
	}

}