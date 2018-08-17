# atclient
MobileID USAT Responder for [Raspberry PI 3](https://www.raspberrypi.org/products/raspberry-pi-3-model-b-plus) to operate one or multiple SIM/LTE wireless terminal(s).

![Raspberry PI 3 B+](img/raspi.jpg?raw=true "Raspberry PI 3 B+") ![HCP HIT wireless terminal](img/hitu4.jpg?raw=true "HCP HIT wireless terminal")

ATClient application can be used to respond to SIM Toolkit requests from the Mobile ID application in a fully automated manner. It does not require any user interaction to respond to mobile phone STK screens (e.g. input Mobile ID PIN). 

Serial port is auto detected at startup and re-initialized in case of communication interruption.

This setup can be useful for automated e2e Mobile ID signature monitoring purpose.

### What you will need (recommended)

- [Mobile ID SIM card](https://mobileid.ch)
- [Raspberry PI 3 B+](https://www.raspberrypi.org/products/raspberry-pi-3-model-b-plus) running latest RASPBIAN ~ EUR 35.-
- [SIM based wireless terminal](http://electronicshcp.com/product/hit-u4-lte) ~ EUR 100.-

### Wireless terminal

This application has been successfully tested with Raspberry PI 3 B+ (raspbian), Windows 10 desktop PC and a PLS8-E LTE wireless terminal: [HCP HIT U4 LTE terminal](http://electronicshcp.com/product/hit-u4-lte) ([Technical documentation](https://developer.gemalto.com/documentation/pls8-e-technical-documentation))

### Serial port

To find serial port details on linux:

1. Unplug the GSM terminal
2. Run: `sudo dmesg -c`
3. Plug in the GSM terminal and wait a few seconds
4. Run: `sudo dmesg`

You may also trace the syslog while connecting the device: `sudo tail -f /var/log/syslog`

Port name must be something like "/dev/ttyUSB0" (Linux) or "COM4" (Windows).

#### Baud rate

We recommend to keep default baud rate 9600. 
Verify baud rate: `sudo stty -F /dev/ttyUSB0`

### How To

#### Clone GIT repository
```
pi@raspberypi:~ $ git clone https://github.com/phaupt/atclient.git
```

#### Compile java source
```
pi@raspberypi:~/atclient $ mkdir class
pi@raspberypi:~/atclient $ javac -d ./class -cp "./lib/*" ./src/com/swisscom/atclient/*.java
```

#### Run the application

##### Application help
```
pi@raspberypi:~/atclient $ java -Dlog.file=GsmClient.log -Dlog4j.configurationFile=log4j2.xml -cp "./class:./lib/*" com.swisscom.atclient.GsmClient --help

*** GSM AT Client ***

Usage: GsmClient [<MODE>]

<MODE>	Switch operation mode:
	ER	Switch to Explicit Response (ER) and shutdown.
	AR	Switch to Automatic Response (AR) and shutdown.

If no <MODE> argument found: Run user emulation with automatic serial port detection (ER operation mode only)

Optional system properties with example values:
	-Dlog.file=GsmClient.log               # Application log file
	-Dlog4j.configurationFile=log4j2.xml   # Location of log4j.xml configuration file
	-DtargetMsisdn=+41791234567            # Target phone number to forward text sms content
	-Dserial.port=COM16                    # Select specific serial port (no automatic port detection)
```

##### Switch to Explicit Response (ER) mode

As a first step, you must switch the terminal from factory default Automatic Response (AR) mode to Explicit Response (ER) mode.

`pi@raspberypi:~/atclient $ java -Dserial.port=/dev/ttyACM1 -Dlog.file=GsmClient.log -Dlog4j.configurationFile=log4j2.xml -cp "./class:./lib/*" com.swisscom.atclient.GsmClient ER`

##### User Emulation

Once your terminal is in Explicit Response (ER) mode you can run the application in continuous User Emulation mode.

Serial port is auto detected at start and re-initialized in case of communication interruption.

Note that any GET-INPUT proactive STK commands will always be responded with the value '123456'.

`pi@raspberypi:~/atclient $ java -Dlog.file=GsmClient.log -Dlog4j.configurationFile=log4j2.xml -cp "./class:./lib/*" com.swisscom.atclient.GsmClient`

##### Auto start at boot

Application startup on boot can be configured in several different ways. We recommend to use init.d, see [here](init.d/).

### Logfile

Edit the `log4j2.xml` to configure a different log level, if needed. DEBUG level is default.

With the default log4j configuration you can pass the log file name as a command line parameter: `-Dlog.file=GsmClient.log`

#### Example logfile output

Example log in case of a Mobile ID signature processing. It's lightning fast (less than a second).
```
2018-08-16 08:58:31,564 16344@U278884 COM22 [DBG] <<< RX ^SSTN: 33
2018-08-16 08:58:31,564 16344@U278884 COM22 [INF] DISPLAY TEXT (Command Code 33)
2018-08-16 08:58:31,564 16344@U278884 COM22 [DBG] >>> TX at^sstgi=33
2018-08-16 08:58:31,616 16344@U278884 COM22 [DBG] <<< RX ^SSTGI: 33,129,"00540065007300740020005300690067006E00610074007500720065003A00200041006C006C006F0077002000740065007300740069006E00670020006F006600200079006F007500720020004D006F00620069006C0065002000490044003F0020002800790032005A004A004700490029",0,1,0
2018-08-16 08:58:31,620 16344@U278884 COM22 [INF] TEXT: "Test Signature: Allow testing of your Mobile ID? (y2ZJGI)"
2018-08-16 08:58:31,620 16344@U278884 COM22 [DBG] <<< RX OK
2018-08-16 08:58:31,620 16344@U278884 COM22 [DBG] >>> TX at^sstr=33,0
2018-08-16 08:58:31,671 16344@U278884 COM22 [DBG] <<< RX OK
2018-08-16 08:58:31,722 16344@U278884 COM22 [DBG] <<< RX ^SSTN: 35
2018-08-16 08:58:31,722 16344@U278884 COM22 [INF] GET INPUT (Command Code 35)
2018-08-16 08:58:31,722 16344@U278884 COM22 [DBG] >>> TX at^sstgi=35
2018-08-16 08:58:31,773 16344@U278884 COM22 [DBG] <<< RX ^SSTGI: 35,4,"002000410075007400680065006E0074006900630061007400650020007700690074006800200079006F007500720020004D006F00620069006C0065002000490044002000500049004E",1,15,"",1,0
2018-08-16 08:58:31,774 16344@U278884 COM22 [INF] TEXT: "Authenticate with your Mobile ID PIN"
2018-08-16 08:58:31,774 16344@U278884 COM22 [DBG] <<< RX OK
2018-08-16 08:58:31,774 16344@U278884 COM22 [DBG] >>> TX at^sstr=35,0,,003100320033003400350036
2018-08-16 08:58:31,824 16344@U278884 COM22 [DBG] <<< RX OK
2018-08-16 08:58:32,330 16344@U278884 COM22 [DBG] <<< RX ^SSTN: 19
2018-08-16 08:58:32,330 16344@U278884 COM22 [INF] SEND MESSAGE (Command Code 19)
2018-08-16 08:58:32,330 16344@U278884 COM22 [DBG] >>> TX at^sstgi=19
2018-08-16 08:58:32,381 16344@U278884 COM22 [DBG] <<< RX ^SSTGI: 19,0,"",1,0
2018-08-16 08:58:32,381 16344@U278884 COM22 [DBG] <<< RX OK
2018-08-16 08:58:32,382 16344@U278884 COM22 [DBG] >>> TX at^sstr=19,0
2018-08-16 08:58:32,585 16344@U278884 COM22 [DBG] <<< RX ^SSTR: 19,0,""
2018-08-16 08:58:32,585 16344@U278884 COM22 [DBG] <<< RX OK
2018-08-16 08:58:32,636 16344@U278884 COM22 [DBG] <<< RX ^SSTN: 19
2018-08-16 08:58:32,636 16344@U278884 COM22 [INF] SEND MESSAGE (Command Code 19)
2018-08-16 08:58:32,636 16344@U278884 COM22 [DBG] >>> TX at^sstgi=19
2018-08-16 08:58:32,688 16344@U278884 COM22 [DBG] <<< RX ^SSTGI: 19,0,"",1,0
2018-08-16 08:58:32,688 16344@U278884 COM22 [DBG] <<< RX OK
2018-08-16 08:58:32,688 16344@U278884 COM22 [DBG] >>> TX at^sstr=19,0
2018-08-16 08:58:32,891 16344@U278884 COM22 [DBG] <<< RX ^SSTR: 19,0,""
2018-08-16 08:58:32,892 16344@U278884 COM22 [DBG] <<< RX OK
2018-08-16 08:58:32,993 16344@U278884 COM22 [DBG] <<< RX ^SSTN: 19
2018-08-16 08:58:32,994 16344@U278884 COM22 [INF] SEND MESSAGE (Command Code 19)
2018-08-16 08:58:32,994 16344@U278884 COM22 [DBG] >>> TX at^sstgi=19
2018-08-16 08:58:33,045 16344@U278884 COM22 [DBG] <<< RX ^SSTGI: 19,0,"",1,0
2018-08-16 08:58:33,045 16344@U278884 COM22 [DBG] <<< RX OK
2018-08-16 08:58:33,045 16344@U278884 COM22 [DBG] >>> TX at^sstr=19,0
2018-08-16 08:58:33,245 16344@U278884 COM22 [DBG] <<< RX ^SSTR: 19,0,""
2018-08-16 08:58:33,245 16344@U278884 COM22 [DBG] <<< RX OK
2018-08-16 08:58:33,296 16344@U278884 COM22 [DBG] <<< RX ^SSTN: 254
2018-08-16 08:58:33,296 16344@U278884 COM22 [INF] SIM Applet returns to main menu (Command Code 254)

```
