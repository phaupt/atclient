package com.swisscom.atclient;

import com.fazecast.jSerialComm.*;
import java.io.*;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class ATresponder extends Thread {
	
	private final Logger log = LogManager.getLogger(ATresponder.class.getName());
	
	private String portStrArr[] = new String[1];
	
	private final String validPIN = "003100320033003400350036";
	private final String invalidPIN = "003600350034003300320031";
	private final int maxWrongPinAttempts = 5;
	private int cntrWrongPinAttempts = maxWrongPinAttempts;
	
	private List<String> watchdogList = Arrays.asList(new String[6]); // RAT-Timestamp, IMSI, Provider, RAT, Signal Strength Percentage, Signal Strength Icon
	private String watchdogFile = null;
	private static final String WATCHDOG_MODE_COMMUNICATION = "communication";
	private static final String WATCHDOG_MODE_ACTIVITY = "activity";
	private static final String WATCHDOG_ACTIVITY_EVENTS_DEFAULT = "19,33,35,37,254";
	private String watchdogMode = WATCHDOG_MODE_COMMUNICATION;
	private Set<Integer> watchdogActivityEventAllowlist = getDefaultWatchdogActivityEventSet();
	private long watchdogActivityStartupGraceMillis = 0;
	private long watchdogStartedAtMillis = 0;
	private String maintenanceFile = null;
	private final Object maintenanceLock = new Object();
	private volatile boolean maintenanceInFlight = false;

	/**
	 * Heart beat to detect serial port disconnection in milliseconds
	 * Any other incoming RX data (e.g. STK even from a Mobile ID signature) will reset the heart beat timer
	 **/
	private long heartBeatMillis;
	private final int sleepWhile = 150; 
	
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
	
	private final int safetySleepTime = 500;
	private int baudrate;
	private int databits;
	private int stopbits;
	private int parity;
	
	private int atTimeout;
	private static final long DEGRADED_DIAGNOSTIC_TIMEOUT_MILLIS = 1200;
	private static final long STARTUP_READINESS_MAX_WAIT_MILLIS = 180000;
	private static final long STARTUP_READINESS_POLL_MILLIS = 5000;
	private static final int DEGRADED_IDLE_CSQ_THRESHOLD = 9;
	private static final int DEGRADED_IDLE_CHECKS_BEFORE_RECOVERY = 2;
	private static final long AUTOMATIC_RADIO_RECOVERY_COOLDOWN_MILLIS = 900000;
	private static final long RADIO_RESELECTION_TIMEOUT_MILLIS = 60000;
	private static final long RADIO_RESELECTION_SETTLE_MILLIS = 2000;
	
	private String actualCopsMode;
	private String newCopsMode;
	private boolean startupSimReady = false;
	private boolean startupRegistrationReady = false;
	private boolean startupNetworkReady = false;
	private boolean startupSignalReady = false;
	private String startupRegistrationSource = "unknown";
	private Integer lastCregStat = null;
	private Integer lastCeregStat = null;
	private String startupProviderName = "n/a";
	private String startupRatLabel = "n/a";
	private boolean startupRadioqCaptured = false;
	private String startupModemVendor = "n/a";
	private String startupModemModel = "n/a";
	private String startupModemRevision = "n/a";
	private String startupModemARevision = "n/a";
	private String startupSrvsetUsbcomp = "n/a";
	private String startupSrvsetMdm = "n/a";
	private String startupSrvsetApp = "n/a";
	private String startupSrvsetNmea = "n/a";
	private String startupSignalSummary = "n/a";
	private Integer lastCsqValue = null;
	private int degradedIdleRadioChecks = 0;
	private long lastAutomaticRadioRecoveryAtMillis = 0;

	private static final Pattern KEYWORD_USERDELAY = Pattern.compile("\\bUSERDELAY=(\\d+)\\b");
	private static final Pattern KEYWORD_RAT = Pattern.compile("\\bRAT=([A072])\\b");
	private static final Pattern KEYWORD_HOSTNAME = Pattern.compile("\\bHOSTNAME=(mobileid0\\d{2})\\b");
	private static final Pattern MODEM_REV_PATTERN = Pattern.compile("^REV(?:ISION)?\\s+(.+)$", Pattern.CASE_INSENSITIVE);
	private static final Pattern MODEM_A_REV_PATTERN = Pattern.compile("^A-REV(?:ISION)?\\s+(.+)$", Pattern.CASE_INSENSITIVE);
	
	private byte opMode; // Switch: 1=ER, 2=AR
	
	private String imsi = null;
	
	public volatile static boolean isAlive = true;

	public ATresponder(byte mode) {
		this.opMode = mode;
	}
	
	public ATresponder(byte mode, String serialPort) {
		this.opMode = mode;
		this.serPortStr = serialPort;
	}

	/** Returns trimmed property value, or throws IllegalStateException if missing. */
	private static String requireProperty(Properties prop, String key) {
		String value = prop.getProperty(key);
		if (value == null || value.trim().isEmpty()) {
			throw new IllegalStateException("Required config property missing or empty: " + key);
		}
		return value.trim();
	}

	/** Returns parsed int property value, or throws IllegalStateException if missing or non-numeric. */
	private static int requireIntProperty(Properties prop, String key) {
		String value = requireProperty(prop, key);
		try {
			return Integer.parseInt(value);
		} catch (NumberFormatException e) {
			throw new IllegalStateException("Config property '" + key + "' must be numeric, got: " + value);
		}
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
				portStrArr[0] = requireProperty(prop, "port.name.windows");
				log.info("Property port.name.windows set to " + portStrArr[0]);
			} else {
				portStrArr[0] = requireProperty(prop, "port.name.linux");
				log.info("Property port.name.linux set to " + portStrArr[0]);
			}
			
			baudrate = requireIntProperty(prop, "port.baudrate");
			log.info("Property port.baudrate set to " + baudrate);
			databits = requireIntProperty(prop, "port.databits");
			log.info("Property port.databits set to " + databits);
			stopbits = requireIntProperty(prop, "port.stopbits");
			log.info("Property port.stopbits set to " + stopbits);
			parity = requireIntProperty(prop, "port.parity");
			log.info("Property port.parity set to " + parity);

			atTimeout = requireIntProperty(prop, "port.communication.timeout");
			log.info("Property port.communication.timeout set to " + atTimeout);

			heartBeatMillis = requireIntProperty(prop, "atclient.atcommand.heartbeat");
			log.info("Property atclient.atcommand.heartbeat set to " + heartBeatMillis);
			
			String configuredCopsMode = prop.getProperty("cops.mode", "").trim().toUpperCase();
			if (configuredCopsMode.length() == 0) {
				actualCopsMode = "A";
				log.info("Property cops.mode set to automatic");
			} else if (configuredCopsMode.contentEquals("A") || configuredCopsMode.contentEquals("0") || configuredCopsMode.contentEquals("2")
					|| configuredCopsMode.contentEquals("7")) {
				actualCopsMode = configuredCopsMode;
				log.info("Property cops.mode set to " + actualCopsMode);
			} else {
				actualCopsMode = "A";
				log.warn("Property cops.mode has invalid value '" + configuredCopsMode + "'. Fallback to automatic mode.");
			}
			
			if (prop.getProperty("watchdog.enable").trim().equals("true")) {
				watchdogFile = prop.getProperty("watchdog.file").trim();
				log.info("Property watchdog.file set to " + watchdogFile);

				String configuredWatchdogMode = prop.getProperty("watchdog.mode", WATCHDOG_MODE_COMMUNICATION).trim().toLowerCase();
				if (WATCHDOG_MODE_ACTIVITY.equals(configuredWatchdogMode) || WATCHDOG_MODE_COMMUNICATION.equals(configuredWatchdogMode)) {
					watchdogMode = configuredWatchdogMode;
				} else {
					watchdogMode = WATCHDOG_MODE_COMMUNICATION;
					log.warn("Property watchdog.mode has unknown value '" + configuredWatchdogMode + "'. Fallback to '" + WATCHDOG_MODE_COMMUNICATION + "'.");
				}
				log.info("Property watchdog.mode set to " + watchdogMode);

				String startupGraceValue = prop.getProperty("watchdog.activity.startup.grace", "0").trim();
				try {
					watchdogActivityStartupGraceMillis = Long.parseLong(startupGraceValue);
					if (watchdogActivityStartupGraceMillis < 0) {
						log.warn("Property watchdog.activity.startup.grace cannot be negative. Fallback to 0.");
						watchdogActivityStartupGraceMillis = 0;
					}
				} catch (NumberFormatException e) {
					log.warn("Property watchdog.activity.startup.grace has non-numeric value '" + startupGraceValue + "'. Fallback to 0.");
					watchdogActivityStartupGraceMillis = 0;
				}
				log.info("Property watchdog.activity.startup.grace set to " + watchdogActivityStartupGraceMillis + "ms");

				boolean activityEventsConfigured = prop.getProperty("watchdog.activity.events") != null;
				watchdogActivityEventAllowlist = parseWatchdogActivityEvents(prop.getProperty("watchdog.activity.events"), activityEventsConfigured);
				if (activityEventsConfigured)
					log.info("Property watchdog.activity.events set to " + formatWatchdogActivityEvents(watchdogActivityEventAllowlist));
				else
					log.info("Property watchdog.activity.events not set. Using default " + formatWatchdogActivityEvents(watchdogActivityEventAllowlist));

				watchdogStartedAtMillis = System.currentTimeMillis();
				log.info("Resolved watchdog settings: mode=" + watchdogMode + ", activity.events=" + formatWatchdogActivityEvents(watchdogActivityEventAllowlist)
						+ ", activity.startup.grace=" + watchdogActivityStartupGraceMillis + "ms");
			} else {
				watchdogFile = null;
				watchdogMode = WATCHDOG_MODE_COMMUNICATION;
				watchdogActivityEventAllowlist = getDefaultWatchdogActivityEventSet();
				watchdogActivityStartupGraceMillis = 0;
				watchdogStartedAtMillis = 0;
				log.info("Property watchdog disabled");
			}
			
			if (prop.getProperty("maintenance.enable").trim().equals("true")) {
				maintenanceFile = prop.getProperty("maintenance.script.file").trim();
				log.info("Property maintenance.script.file set to " + maintenanceFile);
			} else {
				maintenanceFile = null;
				log.info("Property maintenance disabled");
			}
			
		} catch (IllegalStateException e) {
			log.error("Configuration error: " + e.getMessage());
			return;
		} catch (Exception e) {
			log.error("Failed to initialize configuration", e);
			return;
		}

		log.info("Application started...");
		attachShutDownHook();
		
		boolean portFound = false;
		try {
			if (serPortStr == null) {
				portFound = lookupSerialPort(null);
			} else {
				portFound = lookupSerialPort(serPortStr);
			}
			if (portFound) {
				if (initAtCmd()) {
					listenForRx(); // program will stay in the while loop inside this method...
				} else {
					log.error("AT command startup initialization failed. Exiting before steady-state listener loop.");
				}
			}
		} catch (Exception e) {
			log.error("Internal error", e);
		}
		
		// The while loop has been normally terminated.
		log.info("Exiting Application");
	}
	
	public static Properties readPropertiesFile(String fileName) throws IOException {
	      Properties prop = new Properties();
	      try (FileInputStream fis = new FileInputStream(fileName)) {
	         prop.load(fis);
	      }
	      return prop;
	   }

	private boolean lookupSerialPort(String portStrInput) throws UnsupportedEncodingException, IOException, InterruptedException {
		log.info("Start serial port initialization.");
		
		SerialPort[] ports; 
		
		boolean portSuccess = false;
		String portDesc;
		
		while (!portSuccess) {
			ports = SerialPort.getCommPorts();
			
			List<SerialPort> list = new ArrayList<>(Arrays.asList(ports));
			list.sort(Comparator.comparing(SerialPort::getSystemPortName, String.CASE_INSENSITIVE_ORDER));
			
			if (opMode == 0) {
				// automatic serial port detection!
				for (SerialPort port : list) {
					
					portDesc = port.getDescriptivePortName();
					serPortStr = port.getSystemPortName();
					
					for (String portStr : portStrArr) {
						if (portStrInput != null) {
							if (!matchesRequestedPort(portStrInput, serPortStr)) {
								log.info("Found serial port " + serPortStr + " '" + portDesc
										+ "' - skipping (does not exactly match -Dserial.port=" + portStrInput + ").");
								break;
							}
							log.info("Found serial port " + serPortStr + " '" + portDesc
									+ "' - selecting because it exactly matches -Dserial.port.");
							portSuccess = openPort();
							if (portSuccess)
								break;
						} else if (portDesc != null && portDesc.contains(portStr)) {
							log.info("Found serial port " + serPortStr + " '" + portDesc
									+ "' - selecting because description matches configured pattern '" + portStr + "'.");
							portSuccess = openPort();
							if (portSuccess)
								break;
						}
					}
					if (portSuccess)
						break; // success, break iteration for available serial ports
					
					Thread.sleep(sleepWhile); // wait before to proceed with next available port in list
				}

			} else {
				// mode 1 (ER), 2 (AR)
				// serialport was manually defined via argument
				portSuccess = openPort();
			}
			
			if (!portSuccess) {
				log.error("No terminal found yet. Let's try again.");
				sleep(1000);
			}	
		}	
		Thread.sleep(2000);
		return true;
	}
	
	private boolean openPort() throws IOException {
		if (serPortStr == null) {
			log.error("No Port defined. Missing '-Dserial.port' argument?");
			return false;
		}

		try {
			// wait long enough to ensure the port will not be opened too early, causing a port freeze
			sleep(5000);
		} catch (InterruptedException e) {
			log.error("Interrupted during pre-open delay", e);
		} 
			
		serPort = SerialPort.getCommPort(serPortStr);
		
		if (System.getProperty("os.name").toLowerCase().contains("win")) {
			log.debug("Windows detected. Setting serial port read and write timeout parameters.");
			serPort.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING, 500, 0); // only available on Windows systems
		}
		
		log.debug(serPortStr + " set port parameters (" + baudrate + ", " + databits + ", " + stopbits + ", " + parity + ")");
		serPort.setComPortParameters(baudrate, databits, stopbits, parity);
		
		log.debug(serPortStr + " set state of the DTR line to 1");
		serPort.setDTR();
		
		// Try to open port.. the saftySleepTime needs to be 
		log.debug(serPortStr + " trying to open");
		if (!serPort.openPort(safetySleepTime)) {
			// Port not available
			log.debug(serPortStr + " is currently not available.");
			serPort.closePort();
			return false;			
		} else {
			// Port available
			log.debug(serPortStr + " successfully opened.");

			buffReader = new BufferedReader(new InputStreamReader(serPort.getInputStream(), StandardCharsets.UTF_8));
			printStream = new PrintStream(serPort.getOutputStream(), true, StandardCharsets.UTF_8.name());

			log.info(serPortStr + " connection established. Let's see if it responds to AT commands.");
			
			// Check if terminal is responding to AT command
			if (send("AT", 1000, false)) {
				log.info(serPortStr + " is responding. Success!");
				Thread.currentThread().setName(ManagementFactory.getRuntimeMXBean().getName() + " " + serPortStr); // Update thread name
				return true; // success
			} else {
				log.info(serPortStr + " wasn't responding to AT probe. Closing this port cleanly.");
				close(true);
				return false; // failed. terminal wasn't responding.
			}
		}
	}

	private boolean initAtCmd() throws InterruptedException {
		
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
			
			shutdownAndExit(null);
			return false; // exit
		} else if (opMode == 2) {
			log.info("Switch to Automatic Response (AR) and reset AT command settings to factory default values");
			send("AT^SSTA=0"); // enable Automatic Response (AR) Mode
			
			send("AT&F[0]"); // reset AT Command Settings to Factory Default Values
			
			shutdownAndExit(null);
			return false; // exit
		} else {
			
			// CONFIGURATION
			
			if (!send("AT+CPIN?")) { // Check if SIM is correctly inserted and SIM PIN is ready
				log.error("Startup initialization failed: no response for AT+CPIN?.");
				return false;
			}

			send("AT+CNUM"); // MSISDN - which is often not returned with this AT command! :-/
						
			if (!send("ATE0")) { // Echo Mode On(1)/Off(0).
				log.error("Startup initialization failed: no response for ATE0.");
				return false;
			}
			
			send("AT+CMEE=2"); // Enable reporting of me errors (1 = result code with numeric values; 2 = result code with verbose string values)
			send("AT+CMGF=1"); // Set SMS text mode			
			send("AT+CNMI=1,1"); // Activate the display of a URC on every received SMS
			
			send("AT+CMGD=0,4"); // delete all stored short messages
			
			//send("AT^SCFG?"); // Extended Configuration Settings: read command returns a list of all supported parameters and their current values.
			//send("AT^SSRVSET=\"current\""); // check currently active settings			
			
			//send("AT+CGMI"); // Module manufacturers
			//send("AT+CGMM"); // Module model			
			//send("AT+CGSN"); // Module serial number / IMEI			
			
			send("AT+CIMI"); // IMSI		
			send("AT+CPIN?"); // SIM Card status			
			send("AT+CREG?"); // Network registration
			send("AT+CEREG?"); // EPS/LTE registration
			send("ATI1"); // Product identity for conservative startup diagnostics
			send("AT^SSRVSET=\"current\""); // Active service-set diagnostics for USB/Remote-SAT visibility
			
			//send("AT^SMONI"); // supplies information of the serving cell
			//send("ATI1"); // display product identification information
			//send("AT^SCFG=\"SAT/URC\",\"1\""); // enable modem logging
			//send("AT+CEER"); // returns an extended error report (of previous error)
			//send("AT+CEER=0"); // reset the extended error report to initial value
			
			//send("AT&W"); // Store AT Command Settings to User Defined Profile
			
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
			
			if (!waitForStartupReadiness(STARTUP_READINESS_MAX_WAIT_MILLIS, STARTUP_READINESS_POLL_MILLIS)) {
				log.error("Startup readiness was not reached in time. Triggering controlled shutdown/recovery.");
				shutdownAndExit("STARTUP NOT READY");
				return false;
			}
			captureStartupRadioQualitySnapshot();
			logStartupModeDiagnostics();
			
			// Start listening...
			send("AT^SSTR?", null); // Check for STK Menu initialization
			return true;
		}
	}

	private void resetStartupReadinessFlags() {
		startupSimReady = false;
		startupRegistrationReady = false;
		startupRegistrationSource = "unknown";
		startupNetworkReady = false;
		startupSignalReady = false;
		lastCregStat = null;
		lastCeregStat = null;
		startupProviderName = "n/a";
		startupRatLabel = "n/a";
		startupSignalSummary = "n/a";
		lastCsqValue = null;
	}

	private boolean waitForStartupReadiness(long maxWaitMs, long pollMs) throws InterruptedException {
		long startTs = System.currentTimeMillis();
		int attempt = 0;
		while (isAlive && (System.currentTimeMillis() - startTs) < maxWaitMs) {
			attempt++;
			resetStartupReadinessFlags();

			boolean cpinOk = send("AT+CPIN?");
			boolean cregOk = send("AT+CREG?");
			boolean ceregOk = send("AT+CEREG?");
			boolean copsOk = send("AT+COPS?");
			boolean csqOk = send("AT+CSQ");

			if (cpinOk && (cregOk || ceregOk) && copsOk && csqOk
					&& startupSimReady && startupRegistrationReady && startupNetworkReady && startupSignalReady) {
				log.info("Startup readiness confirmed after attempt " + attempt + ".");
				return true;
			}

			log.warn("Startup readiness pending (attempt " + attempt + "): SIM=" + startupSimReady
					+ ", REGISTRATION=" + startupRegistrationReady + "(" + startupRegistrationSource + ")"
					+ ", NETWORK=" + startupNetworkReady + ", SIGNAL=" + startupSignalReady
					+ ", CMDOK[CPIN/CREG/CEREG/COPS/CSQ]=" + cpinOk + "/" + cregOk + "/" + ceregOk + "/" + copsOk + "/" + csqOk + ".");
			long remainingMs = maxWaitMs - (System.currentTimeMillis() - startTs);
			if (remainingMs > 0)
				sleep(Math.min(pollMs, remainingMs));
		}

		log.error("Startup readiness timeout after " + maxWaitMs + "ms: SIM=" + startupSimReady
				+ ", REGISTRATION=" + startupRegistrationReady + "(" + startupRegistrationSource + ")"
				+ ", NETWORK=" + startupNetworkReady + ", SIGNAL=" + startupSignalReady + ".");
		captureDegradedRadioDiagnostics("startup-timeout", true);
		return false;
	}
	
	private void listenForRx() throws InterruptedException, UnsupportedEncodingException, IOException {

		// timer variables
		long heartBeatTimerCurrent, rspTimerCurrent;
		heartBeatTimerCurrent = rspTimerCurrent = System.currentTimeMillis();
		boolean heartbeatAckPending = false;
		boolean heartbeatRadioHealthCheckDue = false;
		
		String code, rx;
		int value = 0;
		boolean ackCmdRequired = false;
		int consecutiveRxFailures = 0;
		
		// Start endless loop...
		while (isAlive) {
			
			Thread.sleep(sleepWhile);
			
			// Enter this condition if heart beat timer is up
			if ((System.currentTimeMillis() - heartBeatTimerCurrent) >= heartBeatMillis) {
				// Check every x milliseconds of inactivity
				send("AT", null); // Send "AT". Next RX shall be received in this thread as it could be some other event coming in.
				heartBeatTimerCurrent = System.currentTimeMillis();
				heartbeatAckPending = true;
				heartbeatRadioHealthCheckDue = false;
			}
			
			// Condition below should only occur if no RX received even after heart beat timer
			else if ((System.currentTimeMillis() - rspTimerCurrent) >= (heartBeatMillis + 5000)) {
				
				// It seems that sometimes we end up in this condition because the HCP's modem crashed
				// In such a case, it seems to be more reliable to invoke a full reboot of the Pi (and HCP terminal/modem)
				log.error(serPortStr + " down! Did the modem crash? I'm afraid we have to invoke a REBOOT right now. Bye bye...");
				rebootAndExit();
				
				/*
				
				// As an alternative to the rebootAndExit(), the code below may be used.
				// In this case we will try to re-open the port - though it does not seem to always work as desired.
				
				log.error(serPortStr + " down! Trying to find the terminal on a different serial port...");
				close(true);
				lookupSerialPort(); // try to find and init the new port

				// reset all timers
				rspTimerCurrent = System.currentTimeMillis();
				heartBeatTimerCurrent = rspTimerCurrent;

				initAtCmd(); // try to init the AT commands
				
				*/
			}
			
			// Listening for incoming notifications (SIM->ME)
			boolean hadRxLoopFailure = false;
			try {
				log.trace("Waiting for RX data..");
				
				while (isAlive && buffReader.ready() && (rx = buffReader.readLine()) != null && rx.length() > 0) {
					
					// reset all timers as we have received RX data
					rspTimerCurrent = System.currentTimeMillis();
					heartBeatTimerCurrent = rspTimerCurrent;
					
					// Watchdog: Write/update local file
					updateWatchdogForCommunicationRx();

						log.debug("RX1 <<< " + rx);
						String normalizedRx = rx.trim().toUpperCase(Locale.ROOT);
						if (heartbeatAckPending) {
							if (isStandaloneFinalResultOk(normalizedRx)) {
								heartbeatRadioHealthCheckDue = true;
								heartbeatAckPending = false;
							} else if (isExplicitModemErrorLine(normalizedRx)) {
								heartbeatAckPending = false;
							}
						}
		
					getMeTextAscii(rx); // may set the flag such as CANCEL	

					if (rx.toUpperCase().startsWith("+CMTI: ")) {
						Integer smsIndex = parseSmsStorageIndex(rx);
						if (smsIndex == null) {
							log.warn("Ignoring malformed +CMTI line '" + rx + "'.");
						} else {
							log.info("TEXT MESSAGE (SMS)");
							send("AT+CMGR=" + smsIndex); // read the SMS data
							send("AT+CMGD=0,4"); // delete all stored short messages after reading
						}
					} else if (rx.toUpperCase().startsWith("^SSTR: ")) {
						ParsedSstr parsedSstr = parseSstrLine(rx);
						if (parsedSstr == null) {
							log.warn("Ignoring malformed ^SSTR line '" + rx + "'.");
							continue;
						}
						value = parsedSstr.commandType;
						updateWatchdogForStkEvent(value);

						// ^SSTR: 2,?? | ^SSTR: 3,?? | ^SSTR: 4,??
						// ^SSTR: <state>,<cmdType>
						// <state>: 0=RESET, 1=OFF, 2=IDLE, 3=PAC, 4=WAIT
						// <cmdType>: only valid in case of <state> is PAC or WAIT
						ackCmdRequired = parsedSstr.ackRequired;
						
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
								if (cancel) {
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
								if (cancel) {
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
								// Some modems emit final OK before the ^SSTGI payload during boot.
								send("at^sstgi=" + value); // GetInfos
								send("at^sstr=" + value + ",0"); // Confirm
							}
							ackCmdRequired = false;
							break;
						default:
							break;
						}
					} else if (rx.toUpperCase().startsWith("^SSTN: ")) {
						Integer sstnValue = parseSstnLine(rx);
						if (sstnValue == null) {
							log.warn("Ignoring malformed ^SSTN line '" + rx + "'.");
							continue;
						}
						value = sstnValue; // ^SSTN: 19
						updateWatchdogForStkEvent(value);

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
							if (cancel) {
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
							if (cancel) {
								setCancel(false); // reset flag
								code = "16"; // Proactive SIM session terminated by user
							} else if (stk_timeout) {
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
							handlePostStkMainMenu();
							send("AT^SSTR?", null);
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

				if (heartbeatRadioHealthCheckDue) {
					heartbeatRadioHealthCheckDue = false;
					handleHeartbeatIdleRadioHealthCheck();
				}
			} catch (IOException | RuntimeException e) {
				hadRxLoopFailure = true;
				consecutiveRxFailures++;
				heartbeatAckPending = false;
				heartbeatRadioHealthCheckDue = false;
				log.error("listenForRx() processing failure (consecutive=" + consecutiveRxFailures + ")", e);
				if (consecutiveRxFailures >= 5) {
					log.error("listenForRx() reached failure threshold. Triggering controlled shutdown/recovery.");
					captureDegradedRadioDiagnostics("rx-loop-failure", true);
					shutdownAndExit("RX LOOP FAILURE");
					return;
				}
				Thread.sleep(Math.min(1000L * consecutiveRxFailures, 5000L));
			}
			if (!hadRxLoopFailure)
				consecutiveRxFailures = 0;
		}
	}

	private void handlePostStkMainMenu() throws InterruptedException {
		verifyRAT();
		refreshIdleRadioStatus("post-STK idle");
		collectIdleRadioDiagnosticsAndRecoverIfNeeded("after STK254");
	}

	private void handleHeartbeatIdleRadioHealthCheck() throws InterruptedException {
		refreshIdleRadioStatus("post-heartbeat idle");
		collectIdleRadioDiagnosticsAndRecoverIfNeeded("after heartbeat");
	}

	private boolean refreshIdleRadioStatus(String context) throws InterruptedException {
		boolean cregOk = send("AT+CREG?");
		boolean ceregOk = send("AT+CEREG?");
		boolean copsOk = send("AT+COPS?");
		boolean csqOk = send("AT+CSQ");
		if ((!cregOk && !ceregOk) || !copsOk || !csqOk) {
			log.warn("RADIOT: " + context + " refresh incomplete. CMDOK[CREG/CEREG/COPS/CSQ]="
					+ cregOk + "/" + ceregOk + "/" + copsOk + "/" + csqOk + ".");
			return false;
		}
		return true;
	}

	private void collectIdleRadioDiagnosticsAndRecoverIfNeeded(String triggerContext) throws InterruptedException {
		boolean degradedIdleRadio = isDegradedIdleRadioState();
		if (!degradedIdleRadio) {
			if (degradedIdleRadioChecks > 0) {
				log.info("RADIOT: degraded idle radio state cleared after " + degradedIdleRadioChecks + " consecutive check(s).");
			}
			degradedIdleRadioChecks = 0;
			return;
		}

		degradedIdleRadioChecks++;
		if (!sendDiagnosticBestEffort("AT+CESQ", DEGRADED_DIAGNOSTIC_TIMEOUT_MILLIS))
			log.warn("RADIOQ: failed to collect +CESQ during degraded idle check " + triggerContext + ".");

		log.warn("RADIOT: degraded idle radio state " + triggerContext + " (count="
				+ degradedIdleRadioChecks + "/" + DEGRADED_IDLE_CHECKS_BEFORE_RECOVERY
				+ ", mode=" + actualCopsMode
				+ ", reg=" + formatRegistrationStat(lastCregStat) + "/" + formatRegistrationStat(lastCeregStat)
				+ ", provider=" + startupProviderName
				+ ", rat=" + startupRatLabel
				+ ", signal=" + startupSignalSummary + ").");

		if (degradedIdleRadioChecks < DEGRADED_IDLE_CHECKS_BEFORE_RECOVERY)
			return;

		if (rat) {
			log.warn("RADIOT: idle radio recovery skipped because a RAT change is still pending.");
			return;
		}

		if (!"A".equals(actualCopsMode)) {
			log.info("RADIOT: idle radio recovery skipped because RAT is forced to " + actualCopsMode + ".");
			return;
		}

		long now = System.currentTimeMillis();
		long sinceLastRecovery = now - lastAutomaticRadioRecoveryAtMillis;
		if (lastAutomaticRadioRecoveryAtMillis > 0 && sinceLastRecovery < AUTOMATIC_RADIO_RECOVERY_COOLDOWN_MILLIS) {
			log.info("RADIOT: idle radio recovery cooldown active (" + sinceLastRecovery + "ms since last attempt).");
			return;
		}

		degradedIdleRadioChecks = 0;

		log.warn("RADIOT: re-triggering automatic network selection after repeated degraded idle radio checks " + triggerContext + ".");
		if (!send("AT+COPS=0", "OK", RADIO_RESELECTION_TIMEOUT_MILLIS, true)) {
			log.warn("RADIOT: automatic network reselection attempt failed.");
			return;
		}
		lastAutomaticRadioRecoveryAtMillis = now;
		log.info("RADIOT: automatic network reselection command accepted. Collecting post-reselection status.");

		sleep(RADIO_RESELECTION_SETTLE_MILLIS);
		if (refreshIdleRadioStatus("post-reselection"))
			log.info("RADIOT: post-reselection refresh completed.");
		if (!sendDiagnosticBestEffort("AT+CESQ", DEGRADED_DIAGNOSTIC_TIMEOUT_MILLIS))
			log.warn("RADIOQ: failed to collect +CESQ after automatic network reselection.");
	}

	private boolean isDegradedIdleRadioState() {
		if (!isRegistrationReady())
			return true;
		if (!startupNetworkReady || !startupSignalReady)
			return true;
		return lastCsqValue == null || lastCsqValue.intValue() <= DEGRADED_IDLE_CSQ_THRESHOLD;
	}

	private boolean isRegistrationReady() {
		return isRegistered(lastCregStat) || isRegistered(lastCeregStat);
	}

	private boolean isRegistered(Integer stat) {
		return stat != null && (stat.intValue() == 1 || stat.intValue() == 5);
	}

	private void verifyRAT() {
		if (!rat)
			return;
		if (newCopsMode == null || newCopsMode.trim().isEmpty()) {
			log.warn("RADIOT: RAT change requested without a target mode. Clearing pending request.");
			setRAT(false);
			return;
		}

		if (actualCopsMode.contentEquals(newCopsMode)) {
			log.info("RADIOT: Requested RAT " + newCopsMode + " is already active.");
			setRAT(false);
			return;
		}

		boolean commandSucceeded = false;
		String previousCopsMode = actualCopsMode;

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
			commandSucceeded = send("AT+COPS=0,2,00000," + newCopsMode, "OK", RADIO_RESELECTION_TIMEOUT_MILLIS, true);

		} else if (newCopsMode.contentEquals("A")) {
			log.info("RADIOT: Set Radio Access Technology to automatic mode");
			
			// Set automatic mode
			commandSucceeded = send("AT+COPS=0", "OK", RADIO_RESELECTION_TIMEOUT_MILLIS, true);
		}

		if (commandSucceeded) {
			actualCopsMode = newCopsMode;
			degradedIdleRadioChecks = 0;
			log.info("RADIOT: Requested RAT change accepted (" + previousCopsMode + " -> " + newCopsMode + ").");
			setRAT(false);
		} else {
			log.warn("RADIOT: Requested RAT change to " + newCopsMode + " failed. Will retry on next STK254.");
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
	
			sleep(sleepWhile); // Ensure that there is enough time for the terminal to process previous command.
	
			log.debug("TX0 >>> " + cmd);
			printStream.write((cmd + "\r\n").getBytes(StandardCharsets.UTF_8));
			
			if (expectedRsp != null)
				return getRx(expectedRsp, timeout, sstr, cmd);
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

	private boolean getRx(String expectedRx, long timeout, boolean sstr, String txCmd) {
		try {
			String compareStr = (expectedRx == null || expectedRx.trim().isEmpty()) ? "OK" : expectedRx.trim().toUpperCase();
			String txCmdUpper = txCmd == null ? "" : txCmd.trim().toUpperCase();
			boolean payloadSensitiveCommand = txCmdUpper.startsWith("AT+CMGR=");
			int payloadLinesToIgnoreForTerminalMatching = 0;

			long startTime = System.currentTimeMillis();

			String rx;
			
			if (timeout == 0)
				timeout = atTimeout; // default
			
			log.trace("Start waiting for response '" + compareStr + "'");

			while (true) {
				
				// Wait buffered reader to have data available.
				
				if ((System.currentTimeMillis() - startTime) >= timeout){
					log.error(serPortStr + " timeout (" + timeout + "ms) waiting for response '" + compareStr
							+ "' (sstrRecoveryHint=" + sstr + ").");
					return false;
				}
		
				while (isAlive && buffReader.ready() && (rx = buffReader.readLine()) != null) {
					
					// Data is available. Read it all.
					
					if (rx.length() > 0) {
						log.debug("RX2 <<< " + rx);
						updateStartupDiagnosticsFromLine(rx);
						
						getMeTextAscii(rx);
						
						if (rx.startsWith("228") && rx.length() == 15) {
							// 228017230302066

							imsi = rx;
							Thread.currentThread().setName(Thread.currentThread().getName() + " " + imsi);
							watchdogList.set(1, imsi); // Update IMSI 

						} else if (rx.toUpperCase().startsWith("+COPS: ")) {
							updateStartupAndWatchdogFromCops(rx);
							 
						} else if (rx.toUpperCase().startsWith("+CSQ: ")) {
							updateStartupAndWatchdogFromCsq(rx);
						} else if (rx.toUpperCase().startsWith("+CPIN: READY")) {
							startupSimReady = true;
						} else if (rx.toUpperCase().startsWith("+CPIN: SIM")) {
							log.error("SIM requires PIN authentication. Please disable SIM PIN.");
							shutdownAndExit("REMOVE SIM PIN");
							return false;
						} else if (rx.toUpperCase().startsWith("+CREG: ")) {
							updateRegistrationStateFromLine(rx, false);
						} else if (rx.toUpperCase().startsWith("+CEREG: ")) {
							updateRegistrationStateFromLine(rx, true);
						} else if (rx.toUpperCase().startsWith("+CESQ: ")) {
							logCesqLine(rx);
						} else if (rx.toUpperCase().startsWith("^SMONI:")) {
							log.info("CELL: " + rx.trim());
						} else if (rx.toUpperCase().startsWith("+CEER:")) {
							log.info("FAILCTX: " + rx.trim());
						} else if (rx.toUpperCase().startsWith("+CME ERROR: SIM")) {
							log.error("Please check if SIM is properly inserted.");
							shutdownAndExit("SIM NOT INSERTED");
							return false;
						} 

						String normalizedRx = rx.trim().toUpperCase();
						if (payloadSensitiveCommand && normalizedRx.startsWith("+CMGR:")) {
							// Following SMS body line can contain arbitrary text that may look like AT final result codes.
							payloadLinesToIgnoreForTerminalMatching = 1;
						} else if (payloadLinesToIgnoreForTerminalMatching > 0) {
							payloadLinesToIgnoreForTerminalMatching--;
						} else {
							if (isExplicitModemErrorLine(normalizedRx)) {
								log.error("Modem returned explicit failure while waiting for '" + compareStr + "': " + rx);
								return false;
							}
							if (isExpectedResponseMatch(normalizedRx, compareStr))
								return true;
							if (isStandaloneFinalResultOk(normalizedRx) && !"OK".equals(compareStr)) {
								log.warn("Received final OK before expected response '" + compareStr + "' for command flow.");
								return false;
							}
						}
						
						// Watchdog: Write/update local file
						updateWatchdogForCommunicationRx();

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
		if (rsp == null || !rsp.contains("^SSTGI:")) {
			return;
		}
		int startIdx = rsp.indexOf(",\"");
		int endIdx = (startIdx >= 0) ? rsp.indexOf("\",", startIdx + 2) : -1;
		if (startIdx < 0 || endIdx <= startIdx + 2) {
			return;
		}
		String ucs2Hex = rsp.substring(startIdx + 2, endIdx).trim();
		if (ucs2Hex.length() == 0) {
			return;
		}
		try {
			String asciiText = new String(hexToByte(ucs2Hex), StandardCharsets.UTF_16);
			log.info("UI-TXT: \'" + asciiText + "\'");
			verifyKeywords(asciiText);
		} catch (RuntimeException e) {
			log.warn("Ignoring malformed ^SSTGI UCS2 payload '" + ucs2Hex + "'.");
		}
	}

	private void verifyKeywords(String rsp) {
		// Check if UI Text contains specific keywords
		if (rsp.contains("CANCEL")) {
			setCancel(true);
			log.info("'CANCEL'-keyword detected! Message will be cancelled.");
		} else if (rsp.contains("STKTIMEOUT")) {
			setStkTimeout(true);
			log.info("'STKTIMEOUT'-keyword detected! Message will time out.");
		} else if (rsp.contains("BLOCKPIN")) {
			setBlockedPIN(true);
			log.info("'BLOCKPIN'-keyword detected! Mobile ID PIN will be blocked.");
		} 
		
		Matcher userDelayMatcher = KEYWORD_USERDELAY.matcher(rsp);
		if (userDelayMatcher.find()) {
			try {
				user_delay_millis = Integer.parseInt(userDelayMatcher.group(1)) * 1000;
				if (user_delay_millis >= 1000 && user_delay_millis <= 9000) {
					setUserDelay(true);
					log.info("'USERDELAY=" + (user_delay_millis/1000) + "'-keyword detected! The current TerminalResponse will be delayed by " + (user_delay_millis/1000) + " seconds.");
				} else {
					log.warn("Ignoring out-of-range USERDELAY value in UI text.");
				} 
			} catch (Exception e) {
				log.warn("Ignoring malformed USERDELAY keyword in UI text.");
			}				
		}
		
		Matcher ratMatcher = KEYWORD_RAT.matcher(rsp);
		if (ratMatcher.find()) {
			newCopsMode = ratMatcher.group(1);
			setRAT(true);
			log.info("'RAT=" + newCopsMode + "'-keyword detected.");
		}
		
		Matcher hostnameMatcher = KEYWORD_HOSTNAME.matcher(rsp);
		if (hostnameMatcher.find()) {
			String value = hostnameMatcher.group(1);
			try {
				new ProcessBuilder("sudo", "/home/mid/setHostName", value).inheritIO().start();
			} catch (IOException e) {
				log.error("Failed to execute setHostName command", e);
			}
			log.info("'HOSTNAME=" + value + "'-keyword detected. Will change hostname to " + value);
		}
		
		if (rsp.contains("MAINTENANCE") && maintenanceFile != null) {
			log.info("'MAINTENANCE'-keyword detected. Will invoke " + maintenanceFile);
			invokeMaintenanceScriptAsync();
		}
		
		if (rsp.contains("REBOOT")) {
			log.info("'REBOOT'-keyword detected. Will invoke 'sudo reboot' command and terminate this program.");
			
			rebootAndExit();
		}
	}

	private void invokeMaintenanceScriptAsync() {
		if (maintenanceFile == null || maintenanceFile.trim().isEmpty()) {
			log.warn("MAINTENANCE keyword ignored because maintenance.script.file is not configured.");
			return;
		}
		synchronized (maintenanceLock) {
			if (maintenanceInFlight) {
				log.info("MAINTENANCE request ignored because a maintenance script is already running.");
				return;
			}
			maintenanceInFlight = true;
		}
		Thread maintenanceThread = new Thread(() -> {
			try {
				ProcessBuilder pb = new ProcessBuilder(maintenanceFile);
				Process process = pb.start();
				int exitCode = process.waitFor();
				log.info("Maintenance script finished with exit code " + exitCode + ".");
			} catch (IOException e) {
				log.error("Failed to execute maintenance script.", e);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				log.error("Maintenance script execution interrupted.", e);
			} finally {
				synchronized (maintenanceLock) {
					maintenanceInFlight = false;
				}
			}
		}, "maintenance-script");
		maintenanceThread.setDaemon(true);
		maintenanceThread.start();
	}

	private boolean matchesRequestedPort(String portArgument, String systemPortName) {
		if (portArgument == null || systemPortName == null) {
			return false;
		}
		String normalizedInput = portArgument.trim();
		if (normalizedInput.isEmpty()) {
			return false;
		}
		if (normalizedInput.equals(systemPortName)) {
			return true;
		}
		String fullPath = normalizedInput.startsWith("/dev/") ? normalizedInput : ("/dev/" + normalizedInput);
		return fullPath.equals("/dev/" + systemPortName);
	}

	private boolean isStandaloneFinalResultOk(String normalizedLineUpper) {
		return "OK".equals(normalizedLineUpper);
	}

	private boolean isExplicitModemErrorLine(String normalizedLineUpper) {
		return "ERROR".equals(normalizedLineUpper)
				|| normalizedLineUpper.startsWith("+CME ERROR:")
				|| normalizedLineUpper.startsWith("+CMS ERROR:");
	}

	private boolean isExpectedResponseMatch(String normalizedLineUpper, String expectedUpper) {
		if (expectedUpper == null || expectedUpper.trim().isEmpty() || "OK".equals(expectedUpper))
			return isStandaloneFinalResultOk(normalizedLineUpper);
		if ("ERROR".equals(expectedUpper) || "+CME ERROR".equals(expectedUpper) || "+CMS ERROR".equals(expectedUpper))
			return normalizedLineUpper.equals(expectedUpper) || normalizedLineUpper.startsWith(expectedUpper + ":");
		return normalizedLineUpper.contains(expectedUpper);
	}

	private void updateRegistrationStateFromLine(String rawLine, boolean epsRegistration) {
		Integer stat = parseRegistrationStatus(rawLine);
		if (stat == null) {
			String registrationType = epsRegistration ? "CEREG" : "CREG";
			log.warn("Ignoring malformed +" + registrationType + " line '" + rawLine + "'.");
			return;
		}
		String registrationType = epsRegistration ? "CEREG" : "CREG";
		Integer previousStat = epsRegistration ? lastCeregStat : lastCregStat;
		if (epsRegistration)
			lastCeregStat = stat;
		else
			lastCregStat = stat;
		if (previousStat == null || previousStat.intValue() != stat.intValue())
			log.info("REG: " + registrationType + " stat=" + stat + " (" + decodeRegistrationState(stat) + ")");

		startupRegistrationReady = isRegistrationReady();
		if (isRegistered(lastCeregStat))
			startupRegistrationSource = "CEREG:" + lastCeregStat;
		else if (isRegistered(lastCregStat))
			startupRegistrationSource = "CREG:" + lastCregStat;
		else
			startupRegistrationSource = registrationType + ":" + stat;
	}

	private void logStartupModeDiagnostics() {
		log.info("MODEM: " + buildModemSummary());
		log.info("SRVSET: " + buildSrvsetSummary());
		log.info("REG: startupSummary source=" + startupRegistrationSource
				+ ", CREG=" + formatRegistrationStat(lastCregStat)
				+ ", CEREG=" + formatRegistrationStat(lastCeregStat)
				+ ", ready=" + startupRegistrationReady);
		log.info("STARTUP: serial=" + serPortStr
				+ ", modem=" + buildModemIdentityLabel()
				+ ", reg=" + formatStartupRegistrationSummary()
				+ ", provider=" + startupProviderName
				+ ", rat=" + startupRatLabel
				+ ", signal=" + startupSignalSummary
				+ ", radioqSnapshot=" + startupRadioqCaptured);
	}

	private void captureStartupRadioQualitySnapshot() {
		if (send("AT+CESQ")) {
			startupRadioqCaptured = true;
		} else {
			log.warn("RADIOQ: startup snapshot command failed.");
		}
	}

	private void captureDegradedRadioDiagnostics(String reason, boolean includeCellSnapshot) {
		log.info("FAILCTX: collecting degraded-path diagnostics (" + reason + ")");
		if (!sendDiagnosticBestEffort("AT+CESQ", DEGRADED_DIAGNOSTIC_TIMEOUT_MILLIS))
			log.warn("RADIOQ: failed to collect +CESQ during " + reason + ".");
		if (includeCellSnapshot && !sendDiagnosticBestEffort("AT^SMONI", DEGRADED_DIAGNOSTIC_TIMEOUT_MILLIS))
			log.warn("CELL: failed to collect ^SMONI during " + reason + ".");
		if (!sendDiagnosticBestEffort("AT+CEER", DEGRADED_DIAGNOSTIC_TIMEOUT_MILLIS))
			log.warn("FAILCTX: failed to collect +CEER during " + reason + ".");
	}

	private boolean sendDiagnosticBestEffort(String cmd, long timeoutMillis) {
		// Keep degraded-path diagnostics bounded so they don't block recovery for full default timeouts.
		return send(cmd, "OK", timeoutMillis, false);
	}

	private void logCesqLine(String cesqLine) {
		Integer[] fields = parseCesqFields(cesqLine);
		if (fields == null) {
			log.info("RADIOQ: " + cesqLine.trim());
			return;
		}
		log.info("RADIOQ: +CESQ"
				+ " rxlev=" + fields[0]
				+ " ber=" + fields[1]
				+ " rscp=" + fields[2]
				+ " ecno=" + fields[3]
				+ " rsrq=" + formatCesqRsrq(fields[4])
				+ " rsrp=" + formatCesqRsrp(fields[5]));
	}

	private Integer[] parseCesqFields(String cesqLine) {
		int colonIdx = cesqLine.indexOf(':');
		if (colonIdx < 0 || colonIdx + 1 >= cesqLine.length())
			return null;
		String[] parts = cesqLine.substring(colonIdx + 1).trim().split(",");
		if (parts.length < 6)
			return null;
		Integer[] values = new Integer[6];
		for (int i = 0; i < 6; i++) {
			values[i] = safeParseInteger(parts[i]);
			if (values[i] == null)
				return null;
		}
		return values;
	}

	private String formatRegistrationStat(Integer stat) {
		if (stat == null)
			return "n/a";
		return stat + "(" + decodeRegistrationState(stat) + ")";
	}

	private String decodeRegistrationState(int stat) {
		switch (stat) {
		case 1:
			return "home-registered";
		case 5:
			return "roaming-registered";
		case 2:
			return "searching";
		case 3:
			return "registration-denied";
		case 4:
			return "unknown";
		case 0:
			return "not-registered";
		default:
			return "stat-" + stat;
		}
	}

	private String getRatLabelForSummary(int ratValue) {
		switch (ratValue) {
		case 0:
		case 1:
		case 3:
			return "2G";
		case 2:
		case 4:
		case 5:
		case 6:
			return "3G";
		case 7:
			return "4G/LTE";
		default:
			return "n/a";
		}
	}

	private Integer parseRegistrationStatus(String registrationLine) {
		if (registrationLine == null) {
			return null;
		}
		int colonIdx = registrationLine.indexOf(':');
		if (colonIdx < 0 || colonIdx + 1 >= registrationLine.length()) {
			return null;
		}
		String[] parts = registrationLine.substring(colonIdx + 1).trim().split(",");
		if (parts.length == 0) {
			return null;
		}
		if (parts.length >= 2) {
			Integer second = safeParseInteger(parts[1]);
			if (second != null) {
				return second;
			}
		}
		return safeParseInteger(parts[0]);
	}

	private void updateStartupDiagnosticsFromLine(String rawLine) {
		if (rawLine == null) {
			return;
		}
		String trimmed = rawLine.trim();
		String normalized = trimmed.toUpperCase(Locale.ROOT);
		updateModemIdentityFromLine(trimmed, normalized);
		if (normalized.startsWith("^SSRVSET:"))
			updateServiceSetFromLine(trimmed);
	}

	private String buildModemSummary() {
		StringBuilder sb = new StringBuilder();
		appendSummaryField(sb, "vendor", startupModemVendor);
		appendSummaryField(sb, "model", startupModemModel);
		appendSummaryField(sb, "revision", startupModemRevision);
		if (!"n/a".equals(startupModemARevision))
			appendSummaryField(sb, "a-revision", startupModemARevision);
		appendSummaryField(sb, "serial", serPortStr == null ? "n/a" : serPortStr);
		return sb.toString();
	}

	private String buildSrvsetSummary() {
		StringBuilder sb = new StringBuilder();
		appendSummaryField(sb, "usbcomp", startupSrvsetUsbcomp);
		appendSummaryField(sb, "MDM", startupSrvsetMdm);
		appendSummaryField(sb, "APP", startupSrvsetApp);
		appendSummaryField(sb, "NMEA", startupSrvsetNmea);
		return sb.toString();
	}

	private String buildModemIdentityLabel() {
		StringBuilder sb = new StringBuilder();
		if (!"n/a".equals(startupModemVendor))
			sb.append(startupModemVendor);
		if (!"n/a".equals(startupModemModel)) {
			if (sb.length() > 0)
				sb.append(" ");
			sb.append(startupModemModel);
		}
		if (!"n/a".equals(startupModemRevision)) {
			if (sb.length() > 0)
				sb.append(" ");
			sb.append("REV ").append(startupModemRevision);
		}
		if (!"n/a".equals(startupModemARevision)) {
			if (sb.length() > 0)
				sb.append(" ");
			sb.append("(A-REV ").append(startupModemARevision).append(")");
		}
		if (sb.length() == 0)
			return "n/a";
		return sb.toString();
	}

	private String formatStartupRegistrationSummary() {
		if ("unknown".equals(startupRegistrationSource))
			return "unknown";
		String[] parts = startupRegistrationSource.split(":");
		if (parts.length != 2)
			return startupRegistrationSource;
		Integer stat = safeParseInteger(parts[1]);
		if (stat == null)
			return startupRegistrationSource;
		return parts[0] + ":" + stat + "(" + decodeRegistrationState(stat) + ")";
	}

	private void appendSummaryField(StringBuilder sb, String key, String value) {
		if (value == null || value.trim().isEmpty())
			value = "n/a";
		if (sb.length() > 0)
			sb.append(" ");
		sb.append(key).append("=").append(value);
	}

	private void updateModemIdentityFromLine(String trimmedLine, String normalizedLine) {
		if ("CINTERION".equals(normalizedLine))
			startupModemVendor = "Cinterion";
		else if ("THALES".equals(normalizedLine))
			startupModemVendor = "Thales";

		if (normalizedLine.startsWith("PLS"))
			startupModemModel = trimmedLine;

		Matcher revMatcher = MODEM_REV_PATTERN.matcher(trimmedLine);
		if (revMatcher.matches())
			startupModemRevision = revMatcher.group(1).trim();

		Matcher aRevMatcher = MODEM_A_REV_PATTERN.matcher(trimmedLine);
		if (aRevMatcher.matches())
			startupModemARevision = aRevMatcher.group(1).trim();

	}

	private void updateServiceSetFromLine(String ssrvsetLine) {
		String[] tokens = parseSsrvsetQuotedTokens(ssrvsetLine);
		if (tokens.length < 2)
			return;

		if ("usbcomp".equalsIgnoreCase(tokens[0])) {
			startupSrvsetUsbcomp = tokens[1].trim();
			return;
		}

		if ("srvmap".equalsIgnoreCase(tokens[0]) && tokens.length >= 4) {
			String role = tokens[1].trim().toUpperCase(Locale.ROOT);
			String mapping = tokens[2].trim() + "/" + tokens[3].trim();
			if ("MDM".equals(role))
				startupSrvsetMdm = mapping;
			else if ("APP".equals(role))
				startupSrvsetApp = mapping;
			else if ("NMEA".equals(role))
				startupSrvsetNmea = mapping;
		}
	}

	private String[] parseSsrvsetQuotedTokens(String ssrvsetLine) {
		int colonIdx = ssrvsetLine.indexOf(':');
		if (colonIdx < 0 || colonIdx + 1 >= ssrvsetLine.length())
			return new String[0];

		List<String> tokens = new ArrayList<>();
		Matcher matcher = Pattern.compile("\"([^\"]*)\"").matcher(ssrvsetLine.substring(colonIdx + 1));
		while (matcher.find())
			tokens.add(matcher.group(1));
		return tokens.toArray(new String[0]);
	}

	private String formatCesqRsrq(Integer rsrqRaw) {
		if (rsrqRaw == null || rsrqRaw == 255)
			return "n/a";
		if (rsrqRaw < 0 || rsrqRaw > 34)
			return rsrqRaw + "(raw)";
		double rsrqDb = -19.5 + (0.5 * rsrqRaw);
		return rsrqRaw + "(" + String.format(Locale.ROOT, "%.1f dB", rsrqDb) + ")";
	}

	private String formatCesqRsrp(Integer rsrpRaw) {
		if (rsrpRaw == null || rsrpRaw == 255)
			return "n/a";
		if (rsrpRaw < 0 || rsrpRaw > 97)
			return rsrpRaw + "(raw)";
		int rsrpDbm = -140 + rsrpRaw;
		return rsrpRaw + "(" + rsrpDbm + " dBm)";
	}

	private Integer parseSmsStorageIndex(String cmtiLine) {
		if (cmtiLine == null) {
			return null;
		}
		String[] parts = cmtiLine.split(",");
		if (parts.length < 2) {
			return null;
		}
		return safeParseInteger(parts[parts.length - 1]);
	}

	private ParsedSstr parseSstrLine(String sstrLine) {
		if (sstrLine == null) {
			return null;
		}
		int colonIdx = sstrLine.indexOf(':');
		if (colonIdx < 0 || colonIdx + 1 >= sstrLine.length()) {
			return null;
		}
		String[] parts = sstrLine.substring(colonIdx + 1).trim().split(",");
		if (parts.length < 2) {
			return null;
		}
		Integer state = safeParseInteger(parts[0]);
		Integer commandType = safeParseInteger(parts[1]);
		if (state == null || commandType == null) {
			return null;
		}
		boolean ackRequired = (state == 3 || state == 4);
		return new ParsedSstr(ackRequired, commandType);
	}

	private Integer parseSstnLine(String sstnLine) {
		if (sstnLine == null) {
			return null;
		}
		int colonIdx = sstnLine.indexOf(':');
		if (colonIdx < 0 || colonIdx + 1 >= sstnLine.length()) {
			return null;
		}
		return safeParseInteger(sstnLine.substring(colonIdx + 1).trim());
	}

	private void updateStartupAndWatchdogFromCops(String copsLine) {
		ParsedCops parsedCops = parseCopsLine(copsLine);
		if (parsedCops == null) {
			startupNetworkReady = false;
			startupProviderName = "n/a";
			startupRatLabel = "n/a";
			if ("+COPS: 0".equals(copsLine.trim())) {
				log.warn("Network selection not usable yet ('+COPS: 0'). Waiting for registration/provider details.");
			} else {
				log.warn("Network selection not usable yet. Malformed +COPS response '" + copsLine + "'.");
			}
			return;
		}
		switch (parsedCops.ratValue) {
		case 0:
			log.info("RADIOT: GSM (2G)");
			watchdogList.set(3, "2G");
			break;
		case 1:
			log.info("RADIOT: GSM Compact (2G)");
			watchdogList.set(3, "2G");
			break;
		case 2:
			log.info("RADIOT: UTRAN (3G)");
			watchdogList.set(3, "3G");
			break;
		case 3:
			log.info("RADIOT: GSM w/EGPRS (2G)");
			watchdogList.set(3, "2G");
			break;
		case 4:
			log.info("RADIOT: UTRAN w/HSDPA (3G)");
			watchdogList.set(3, "3G");
			break;
		case 5:
			log.info("RADIOT: UTRAN w/HSUPA (3G)");
			watchdogList.set(3, "3G");
			break;
		case 6:
			log.info("RADIOT: UTRAN w/HSDPA and HSUPA (3G)");
			watchdogList.set(3, "3G");
			break;
		case 7:
			log.info("RADIOT: E-UTRAN (4G/LTE)");
			watchdogList.set(3, "4G");
			break;
		default:
			break;
		}
		startupProviderName = parsedCops.providerName.isEmpty() ? "n/a" : parsedCops.providerName;
		startupRatLabel = getRatLabelForSummary(parsedCops.ratValue);
		watchdogList.set(2, startupProviderName);
		watchdogList.set(0, new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()));
		startupNetworkReady = parsedCops.ready;
	}

	private ParsedCops parseCopsLine(String copsLine) {
		if (copsLine == null) {
			return null;
		}
		int colonIdx = copsLine.indexOf(':');
		if (colonIdx < 0 || colonIdx + 1 >= copsLine.length()) {
			return null;
		}
		String[] parts = copsLine.substring(colonIdx + 1).trim().split(",");
		if (parts.length < 4) {
			return null;
		}
		String providerName = parts[2].replace("\"", "").trim();
		Integer ratValue = safeParseInteger(parts[3]);
		if (ratValue == null) {
			return null;
		}
		boolean ready = !providerName.isEmpty() && ratValue >= 0 && ratValue <= 7;
		return new ParsedCops(providerName, ratValue, ready);
	}

	private void updateStartupAndWatchdogFromCsq(String csqLine) {
		Integer csqValue = parseCsqValue(csqLine);
		if (csqValue == null) {
			lastCsqValue = null;
			startupSignalReady = false;
			startupSignalSummary = "n/a";
			log.warn("Ignoring malformed +CSQ line '" + csqLine + "'.");
			return;
		}
		lastCsqValue = csqValue;
		int percent = Math.round(csqValue * 100 / 31);
		startupSignalReady = csqValue >= 0 && csqValue <= 31;
		if (!startupSignalReady) {
			watchdogList.set(4, "n/a");
			watchdogList.set(5, "n/a");
			startupSignalSummary = csqValue + "/31 [n/a]";
			log.warn("SIGNAL: " + csqValue + "/31 not usable yet.");
			return;
		}
		if (percent > 0 && percent <= 100)
			watchdogList.set(4, percent + "%");
		else
			watchdogList.set(4, "n/a");
		if (csqValue <= 9) {
			log.info("SIGNAL: " + csqValue + "/1-9/31 [+---]");
			watchdogList.set(5, "+---");
			startupSignalSummary = csqValue + "/31 [+---]," + percent + "%";
		} else if (csqValue >= 10 && csqValue <= 14) {
			log.info("SIGNAL: " + csqValue + "/10-14/31 [++--]");
			watchdogList.set(5, "++--");
			startupSignalSummary = csqValue + "/31 [++--]," + percent + "%";
		} else if (csqValue >= 15 && csqValue <= 19) {
			log.info("SIGNAL: " + csqValue + "/15-19/31 [+++-]");
			watchdogList.set(5, "+++-");
			startupSignalSummary = csqValue + "/31 [+++-]," + percent + "%";
		} else if (csqValue >= 20 && csqValue <= 31) {
			log.info("SIGNAL: " + csqValue + "/20-31/31 [++++]");
			watchdogList.set(5, "++++");
			startupSignalSummary = csqValue + "/31 [++++]," + percent + "%";
		} else {
			startupSignalSummary = csqValue + "/31 [n/a]," + percent + "%";
		}
	}

	private Integer parseCsqValue(String csqLine) {
		if (csqLine == null) {
			return null;
		}
		int colonIdx = csqLine.indexOf(':');
		if (colonIdx < 0 || colonIdx + 1 >= csqLine.length()) {
			return null;
		}
		String[] parts = csqLine.substring(colonIdx + 1).trim().split(",");
		if (parts.length < 1) {
			return null;
		}
		return safeParseInteger(parts[0]);
	}

	private Integer safeParseInteger(String value) {
		if (value == null) {
			return null;
		}
		try {
			return Integer.parseInt(value.trim());
		} catch (NumberFormatException e) {
			return null;
		}
	}

	private static class ParsedSstr {
		private final boolean ackRequired;
		private final int commandType;

		private ParsedSstr(boolean ackRequired, int commandType) {
			this.ackRequired = ackRequired;
			this.commandType = commandType;
		}
	}

	private static class ParsedCops {
		private final String providerName;
		private final int ratValue;
		private final boolean ready;

		private ParsedCops(String providerName, int ratValue, boolean ready) {
			this.providerName = providerName;
			this.ratValue = ratValue;
			this.ready = ready;
		}
	}

	private void rebootAndExit() {
		try {
			new ProcessBuilder("sudo", "reboot").inheritIO().start();
		} catch (IOException e) {
			log.error("Failed to execute reboot command", e);
		}

		log.info("Exiting program.");
		System.exit(0);	// Just in case the reboot doesn't work as expected, the watchdog-reboot would be the fall-back
	}

	private void close(boolean closePort) {
		Thread.currentThread().setName(ManagementFactory.getRuntimeMXBean().getName()); // Update thread name

		if (closePort && serPort != null && serPort.isOpen()) {
			log.debug(serPortStr + " trying to close serial port.");
			
			if (serPort.closePort())
				log.debug(serPortStr + " is now closed.");
			else 
				log.error(serPortStr + " is still open but couldn't be closed.");
		}
		if (closePort)
			serPort = null;
		
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

	private boolean isWatchdogActivityMode() {
		return WATCHDOG_MODE_ACTIVITY.equals(watchdogMode);
	}

	private boolean isWithinActivityStartupGrace() {
		if (!isWatchdogActivityMode() || watchdogActivityStartupGraceMillis <= 0 || watchdogStartedAtMillis <= 0)
			return false;
		return (System.currentTimeMillis() - watchdogStartedAtMillis) < watchdogActivityStartupGraceMillis;
	}

	private boolean isMeaningfulWatchdogActivityEvent(int eventCode) {
		return watchdogActivityEventAllowlist.contains(eventCode);
	}

	private Set<Integer> getDefaultWatchdogActivityEventSet() {
		HashSet<Integer> defaultEvents = new HashSet<>();
		for (String token : WATCHDOG_ACTIVITY_EVENTS_DEFAULT.split(",")) {
			defaultEvents.add(Integer.parseInt(token));
		}
		return defaultEvents;
	}

	private Set<Integer> parseWatchdogActivityEvents(String configuredValue, boolean explicitlyConfigured) {
		Set<Integer> defaultEvents = getDefaultWatchdogActivityEventSet();
		if (!explicitlyConfigured)
			return defaultEvents;

		HashSet<Integer> parsedEvents = new HashSet<>();
		if (configuredValue != null) {
			for (String token : configuredValue.split(",")) {
				String value = token.trim();
				if (value.isEmpty())
					continue;

				try {
					parsedEvents.add(Integer.parseInt(value));
				} catch (NumberFormatException e) {
					log.warn("Invalid watchdog.activity.events token '" + value + "' ignored.");
				}
			}
		}

		if (parsedEvents.isEmpty()) {
			log.warn("Property watchdog.activity.events has no valid values. Fallback to default " + formatWatchdogActivityEvents(defaultEvents));
			return defaultEvents;
		}

		return parsedEvents;
	}

	private String formatWatchdogActivityEvents(Set<Integer> events) {
		ArrayList<Integer> values = new ArrayList<>(events);
		Collections.sort(values);
		return values.toString().replace("[", "").replace("]", "").replace(" ", "");
	}

	private void updateWatchdogForCommunicationRx() {
		if (watchdogFile == null)
			return;
		if (!isWatchdogActivityMode() || isWithinActivityStartupGrace())
			updateWatchdog();
	}

	private void updateWatchdogForStkEvent(int eventCode) {
		if (watchdogFile == null)
			return;
		if (isWatchdogActivityMode() && (isWithinActivityStartupGrace() || isMeaningfulWatchdogActivityEvent(eventCode)))
			updateWatchdog();
	}
	
	public void updateWatchdog() {
		if ( watchdogFile == null || watchdogList.get(0) == null )
			return; // update watchdog file only if the list contains the RAT Timestamp (avoid too early updates)
		try {
			log.trace("Update watchdog file \'" + watchdogFile + "\'");

			// RAT-Timestamp, IMSI, Provider, RAT
			// 2020.05.23 17:28:53, 228017230302066, Swisscom, 4G, 83%, +++-
			String content = watchdogList.toString();
			try (BufferedWriter bw = new BufferedWriter(new FileWriter(watchdogFile))) {
				bw.write(content.substring(1, content.length() - 1).replace("null", "n/a").replace(", ", ","));
			}
		} catch (IOException e) {
			log.error("Failed to update watchdog file at " + watchdogFile, e);
		}
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
	
	public void shutdownAndExit(String msg){
		
		if (msg == null)
			msg = "ERR";
		
		if ( watchdogFile != null ) {
			try {

				log.error("Update watchdog file \'" + watchdogFile + "\' with ERR content");

				//         RAT-Timestamp, IMSI, Provider, RAT
				// normal: 2020.05.23 17:28:53, 228017230302066, Swisscom, 4G , 83%, +++-
				// error : 2020.05.23 17:28:53, ERR            , ERR     , ERR,    ,
				try (BufferedWriter bw = new BufferedWriter(new FileWriter(watchdogFile))) {
					bw.write(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()) + "," + msg + ",,ERR,ERR,ERR");
				}
			} catch (IOException e) {
				log.error("Failed to update watchdog file at " + watchdogFile, e);
			}
		}
		
		log.info("Send SHUTDOWN Command and exit application");
		send("AT+CFUN=1,1"); // force UE restart
		//send("AT^SMSO"); // Power-off the terminal
		isAlive = false; // will exit the while loop and terminate the application	
	}

}
