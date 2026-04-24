package com.swisscom.atclient;

import com.fazecast.jSerialComm.*;
import java.io.*;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
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

public class ATresponder extends Thread implements ATCommandSender {
	
	private final Logger log = LogManager.getLogger(ATresponder.class.getName());
	
	private String portStrArr[] = new String[1];
	
	// Mobile ID test PINs used to simulate user input in UI flows. Encoded as UCS-2 hex
	// (validPIN = "123456", invalidPIN = "654321"). These are fixed test vectors, not
	// production credentials.
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

	// Modem abstraction. This branch ships only the SIMCom SIM8262E-M2 driver; the
	// abstraction is kept so future modem families can be added without rewiring
	// the core, but every call site assumes SIMCom today.
	private ModemDriver modemDriver = new SIMComSIM8262Driver();

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
	
	// Requested RAT change via RADIO= keyword. Applied by verifyRAT() on the next
	// post STK idle refresh.
	private String newRadioMode;
	private boolean simcomStartupRatForced = false;
	private String simcomStartupRatKeyword = "AUTO";
	private String simcomNetworkModeProperty = "";
	private boolean startupSimReady = false;
	private boolean startupRegistrationReady = false;
	private boolean startupNetworkReady = false;
	private boolean startupSignalReady = false;
	private String startupRegistrationSource = "unknown";
	private Integer lastCregStat = null;
	private Integer lastCeregStat = null;
	private Integer lastC5gregStat = null;
	private String startupProviderName = "n/a";
	private String startupRatLabel = "n/a";
	private boolean startupRadioqCaptured = false;
	private String startupSignalSummary = "n/a";
	private String startupQualitySummary = "n/a";
	private Integer lastCsqValue = null;
	private int degradedIdleRadioChecks = 0;
	private long lastAutomaticRadioRecoveryAtMillis = 0;

	// Serving-cell snapshot captured from the most recent +CPSI line. Used by the
	// heartbeat summary so every 3-minute radio check can print band / RSRP / RSRQ /
	// SINR alongside the per-RAT registration state in one consolidated block.
	private String lastCpsiMode = "n/a";
	private String lastCpsiBand = "n/a";
	private String lastCpsiPlmn = "n/a";
	private Double lastCpsiRsrpDbm = null;
	private Double lastCpsiRsrqDb = null;
	private Double lastCpsiSinrDb = null;
	private long lastCpsiAtMillis = 0;

	// SA-recovery state. Only active when modem.type=simcom + simcom.network.mode=nr_only.
	// The tier thresholds count consecutive heartbeats where C5GREG is not 1 (home) or
	// 5 (roaming); cooldowns prevent hammering the network with back-to-back attempts.
	private boolean simcomSaRecoveryEnabled = true;
	private int saRecoveryTier1Misses = 1;
	private int saRecoveryTier2Misses = 2;
	private int saRecoveryTier3Misses = 3;
	private long saRecoveryCooldownTier1Ms = 180_000L;
	private long saRecoveryCooldownTier2Ms = 600_000L;
	private long saRecoveryCooldownTier3Ms = 1_800_000L;
	private int saRecoveryConsecutiveMisses = 0;
	private int lastSaRecoveryTier = 0;
	private long lastSaRecoveryAtMillis = 0;

	private static final Pattern KEYWORD_USERDELAY = Pattern.compile("\\bUSERDELAY=(\\d+)\\b");
	// Radio access technology keyword. AUTO / LTE / NR / LTE_NR map to AT+CNMP=2/38/71/109
	// via the SIMCom driver's buildRATSelectionCommand.
	private static final Pattern KEYWORD_RADIO = Pattern.compile("\\bRADIO=(AUTO|LTE|NR|LTE_NR)\\b");
	private static final Pattern KEYWORD_HOSTNAME = Pattern.compile("\\bHOSTNAME=(mobileid0\\d{2})\\b");
	
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

	/** Returns parsed int property value, or the supplied default when missing or non-numeric. */
	private static int intPropertyOrDefault(Properties prop, String key, int defaultValue) {
		String value = prop.getProperty(key);
		if (value == null || value.trim().isEmpty()) return defaultValue;
		try {
			return Integer.parseInt(value.trim());
		} catch (NumberFormatException e) {
			return defaultValue;
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
				warnIfConfigWorldReadable(atclientCfg);
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

			log.info("Modem driver: " + modemDriver.getVendorName() + " " + modemDriver.getModemFamilyLabel());

			simcomNetworkModeProperty = prop.getProperty("simcom.network.mode", "").trim().toLowerCase();
			if (simcomNetworkModeProperty.length() > 0) {
				log.info("Property simcom.network.mode set to " + simcomNetworkModeProperty + " (applied at startup).");
			}

			simcomSaRecoveryEnabled = "true".equalsIgnoreCase(prop.getProperty("simcom.sa.recovery.enabled", "true").trim());
			saRecoveryTier1Misses = Math.max(1, intPropertyOrDefault(prop, "simcom.sa.recovery.tier1.misses", 1));
			saRecoveryTier2Misses = Math.max(saRecoveryTier1Misses, intPropertyOrDefault(prop, "simcom.sa.recovery.tier2.misses", 2));
			saRecoveryTier3Misses = Math.max(saRecoveryTier2Misses, intPropertyOrDefault(prop, "simcom.sa.recovery.tier3.misses", 3));
			saRecoveryCooldownTier1Ms = (long) intPropertyOrDefault(prop, "simcom.sa.recovery.cooldown.tier1.seconds", 180) * 1000L;
			saRecoveryCooldownTier2Ms = (long) intPropertyOrDefault(prop, "simcom.sa.recovery.cooldown.tier2.seconds", 600) * 1000L;
			saRecoveryCooldownTier3Ms = (long) intPropertyOrDefault(prop, "simcom.sa.recovery.cooldown.tier3.seconds", 1800) * 1000L;
			log.info("SA recovery settings: enabled=" + simcomSaRecoveryEnabled
					+ ", tiers misses=" + saRecoveryTier1Misses + "/" + saRecoveryTier2Misses + "/" + saRecoveryTier3Misses
					+ ", cooldowns=" + (saRecoveryCooldownTier1Ms / 1000L) + "s/"
					+ (saRecoveryCooldownTier2Ms / 1000L) + "s/"
					+ (saRecoveryCooldownTier3Ms / 1000L) + "s"
					+ " (only active when simcom.network.mode=nr_only).");
			
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
				String configuredMaintenance = prop.getProperty("maintenance.script.file").trim();
				if (isSafeMaintenanceScript(configuredMaintenance)) {
					maintenanceFile = configuredMaintenance;
					log.info("Property maintenance.script.file set to " + maintenanceFile);
				} else {
					maintenanceFile = null;
					log.error("Property maintenance.script.file rejected (must be an existing regular-file absolute path, executable, not a symlink): '"
							+ configuredMaintenance + "'. Maintenance disabled.");
				}
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

	private void warnIfConfigWorldReadable(String path) {
		try {
			Path p = Paths.get(path);
			if (!Files.exists(p)) return;
			Set<PosixFilePermission> perms = Files.getPosixFilePermissions(p);
			if (perms.contains(PosixFilePermission.OTHERS_READ) || perms.contains(PosixFilePermission.OTHERS_WRITE)) {
				log.warn("Config file " + path + " has permissions " + PosixFilePermissions.toString(perms)
						+ " — world-readable/writable. Recommended: chmod 600.");
			}
		} catch (UnsupportedOperationException e) {
			// POSIX not supported (e.g. Windows) — skip silently.
		} catch (IOException | SecurityException e) {
			log.debug("Could not inspect config file permissions: " + e.getMessage());
		}
	}

	private static boolean isSafeMaintenanceScript(String path) {
		if (path == null || path.isEmpty()) return false;
		try {
			Path p = Paths.get(path);
			if (!p.isAbsolute()) return false;
			if (Files.isSymbolicLink(p)) return false;
			if (!Files.isRegularFile(p)) return false;
			return Files.isExecutable(p);
		} catch (SecurityException e) {
			return false;
		}
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
		{
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

			send("AT+CIMI");   // IMSI
			send("AT+CPIN?");  // SIM Card status
			send("AT+CREG?");  // 2G/3G registration
			send("AT+CEREG?"); // LTE (EPS) registration
			send("ATI1");      // Product identity for startup diagnostics
			// Read only driver introspection (AT+CNMP?, AT+CGDCONT?). No STK mode toggles.
			modemDriver.sendStartupCommands(this);

			String startupKeyword;
			switch (simcomNetworkModeProperty) {
				case "auto":     startupKeyword = "AUTO";   break;
				case "lte_only": startupKeyword = "LTE";    break;
				case "nr_only":  startupKeyword = "NR";     break;
				case "lte_nr5g": startupKeyword = "LTE_NR"; break;
				case "":         startupKeyword = "AUTO";   break;
				default:
					log.warn("Property simcom.network.mode has unknown value '" + simcomNetworkModeProperty + "'. Falling back to AUTO.");
					startupKeyword = "AUTO";
					break;
			}
			if (!"AUTO".equals(startupKeyword)) {
				simcomStartupRatForced = true;
				simcomStartupRatKeyword = startupKeyword;
				log.info("Startup RAT forced to " + startupKeyword + " via simcom.network.mode=" + simcomNetworkModeProperty
						+ " (idle radio recovery is suppressed; the tiered SA recovery in the heartbeat path handles 5G SA attach).");
			}
			String startupRatCmd = modemDriver.buildRATSelectionCommand(startupKeyword);
			send(startupRatCmd, "OK", 60000, true);

			if (!waitForStartupReadiness(STARTUP_READINESS_MAX_WAIT_MILLIS, STARTUP_READINESS_POLL_MILLIS)) {
				// Self healing for simcom.network.mode=nr_only: SA coverage may flap, leaving
				// the modem stuck in C5GREG stat=2 (searching). A CFUN=0/1 cycle flushes NAS
				// state and retriggers an SA cell search. Try once before escalating to
				// shutdownAndExit (which would produce a tight systemd restart loop otherwise).
				if (simcomStartupRatForced && "NR".equals(simcomStartupRatKeyword)) {
					log.warn("SA startup readiness timed out under simcom.network.mode=nr_only. "
							+ "Attempting CFUN=0/1 cycle to re-trigger SA attach before shutdown.");
					resetStartupReadinessFlags();
					send("AT+CFUN=0", "OK", 10000, false);
					try { Thread.sleep(8000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
					send("AT+CFUN=1", "OK", 10000, false);
					try { Thread.sleep(10000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
					send("AT+CNMP=71", "OK", 10000, false);
					if (waitForStartupReadiness(STARTUP_READINESS_MAX_WAIT_MILLIS, STARTUP_READINESS_POLL_MILLIS)) {
						log.info("SA re-attached after CFUN recovery cycle.");
					} else {
						log.error("Startup readiness still not reached after CFUN recovery. Triggering controlled shutdown/recovery.");
						shutdownAndExit("STARTUP NOT READY");
						return false;
					}
				} else {
					log.error("Startup readiness was not reached in time. Triggering controlled shutdown/recovery.");
					shutdownAndExit("STARTUP NOT READY");
					return false;
				}
			}
			captureStartupRadioQualitySnapshot();
			logStartupModeDiagnostics();
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
		lastC5gregStat = null;
		startupProviderName = "n/a";
		startupRatLabel = "n/a";
		startupSignalSummary = "n/a";
		startupQualitySummary = "n/a";
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
			boolean c5gregOk = true;
			if (modemDriver.supports5GRegistration()) {
				String q = modemDriver.build5GRegistrationQuery();
				if (q != null) {
					// Non-fatal: older SIMCom firmware returns ERROR when no SIM is 5G-capable.
					c5gregOk = send(q);
				}
			}
			boolean copsOk = send("AT+COPS?");
			boolean csqOk = send("AT+CSQ");

			if (cpinOk && (cregOk || ceregOk || c5gregOk) && copsOk && csqOk
					&& startupSimReady && startupRegistrationReady && startupNetworkReady && startupSignalReady) {
				log.info("Startup readiness confirmed after attempt " + attempt + ".");
				return true;
			}

			log.warn("Startup readiness pending (attempt " + attempt + "): SIM=" + startupSimReady
					+ ", REGISTRATION=" + startupRegistrationReady + "(" + startupRegistrationSource + ")"
					+ ", NETWORK=" + startupNetworkReady + ", SIGNAL=" + startupSignalReady
					+ ", CMDOK[CPIN/CREG/CEREG/C5GREG/COPS/CSQ]="
					+ cpinOk + "/" + cregOk + "/" + ceregOk + "/" + c5gregOk + "/" + copsOk + "/" + csqOk + ".");
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
		
		String rx;
		int value;
		int consecutiveRxFailures = 0;
		
		// Start endless loop...
		while (isAlive) {

			Thread.sleep(fastPollMillis());
			
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
				log.error(serPortStr + " down! Did the modem crash? Attempting bounded recovery before reboot.");

				boolean recovered = false;
				long backoffMillis = 1000;
				for (int attempt = 1; attempt <= 3 && !recovered && isAlive; attempt++) {
					try {
						log.warn("Serial port recovery attempt " + attempt + "/3 (backoff " + backoffMillis + "ms).");
						Thread.sleep(backoffMillis);
						close(true);
						lookupSerialPort(serPortStr);
						if (send("AT", 1000, false)) {
							rspTimerCurrent = System.currentTimeMillis();
							heartBeatTimerCurrent = rspTimerCurrent;
							recovered = true;
							log.info("Serial port recovered on attempt " + attempt + ".");
						} else {
							log.warn("Serial port probe after recovery attempt " + attempt + " did not respond to AT.");
						}
					} catch (Exception e) {
						log.warn("Serial port recovery attempt " + attempt + " failed: " + e.getMessage());
					}
					backoffMillis *= 2;
				}

				if (!recovered) {
					log.error(serPortStr + " still down after bounded retry. Invoking REBOOT.");
					rebootAndExit();
				}
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
					} else if (modemDriver.isSTKProactiveURC(rx)) {
						// SIMCom +STIN:<cmd> dispatch. Uses driver delegated commands
						// (AT+STGI= / AT+STGR=) and emits STKxxx log markers so downstream
						// log tooling can match on proactive command type.
						value = modemDriver.parseSTKCommandType(rx);
						if (value < 0) {
							log.warn("Ignoring malformed +STIN line '" + rx + "'.");
							continue;
						}
						updateWatchdogForStkEvent(value);

						if (value == modemDriver.getSendMessageType()) {
							log.info("STK019: SEND MESSAGE");
							send(modemDriver.buildGetInfoCommand(value));
							modemDriver.acknowledgeProactive(this, value);
						} else if (value == modemDriver.getPlayToneType()) {
							log.info("STK032: PLAY TONE");
							send(modemDriver.buildGetInfoCommand(value));
							modemDriver.acknowledgeProactive(this, value);
						} else if (value == modemDriver.getDisplayTextType()) {
							log.info("STK033: DISPLAY TEXT");
							send(modemDriver.buildGetInfoCommand(value));
							getMeTextAscii(rx); // may set the flag such as CANCEL

							if (cancel) {
								setCancel(false);
								modemDriver.cancelSession(this, value);
							} else if (stk_timeout) {
								setStkTimeout(false);
								modemDriver.sessionTimeout(this, value);
							} else {
								if (user_delay) {
									sleep(user_delay_millis);
									setUserDelay(false);
								}
								modemDriver.ackDisplayText(this, value);
							}
						} else if (value == modemDriver.getGetInputType()) {
							log.info("STK035: GET INPUT");
							send(modemDriver.buildGetInfoCommand(value));
							getMeTextAscii(rx);

							if (cancel) {
								setCancel(false);
								modemDriver.cancelSession(this, value);
							} else if (stk_timeout) {
								setStkTimeout(false);
								modemDriver.sessionTimeout(this, value);
							} else {
								String pinPlain = "123456"; // valid test PIN
								if (block_pin) {
									log.info("BLOCKPIN: Input wrong PIN. Attempt " + (maxWrongPinAttempts - cntrWrongPinAttempts + 1) + " out of " + maxWrongPinAttempts + ".");
									if (--cntrWrongPinAttempts == 0) {
										setBlockedPIN(false);
										cntrWrongPinAttempts = maxWrongPinAttempts;
									}
									pinPlain = "654321"; // invalid test PIN
								}
								if (user_delay) {
									sleep(user_delay_millis);
									setUserDelay(false);
								}
								modemDriver.submitInput(this, value, pinPlain);
							}
						} else if (value == modemDriver.getSelectItemType()) {
							log.info("STK036: SELECT ITEM");
							send(modemDriver.buildGetInfoCommand(value));
							// Do NOT auto-select: during auth the flow never hits this path,
							// and auto-selecting can accidentally enter Change-PIN sub-menu
							// (observed during Phase 0.4 responder debugging).
						} else if (value == modemDriver.getSetUpMenuType()) {
							log.info("STK037: SET UP MENU");
							send(modemDriver.buildGetInfoCommand(value));
							// On SIMCom the applet re-broadcasts SET UP MENU when it returns to
							// idle after a completed auth. The SIM expects a user selection
							// (AT+STGR=25,<item_id>) or silence; a plain result-code ack
							// AT+STGR=25,0 is rejected with ERROR because it is not a
							// valid SET UP MENU response in this state. Skip the ack when
							// the driver advertises SET UP MENU as its return-to-main trigger
							// and let handlePostStkMainMenu drive the post-session recovery.
							if (modemDriver.getReturnToMainType() == modemDriver.getSetUpMenuType()) {
								handlePostStkMainMenu();
							} else {
								modemDriver.acknowledgeProactive(this, value);
							}
						} else {
							log.warn("Unhandled +STIN cmd " + value + " on SIMCom: '" + rx + "'");
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
		emitHeartbeatSummary("post-STK");
		evaluateSaRecovery("post-STK");
		collectIdleRadioDiagnosticsAndRecoverIfNeeded("after STK254");
	}

	private void handleHeartbeatIdleRadioHealthCheck() throws InterruptedException {
		refreshIdleRadioStatus("post-heartbeat idle");
		emitHeartbeatSummary("post-heartbeat");
		evaluateSaRecovery("post-heartbeat");
		collectIdleRadioDiagnosticsAndRecoverIfNeeded("after heartbeat");
	}

	/**
	 * Consolidated operator-facing radio summary emitted after every heartbeat radio
	 * refresh. Prints one block covering per-RAT registration, serving cell (band /
	 * operator from +CPSI) and a uniform 5-level strength label for CSQ, RSRP, RSRQ
	 * and SINR so the 3-minute heartbeat reads at a glance, even for readers who are
	 * not modem experts. Kept alongside the existing RADIOQ / REG / SIGNAL lines —
	 * this is additive, not a replacement.
	 */
	private void emitHeartbeatSummary(String context) {
		boolean forcedNrOnly = simcomStartupRatForced && "NR".equals(simcomStartupRatKeyword);
		String servingMode = (lastCpsiMode == null || "n/a".equals(lastCpsiMode)) ? "unknown" : lastCpsiMode;
		String servingHint = RadioGlossary.cpsiModeHint(lastCpsiMode);
		String bandPretty = ("n/a".equals(lastCpsiBand) || lastCpsiBand == null) ? "n/a" : RadioGlossary.bandHint(lastCpsiBand);
		String plmnPretty = ("n/a".equals(lastCpsiPlmn) || lastCpsiPlmn == null)
				? "n/a"
				: (lastCpsiPlmn + " - " + RadioGlossary.plmnHint(lastCpsiPlmn));

		log.info("HEARTBEAT: " + context
				+ " | serving=" + servingMode + " (" + servingHint + ")"
				+ " | band=" + bandPretty
				+ " | operator=" + startupProviderName + " | plmn=" + plmnPretty);

		String saLine = formatRatRegistrationLine("5G SA ", lastC5gregStat, null);
		String nsaLine = formatNsaRegistrationLine(forcedNrOnly);
		String lteLine = formatRatRegistrationLine("LTE   ", lastCeregStat, forcedNrOnly ? "forced NR-only, LTE search disabled" : null);
		String twogLine = formatRatRegistrationLine("2G/3G ", lastCregStat, forcedNrOnly ? "forced NR-only, legacy RAT search disabled" : null);
		log.info("HEARTBEAT:   " + saLine);
		log.info("HEARTBEAT:   " + nsaLine);
		log.info("HEARTBEAT:   " + lteLine);
		log.info("HEARTBEAT:   " + twogLine);

		StringBuilder sig = new StringBuilder("HEARTBEAT: signal");
		if (lastCpsiRsrpDbm != null)
			sig.append(" RSRP=").append(String.format(Locale.ROOT, "%.1f dBm", lastCpsiRsrpDbm))
					.append(" (").append(RadioGlossary.classifyRsrp(lastCpsiRsrpDbm).label()).append(")");
		if (lastCpsiRsrqDb != null)
			sig.append(" RSRQ=").append(String.format(Locale.ROOT, "%.1f dB", lastCpsiRsrqDb))
					.append(" (").append(RadioGlossary.classifyRsrq(lastCpsiRsrqDb).label()).append(")");
		if (lastCpsiSinrDb != null)
			sig.append(" SINR=").append(String.format(Locale.ROOT, "%.1f dB", lastCpsiSinrDb))
					.append(" (").append(RadioGlossary.classifySinr(lastCpsiSinrDb).label()).append(")");
		if (lastCsqValue != null) {
			sig.append(" CSQ=").append(lastCsqValue).append("/31")
					.append(" (").append(RadioGlossary.classifyCsq(lastCsqValue).label()).append(")");
		}
		log.info(sig.toString());
	}

	private String formatRatRegistrationLine(String ratLabel, Integer stat, String extraNote) {
		StringBuilder sb = new StringBuilder();
		sb.append(ratLabel).append(": ");
		if (stat == null) {
			sb.append("no status reported");
		} else {
			sb.append("stat=").append(stat).append(" (").append(RadioGlossary.regStatHint(stat)).append(")");
		}
		if (extraNote != null && !extraNote.isEmpty())
			sb.append(" - ").append(extraNote);
		return sb.toString();
	}

	private String formatNsaRegistrationLine(boolean forcedNrOnly) {
		StringBuilder sb = new StringBuilder("5G NSA: ");
		if ("NR5G_NSA".equalsIgnoreCase(lastCpsiMode) || "LTE_NR5G".equalsIgnoreCase(lastCpsiMode)) {
			sb.append("active (").append(RadioGlossary.cpsiModeHint(lastCpsiMode)).append(")");
		} else if (forcedNrOnly) {
			sb.append("not-anchored - forced NR-only via CNMP=71, EN-DC disabled");
		} else {
			sb.append("not-anchored (no EN-DC dual-connectivity reported)");
		}
		return sb.toString();
	}

	/**
	 * Tiered 5G SA recovery, only active when modem.type=simcom and simcom.network.mode=nr_only.
	 * Counts consecutive heartbeats where C5GREG is not 1 (home) or 5 (roaming) and escalates:
	 *   tier 1 (AT+COPS=0 network reselection) - cheapest, network-side re-attach
	 *   tier 2 (CFUN 0/1 + CNMP=71) - flushes NAS state and re-forces NR-only
	 *   tier 3 (AT+CFUN=1,1) - full modem reboot. The serial port disappears briefly;
	 *                          atclient's listenForRx serial-recovery path picks it up,
	 *                          and if not, systemd restarts the service.
	 * Cooldowns prevent hammering the network. The counter is cleared once SA re-attaches.
	 */
	private void evaluateSaRecovery(String context) throws InterruptedException {
		if (!simcomSaRecoveryEnabled) return;
		if (!(modemDriver instanceof SIMComSIM8262Driver)) return;
		if (!simcomStartupRatForced || !"NR".equals(simcomStartupRatKeyword)) return;

		boolean saAttached = isRegistered(lastC5gregStat);
		if (saAttached) {
			if (saRecoveryConsecutiveMisses > 0) {
				log.info("SA_RECOVERY: 5G SA re-attached after " + saRecoveryConsecutiveMisses
						+ " miss(es) (lastTier=" + lastSaRecoveryTier + "). Clearing counters.");
			}
			saRecoveryConsecutiveMisses = 0;
			lastSaRecoveryTier = 0;
			return;
		}

		if (rat) {
			log.info("SA_RECOVERY: skipped - RAT change is pending.");
			return;
		}

		saRecoveryConsecutiveMisses++;
		long now = System.currentTimeMillis();
		long sinceLast = (lastSaRecoveryAtMillis > 0) ? (now - lastSaRecoveryAtMillis) : Long.MAX_VALUE;
		String c5gregLabel = (lastC5gregStat == null)
				? "n/a"
				: (lastC5gregStat + "/" + RadioGlossary.regStatHint(lastC5gregStat));
		log.warn("SA_RECOVERY: " + context + " - 5G SA not attached (C5GREG=" + c5gregLabel
				+ "), miss #" + saRecoveryConsecutiveMisses
				+ " (lastTier=" + lastSaRecoveryTier + ", sinceLastRecovery="
				+ (lastSaRecoveryAtMillis == 0 ? "never" : (sinceLast + "ms")) + ").");

		int chosenTier = 0;
		if (saRecoveryConsecutiveMisses >= saRecoveryTier3Misses && sinceLast >= saRecoveryCooldownTier3Ms) {
			chosenTier = 3;
		} else if (saRecoveryConsecutiveMisses >= saRecoveryTier2Misses && sinceLast >= saRecoveryCooldownTier2Ms) {
			chosenTier = 2;
		} else if (saRecoveryConsecutiveMisses >= saRecoveryTier1Misses && sinceLast >= saRecoveryCooldownTier1Ms) {
			chosenTier = 1;
		} else {
			log.info("SA_RECOVERY: within cooldown, no action this cycle.");
			return;
		}

		lastSaRecoveryAtMillis = now;
		lastSaRecoveryTier = chosenTier;

		switch (chosenTier) {
			case 1:
				log.warn("SA_RECOVERY tier=1: AT+COPS=0 network reselection (soft retry toward 5G SA).");
				send("AT+COPS=0", "OK", RADIO_RESELECTION_TIMEOUT_MILLIS, true);
				break;
			case 2:
				log.warn("SA_RECOVERY tier=2: CFUN 0/1 cycle + CNMP=71 (RF cold restart + re-force NR-only).");
				send("AT+CFUN=0", "OK", 10000, false);
				sleep(8000);
				send("AT+CFUN=1", "OK", 10000, false);
				sleep(10000);
				String nrCmd = modemDriver.buildRATSelectionCommand("NR");
				send(nrCmd, "OK", 10000, false);
				break;
			case 3:
				log.warn("SA_RECOVERY tier=3: AT+CFUN=1,1 full modem reboot. Serial port will disappear briefly; "
						+ "the listenForRx serial-recovery path or systemd will re-establish the link.");
				send("AT+CFUN=1,1", "OK", 5000, false);
				break;
			default:
				break;
		}

		sleep(RADIO_RESELECTION_SETTLE_MILLIS);
		if (refreshIdleRadioStatus("post-SA-recovery-tier" + chosenTier)) {
			if (isRegistered(lastC5gregStat)) {
				log.info("SA_RECOVERY tier=" + chosenTier + " succeeded: 5G SA attached after recovery.");
				saRecoveryConsecutiveMisses = 0;
				lastSaRecoveryTier = 0;
			} else {
				log.warn("SA_RECOVERY tier=" + chosenTier + " completed but 5G SA still not attached "
						+ "(C5GREG=" + (lastC5gregStat == null ? "n/a" : lastC5gregStat.toString())
						+ "). Cooldown now active; next attempt possible after the configured window.");
			}
		}
	}

	private boolean refreshIdleRadioStatus(String context) throws InterruptedException {
		boolean cregOk = send("AT+CREG?");
		boolean ceregOk = send("AT+CEREG?");
		// On 5G SA the UE is attached via C5GREG only (CREG/CEREG both report 0), so include
		// C5GREG in the heartbeat probe when the driver supports it. Without this, the
		// heartbeat would incorrectly report "no registration" for SA-pinned devices.
		boolean c5gregOk = true;
		if (modemDriver.supports5GRegistration()) {
			c5gregOk = send(modemDriver.build5GRegistrationQuery());
		}
		boolean copsOk = send("AT+COPS?");
		boolean csqOk = send("AT+CSQ");
		// Also emit an extended-signal snapshot (AT+CPSI? on SIMCom) on every heartbeat, not
		// only on degraded-idle path. This gives every Mobile-ID auth attempt a nearby RSRP/
		// RSRQ/SINR stamp in the log for direct signal-vs-outcome correlation at marginal SA
		// coverage. logCpsiLine also populates startupQualitySummary / RADIOQ quality suffix.
		boolean extSignalOk = true;
		String extSignalCmd = modemDriver.buildExtendedSignalCommand();
		if (extSignalCmd != null) {
			extSignalOk = send(extSignalCmd);
		}
		boolean anyRegOk = cregOk || ceregOk || c5gregOk;
		if (!anyRegOk || !copsOk || !csqOk) {
			log.warn("RADIOT: " + context + " refresh incomplete. CMDOK[CREG/CEREG/C5GREG/COPS/CSQ/EXTSIG]="
					+ cregOk + "/" + ceregOk + "/" + c5gregOk + "/" + copsOk + "/" + csqOk + "/" + extSignalOk + ".");
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
		String degradedSignalCmd = modemDriver.buildExtendedSignalCommand();
		if (degradedSignalCmd != null && !sendDiagnosticBestEffort(degradedSignalCmd, DEGRADED_DIAGNOSTIC_TIMEOUT_MILLIS))
			log.warn("RADIOQ: failed to collect " + degradedSignalCmd + " during degraded idle check " + triggerContext + ".");

		log.warn("RADIOT: degraded idle radio state " + triggerContext + " (count="
				+ degradedIdleRadioChecks + "/" + DEGRADED_IDLE_CHECKS_BEFORE_RECOVERY
				+ ", mode=" + simcomNetworkModeProperty
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

		if (simcomStartupRatForced) {
			log.info("RADIOT: idle radio recovery skipped because startup RAT is forced to " + simcomStartupRatKeyword
					+ " (SA recovery tiers in the heartbeat path handle attach).");
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
		String recoveryCmd = modemDriver.buildRadioRecoveryCommand();
		if (!send(recoveryCmd, "OK", RADIO_RESELECTION_TIMEOUT_MILLIS, true)) {
			log.warn("RADIOT: automatic network reselection attempt failed.");
			return;
		}
		lastAutomaticRadioRecoveryAtMillis = now;
		log.info("RADIOT: automatic network reselection command accepted. Collecting post-reselection status.");

		sleep(RADIO_RESELECTION_SETTLE_MILLIS);
		if (refreshIdleRadioStatus("post-reselection"))
			log.info("RADIOT: post-reselection refresh completed.");
		String postSignalCmd = modemDriver.buildExtendedSignalCommand();
		if (postSignalCmd != null && !sendDiagnosticBestEffort(postSignalCmd, DEGRADED_DIAGNOSTIC_TIMEOUT_MILLIS))
			log.warn("RADIOQ: failed to collect " + postSignalCmd + " after automatic network reselection.");
	}

	private boolean isDegradedIdleRadioState() {
		if (!isRegistrationReady())
			return true;
		if (!startupNetworkReady || !startupSignalReady)
			return true;
		return lastCsqValue == null || lastCsqValue.intValue() <= DEGRADED_IDLE_CSQ_THRESHOLD;
	}

	private boolean isRegistrationReady() {
		return isRegistered(lastCregStat) || isRegistered(lastCeregStat) || isRegistered(lastC5gregStat);
	}

	private boolean isRegistered(Integer stat) {
		return stat != null && (stat.intValue() == 1 || stat.intValue() == 5);
	}

	private void verifyRAT() {
		if (!rat)
			return;
		if (newRadioMode == null || newRadioMode.trim().isEmpty()) {
			log.warn("RADIOT: RADIO change requested without a target mode. Clearing pending request.");
			setRAT(false);
			return;
		}

		log.info("RADIOT: Applying RADIO=" + newRadioMode + " via AT+CNMP.");
		String ratCmd = modemDriver.buildRATSelectionCommand(newRadioMode);
		boolean commandSucceeded = send(ratCmd, "OK", RADIO_RESELECTION_TIMEOUT_MILLIS, true);
		if (commandSucceeded) {
			degradedIdleRadioChecks = 0;
			log.info("RADIOT: RADIO=" + newRadioMode + " accepted.");
			setRAT(false);
		} else {
			log.warn("RADIOT: RADIO=" + newRadioMode + " change failed. Will retry after the next post STK idle refresh.");
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
	
			int preSleep = sendPreSleepMillis();
			if (preSleep > 0)
				sleep(preSleep); // Driver dictates the pre send settling delay; SIMCom USB CDC uses 0 ms.
	
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
						} else if (rx.toUpperCase().startsWith("+C5GREG: ")) {
							updateC5gRegistrationStateFromLine(rx);
						} else if (rx.toUpperCase().startsWith("+CPSI: ")) {
							logCpsiLine(rx);
						} else if (rx.toUpperCase().startsWith("+CNWINFO: ")) {
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
					Thread.sleep(fastPollMillis());
				}
				Thread.sleep(fastPollMillis());
			}
		} catch (IOException e) {
			log.error("receive() IOException : ", e);
		} catch (InterruptedException e) {
			log.error("receive() InterruptedException : ", e);
		}
		return false;
	}

	private void getMeTextAscii(String rsp) throws UnsupportedEncodingException {
		// Driver delegated UI text extraction. SIMCom driver decodes +STGI: UCS-2 hex
		// (SIMCom's "decoded" mode still wraps text in UCS-2 hex on the wire).
		if (rsp == null) return;
		if (!modemDriver.isSTKInfoResponse(rsp)) return;
		String asciiText = modemDriver.extractDisplayText(rsp);
		if (asciiText == null || asciiText.isEmpty()) return;
		log.info("UI-TXT: \'" + asciiText + "\'");
		verifyKeywords(asciiText);
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
				long parsed = Long.parseLong(userDelayMatcher.group(1));
				if (parsed >= 1 && parsed <= 9) {
					user_delay_millis = (int) (parsed * 1000L);
					setUserDelay(true);
					log.info("'USERDELAY=" + parsed + "'-keyword detected! The current TerminalResponse will be delayed by " + parsed + " seconds.");
				} else {
					log.warn("Ignoring out-of-range USERDELAY value in UI text.");
				}
			} catch (NumberFormatException e) {
				log.warn("Ignoring malformed USERDELAY keyword in UI text.");
			}
		}
		
		Matcher radioMatcher = KEYWORD_RADIO.matcher(rsp);
		if (radioMatcher.find()) {
			newRadioMode = radioMatcher.group(1);
			setRAT(true);
			log.info("'RADIO=" + newRadioMode + "'-keyword detected (driver translates to AT+CNMP).");
		}
		
		Matcher hostnameMatcher = KEYWORD_HOSTNAME.matcher(rsp);
		if (hostnameMatcher.find()) {
			String value = hostnameMatcher.group(1);
			log.info("'HOSTNAME=" + value + "'-keyword detected. Will change hostname to " + value);
			try {
				Process p = new ProcessBuilder("sudo", "/home/mid/setHostName", value).inheritIO().start();
				Thread waiter = new Thread(() -> {
					try {
						int code = p.waitFor();
						if (code == 0) log.info("setHostName completed successfully.");
						else log.warn("setHostName exited with non-zero code " + code + ".");
					} catch (InterruptedException ie) {
						Thread.currentThread().interrupt();
					}
				}, "setHostName-waiter");
				waiter.setDaemon(true);
				waiter.start();
			} catch (IOException e) {
				log.error("Failed to execute setHostName command", e);
			}
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
		// Non-daemon so a JVM shutdown waits for in-flight maintenance to complete
		// instead of killing it mid-run, which could leave the device in a partial state.
		maintenanceThread.setDaemon(false);
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
		String systemPath = "/dev/" + systemPortName;
		if (fullPath.equals(systemPath)) {
			return true;
		}
		// Follow symlinks (e.g. /dev/simcom_at -> /dev/ttyUSB2) so udev-managed
		// stable names work with the jSerialComm system-port names.
		try {
			Path requested = Paths.get(fullPath);
			if (Files.exists(requested) && Files.isSymbolicLink(requested)) {
				Path real = requested.toRealPath();
				if (real.toString().equals(systemPath)) {
					return true;
				}
			}
		} catch (IOException e) {
			log.debug("Symlink resolution for " + fullPath + " failed: " + e.getMessage());
		}
		return false;
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
			log.info("REG: " + registrationType + " stat=" + stat + " (" + RadioGlossary.regStatHint(stat) + ")");

		startupRegistrationReady = isRegistrationReady();
		updateRegistrationSourceLabel(registrationType, stat);
	}

	/** Parse and record a +C5GREG registration update (SIMCom / 5G NR path). */
	private void updateC5gRegistrationStateFromLine(String rawLine) {
		Integer stat = parseRegistrationStatus(rawLine);
		if (stat == null) {
			log.warn("Ignoring malformed +C5GREG line '" + rawLine + "'.");
			return;
		}
		Integer previousStat = lastC5gregStat;
		lastC5gregStat = stat;
		if (previousStat == null || previousStat.intValue() != stat.intValue())
			log.info("REG: C5GREG stat=" + stat + " (" + RadioGlossary.regStatHint(stat) + ")");
		startupRegistrationReady = isRegistrationReady();
		updateRegistrationSourceLabel("C5GREG", stat);
	}

	private void updateRegistrationSourceLabel(String activeType, Integer activeStat) {
		if (isRegistered(lastC5gregStat))
			startupRegistrationSource = "C5GREG:" + lastC5gregStat;
		else if (isRegistered(lastCeregStat))
			startupRegistrationSource = "CEREG:" + lastCeregStat;
		else if (isRegistered(lastCregStat))
			startupRegistrationSource = "CREG:" + lastCregStat;
		else
			startupRegistrationSource = activeType + ":" + activeStat;
	}

	private void logStartupModeDiagnostics() {
		log.info("MODEM: " + modemDriver.getIdentitySummary());
		log.info("REG: startupSummary source=" + startupRegistrationSource
				+ ", CREG=" + formatRegistrationStat(lastCregStat)
				+ ", CEREG=" + formatRegistrationStat(lastCeregStat)
				+ ", C5GREG=" + formatRegistrationStat(lastC5gregStat)
				+ ", ready=" + startupRegistrationReady);
		log.info("STARTUP: serial=" + serPortStr
				+ ", modem=" + modemDriver.getIdentitySummary()
				+ ", reg=" + formatStartupRegistrationSummary()
				+ ", provider=" + startupProviderName
				+ ", rat=" + startupRatLabel
				+ ", signal=" + startupSignalSummary
				+ ", quality=" + startupQualitySummary
				+ ", radioqSnapshot=" + startupRadioqCaptured);
	}

	private void captureStartupRadioQualitySnapshot() {
		String signalCmd = modemDriver.buildExtendedSignalCommand();
		if (signalCmd == null) {
			log.debug("RADIOQ: driver " + modemDriver.getVendorName() + " has no extended signal command.");
			return;
		}
		if (send(signalCmd)) {
			startupRadioqCaptured = true;
		} else {
			log.warn("RADIOQ: startup snapshot (" + signalCmd + ") failed.");
		}
	}

	private void captureDegradedRadioDiagnostics(String reason, boolean includeCellSnapshot) {
		log.info("FAILCTX: collecting degraded-path diagnostics (" + reason + ")");
		String signalCmd = modemDriver.buildExtendedSignalCommand();
		if (signalCmd != null && !sendDiagnosticBestEffort(signalCmd, DEGRADED_DIAGNOSTIC_TIMEOUT_MILLIS))
			log.warn("RADIOQ: failed to collect " + signalCmd + " during " + reason + ".");
		if (includeCellSnapshot) {
			String cellCmd = modemDriver.buildCellInfoCommand();
			if (cellCmd != null && !sendDiagnosticBestEffort(cellCmd, DEGRADED_DIAGNOSTIC_TIMEOUT_MILLIS))
				log.warn("CELL: failed to collect " + cellCmd + " during " + reason + ".");
		}
		if (!sendDiagnosticBestEffort("AT+CEER", DEGRADED_DIAGNOSTIC_TIMEOUT_MILLIS))
			log.warn("FAILCTX: failed to collect +CEER during " + reason + ".");
	}

	private boolean sendDiagnosticBestEffort(String cmd, long timeoutMillis) {
		// Keep degraded-path diagnostics bounded so they don't block recovery for full default timeouts.
		return send(cmd, "OK", timeoutMillis, false);
	}

	/**
	 * Parse and log a +CPSI: serving-cell line (SIMCom-specific).
	 * Per SIMCom SIM82xx AT Command Reference:
	 *   LTE format:     LTE,<op>,<MCC-MNC>,<TAC>,<CID>,<PCI>,<BAND>,<EARFCN>,<DL_BW>,<UL_BW>,<RSRQ>,<RSRP>,<RSSI>,<RSSNR>
	 *   NR5G_SA format: NR5G_SA,<op>,<MCC-MNC>,<TAC>,<NR-CID>,<PCI>,<BAND>,<NR-ARFCN>,<RSRP>,<RSRQ>,<SS-SINR>
	 * All signal-quality fields are reported in tenths of dB/dBm.
	 * Note the NR order: RSRP first, then RSRQ (opposite of LTE's RSRQ-then-RSRP),
	 * and the RSSI field is not present on NR.
	 * NO SERVICE: the whole line collapses to "NO SERVICE,Online" with no further fields.
	 * For both LTE and NR the parser derives a human-readable quality label and percentage
	 * from RSRP and appends it to the RADIOQ line and the STARTUP summary.
	 */
	private void logCpsiLine(String cpsiLine) {
		int colonIdx = cpsiLine.indexOf(':');
		if (colonIdx < 0) {
			log.info("RADIOQ: " + cpsiLine.trim());
			return;
		}
		String payload = cpsiLine.substring(colonIdx + 1).trim();
		String[] parts = payload.split(",");
		String mode = parts.length > 0 ? parts[0].trim().toUpperCase() : "";
		if (mode.startsWith("NO SERVICE") || parts.length < 10) {
			lastCpsiMode = mode.isEmpty() ? "n/a" : mode;
			lastCpsiBand = "n/a";
			lastCpsiPlmn = "n/a";
			lastCpsiRsrpDbm = null;
			lastCpsiRsrqDb = null;
			lastCpsiSinrDb = null;
			lastCpsiAtMillis = System.currentTimeMillis();
			log.info("RADIOQ: +CPSI " + payload + " (" + RadioGlossary.cpsiModeHint(mode) + ")");
			return;
		}
		String op = parts[1].trim();
		String plmn = parts[2].trim();
		String band = parts.length > 6 ? parts[6].trim() : "n/a";
		String channel = parts.length > 7 ? parts[7].trim() : "n/a";
		String bandPretty = RadioGlossary.bandHint(band);
		String modeHint = RadioGlossary.cpsiModeHint(mode);
		String plmnHint = RadioGlossary.plmnHint(plmn);
		if ("LTE".equals(mode) && parts.length >= 14) {
			Double rsrqDbl = parseCpsiTenthsToDouble(parts[10]);
			Double rsrpDbl = parseCpsiTenthsToDouble(parts[11]);
			Double sinrDbl = parseCpsiTenthsToDouble(parts[13]);
			String qualitySuffix = buildRsrpQualitySuffix(rsrpDbl);
			log.info("RADIOQ: +CPSI mode=" + mode + " (" + modeHint + ")"
					+ " op=" + op + " plmn=" + plmn + " (" + plmnHint + ")"
					+ " band=" + bandPretty + " earfcn=" + channel
					+ " rsrq=" + formatCpsiTenths(parts[10], "dB")
					+ " rsrp=" + formatCpsiTenths(parts[11], "dBm")
					+ " rssi=" + formatCpsiTenths(parts[12], "dBm")
					+ " sinr=" + formatCpsiTenths(parts[13], "dB")
					+ qualitySuffix);
			recordCpsiSnapshot(mode, band, plmn, rsrpDbl, rsrqDbl, sinrDbl);
			updateStartupQualitySummary(rsrpDbl);
		} else if (mode.startsWith("NR5G") && parts.length >= 11) {
			Double rsrpDbl = parseCpsiTenthsToDouble(parts[8]);
			Double rsrqDbl = parseCpsiTenthsToDouble(parts[9]);
			Double sinrDbl = parseCpsiTenthsToDouble(parts[10]);
			String qualitySuffix = buildRsrpQualitySuffix(rsrpDbl);
			log.info("RADIOQ: +CPSI mode=" + mode + " (" + modeHint + ")"
					+ " op=" + op + " plmn=" + plmn + " (" + plmnHint + ")"
					+ " band=" + bandPretty + " arfcn=" + channel
					+ " rsrp=" + formatCpsiTenths(parts[8], "dBm")
					+ " rsrq=" + formatCpsiTenths(parts[9], "dB")
					+ " sinr=" + formatCpsiTenths(parts[10], "dB")
					+ qualitySuffix);
			recordCpsiSnapshot(mode, band, plmn, rsrpDbl, rsrqDbl, sinrDbl);
			updateStartupQualitySummary(rsrpDbl);
		} else {
			log.info("RADIOQ: +CPSI " + payload + " (" + modeHint + ")");
		}
	}

	private void recordCpsiSnapshot(String mode, String band, String plmn,
			Double rsrpDbm, Double rsrqDb, Double sinrDb) {
		lastCpsiMode = (mode == null || mode.isEmpty()) ? "n/a" : mode;
		lastCpsiBand = (band == null || band.isEmpty()) ? "n/a" : band;
		lastCpsiPlmn = (plmn == null || plmn.isEmpty()) ? "n/a" : plmn;
		lastCpsiRsrpDbm = rsrpDbm;
		lastCpsiRsrqDb = rsrqDb;
		lastCpsiSinrDb = sinrDb;
		lastCpsiAtMillis = System.currentTimeMillis();
	}

	private String formatCpsiTenths(String raw, String unit) {
		if (raw == null) return "n/a";
		try {
			int v = Integer.parseInt(raw.trim());
			double scaled = v / 10.0;
			return String.format(Locale.ROOT, "%.1f %s", scaled, unit);
		} catch (NumberFormatException e) {
			return raw.trim();
		}
	}

	private Double parseCpsiTenthsToDouble(String raw) {
		if (raw == null) return null;
		try {
			return Integer.parseInt(raw.trim()) / 10.0;
		} catch (NumberFormatException e) {
			return null;
		}
	}

	/**
	 * Map RSRP in dBm to a 0-100% quality score. Linear: -120 dBm = 0%, -80 dBm = 100%.
	 * These thresholds match typical cellular reception guidelines (better than -80 dBm
	 * is excellent reception; worse than -120 dBm is unusable).
	 */
	private int rsrpToQualityPercent(double rsrpDbm) {
		double pct = (rsrpDbm + 120.0) / 40.0 * 100.0;
		if (pct < 0) return 0;
		if (pct > 100) return 100;
		return (int) Math.round(pct);
	}

	private String rsrpToQualityLabel(double rsrpDbm) {
		if (rsrpDbm >= -80)  return "excellent";
		if (rsrpDbm >= -90)  return "good";
		if (rsrpDbm >= -100) return "fair";
		if (rsrpDbm >= -110) return "poor";
		return "very poor";
	}

	private String buildRsrpQualitySuffix(Double rsrpDbm) {
		if (rsrpDbm == null) return "";
		return " quality=" + rsrpToQualityLabel(rsrpDbm)
				+ " (" + rsrpToQualityPercent(rsrpDbm) + "%)";
	}

	private void updateStartupQualitySummary(Double rsrpDbm) {
		if (rsrpDbm == null) return;
		startupQualitySummary = String.format(Locale.ROOT, "RSRP %.1f dBm (%d%%, %s)",
				rsrpDbm, rsrpToQualityPercent(rsrpDbm), rsrpToQualityLabel(rsrpDbm));
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
			return "4G LTE";
		case 11:
			return "5G SA";
		case 12:
			return "5G NSA";
		case 13:
			return "5G NGEN-DC";
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
		modemDriver.updateIdentityFromLine(rawLine);
	}

	private int fastPollMillis() {
		return modemDriver != null ? modemDriver.getSerialPollIntervalMillis() : sleepWhile;
	}

	private int sendPreSleepMillis() {
		return modemDriver != null ? modemDriver.getSerialSendPreSleepMillis() : sleepWhile;
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
		case 11:
			log.info("RADIOT: NR5G (5G SA)");
			watchdogList.set(3, "5G");
			break;
		case 12:
			log.info("RADIOT: EN-DC (5G NSA, LTE+NR dual connectivity)");
			watchdogList.set(3, "5G/NSA");
			break;
		case 13:
			log.info("RADIOT: NGEN-DC (5G NR with E-UTRA dual connectivity)");
			watchdogList.set(3, "5G/DC");
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
		// Accept 0-7 (legacy through LTE) plus 11/12/13 (NR SA, EN-DC NSA, NGEN-DC).
		// 8-10 are 3GPP-reserved / modem-vendor-specific and currently unused by Swisscom.
		boolean ready = !providerName.isEmpty()
				&& ((ratValue >= 0 && ratValue <= 7) || (ratValue >= 11 && ratValue <= 13));
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
			// On LTE, AT+CSQ often returns 99 ("not known") because the modem reports
			// signal via RSRP/RSRQ instead of legacy RSSI. Accept this when registered
			// on LTE and capture AT+CPSI for actual signal quality diagnostics.
			if (csqValue == 99 && startupRegistrationReady && startupNetworkReady && "4G/LTE".equals(startupRatLabel)) {
				String extSignalCmd = modemDriver.buildExtendedSignalCommand();
				String extTag = (extSignalCmd != null) ? extSignalCmd.replace("AT", "+") : "ext";
				log.info("SIGNAL: CSQ 99 on LTE - accepting as ready (RSSI not populated on LTE). Capturing "
						+ (extSignalCmd != null ? extSignalCmd : "(no extended signal command)") + " for diagnostics.");
				startupSignalReady = true;
				watchdogList.set(4, "n/a");
				watchdogList.set(5, "LTE");
				startupSignalSummary = "99/31 [LTE," + extTag + "]";
				if (extSignalCmd != null)
					sendDiagnosticBestEffort(extSignalCmd, DEGRADED_DIAGNOSTIC_TIMEOUT_MILLIS);
				return;
			}
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
		String strengthLabel = RadioGlossary.classifyCsq(csqValue).label();
		if (csqValue <= 9) {
			log.info("SIGNAL: " + csqValue + "/1-9/31 [+---] " + strengthLabel);
			watchdogList.set(5, "+---");
			startupSignalSummary = csqValue + "/31 [+---]," + percent + "%," + strengthLabel;
		} else if (csqValue >= 10 && csqValue <= 14) {
			log.info("SIGNAL: " + csqValue + "/10-14/31 [++--] " + strengthLabel);
			watchdogList.set(5, "++--");
			startupSignalSummary = csqValue + "/31 [++--]," + percent + "%," + strengthLabel;
		} else if (csqValue >= 15 && csqValue <= 19) {
			log.info("SIGNAL: " + csqValue + "/15-19/31 [+++-] " + strengthLabel);
			watchdogList.set(5, "+++-");
			startupSignalSummary = csqValue + "/31 [+++-]," + percent + "%," + strengthLabel;
		} else if (csqValue >= 20 && csqValue <= 31) {
			log.info("SIGNAL: " + csqValue + "/20-31/31 [++++] " + strengthLabel);
			watchdogList.set(5, "++++");
			startupSignalSummary = csqValue + "/31 [++++]," + percent + "%," + strengthLabel;
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
			log.info("Exiting program after reboot invocation.");
			System.exit(0);	// Just in case the reboot doesn't work as expected, the watchdog-reboot would be the fall-back
		} catch (IOException e) {
			// Do NOT System.exit(0) here. A clean exit would stop the watchdog file updates
			// and hide the failure. Leaving the process alive lets the external hardware/file
			// watchdog observe staleness and recover the device.
			log.error("Failed to execute reboot command. Leaving process alive for watchdog-driven recovery.", e);
		}
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
