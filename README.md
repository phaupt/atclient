# atclient
MobileID USAT Responder for Raspberry PI to operate one or many PLS8-E LTE terminals.

![Raspberry PI](img/raspi.jpg?raw=true "Raspberry PI") ![PLS8-E LTE terminal](img/hitu4.jpg?raw=true "HCP HIT wireless terminal")

#### Features

* Auto-respond SIM Toolkit requests, for example a [Mobile ID](https://mobileid.ch) authentication request (using PIN 123456)
* USB serial port auto-detection
* Automatic or forced selection of radio access technology(4G/3G/2G)
* Forward incoming Text SMS to MSISDN (optional)
* Publish incoming Text SMS to URL (optional)

### What you will need
Recommended setup:
* [Mobile ID SIM card](https://mobileid.ch)
* [Raspberry PI 4](https://www.raspberrypi.org/products/raspberry-pi-4-model-b)
* [PLS8-E LTE terminal](http://electronicshcp.com/product/hit-u4-lte)

### Wireless terminal

This application has been tested with Raspberry PI 4 and PLS8-E LTE terminal. [HCP HIT U4 LTE terminal](http://electronicshcp.com/product/hit-u4-lte), [Technical documentation](https://developer.gemalto.com/documentation/pls8-e-technical-documentation), [Windows Drivers](https://files.c-wm.net/index.php/s/GRPgoz5m7a73c54) (Password: Gemalto019)

### Serial port

To find serial port details on linux:

1. Unplug the GSM terminal
2. Run: `sudo dmesg -c`
3. Plug in the GSM terminal and wait a few seconds
4. Run: `sudo dmesg`

You may also trace the syslog while connecting the device: `sudo tail -f /var/log/syslog`

#### Baud rate

We recommend to keep default baud rate 9600. 

### How To

#### Clone GIT repository
`pi@raspberypi:~ $ git clone https://github.com/phaupt/atclient.git`

#### Compile java source
```
pi@raspberypi:~/atclient $ mkdir class
pi@raspberypi:~/atclient $ javac -d ./class -cp "./lib/*" ./src/com/swisscom/atclient/*.java
```

#### ATClient Configuration

Edit the `atclient.cfg` according to your needs.

#### Run the application

##### Application help
```
*** AT Client ***

Usage: ATClient [<MODE>]

<MODE>	Switch operation mode:
	ER	Switch to Explicit Response (ER) and shutdown.
	AR	Switch to Automatic Response (AR) and shutdown.

If no <MODE> argument found: Run user emulation with automatic serial port detection (ER operation mode only)

	-Dlog.file=atclient.log                # Application log file
	-Dlog4j.configurationFile=log4j2.xml   # Location of log4j.xml configuration file
	-Dserial.port=/dev/ttyACM1             # Select specific serial port (no automatic port detection)
```

##### Switch to Explicit Response (ER) mode

As a first step, you must switch the terminal from factory default Automatic Response (AR) mode to Explicit Response (ER) mode.

`pi@raspberypi:~/atclient $ java -Dserial.port=/dev/ttyACM1 -Dconfig.file=atclient.cfg -Dlog.file=atclient.log -Dlog4j.configurationFile=log4j2.xml -cp "./class:./lib/*" com.swisscom.atclient.ATClient ER`

##### User Emulation

Once your terminal is in Explicit Response (ER) mode you can run the application in continuous User Emulation mode.

`pi@raspberypi:~/atclient $ java -Dconfig.file=atclient.cfg -Dlog.file=atclient.log -Dlog4j.configurationFile=log4j2.xml -cp "./class:./lib/*" com.swisscom.atclient.ATClient`

##### Keywords

If a keyword (case sensitive) is found in the Mobile ID authentication message, the ATClient will invoke specific actions.
This is helpful to simulate a specific user behavior.

`'CANCEL'       : TerminalResponse '16' - Proactive SIM session terminated by user.`

`'STKTIMEOUT'   : TerminalResponse '18' - No response from user.`

`'USERDELAY=x'  : The very first TerminalResponse will be delayed by x seconds (supported values are 1 to 9).`

`'BLOCKPIN'     : Mobile ID PIN will be blocked.`

`'RAT=x'        : Set Radio Access Technology to x (supported values are: A=Automatic, 0=2G, 2=3G, 7=4G).`

`'REBOOT'       : Execute 'sudo reboot' linux command (make sure that user is not required to enter sudo password).`

##### Auto start at boot

There are several ways to autostart ATClient. The easiest way is to edit `/etc/rc.local`. Just before the `exit 0`, add:
`(/bin/sleep 60 && /usr/bin/java -Dconfig.file=/home/pi/atclient/atclient.cfg -Dlog.file=/home/pi/atclient/atclient.log -Dlog4j.configurationFile=/home/pi/atclient/log4j2.xml -cp "/home/pi/atclient/class:/home/pi/atclient/lib/*" com.swisscom.atclient.ATClient) &`

The sleep of 60 seconds is recommende because the PLS8-E LTE terminal requires 30-40 seconds boot time.

### Logfile

Edit the `log4j2.xml` to configure a different log level, if needed. DEBUG level is default.

With the default log4j configuration you can pass the log file name as a command line parameter: `-Dlog.file=atclient.log`

#### Example logfile output

Example log for a Mobile ID signature response:
```
2020-04-12 20:04:45,222 16768@DESKTOP-JOULENUKE ttyACM1 +41791234567 [DBG] <<< RX ^SSTN: 33
2020-04-12 20:04:45,222 16768@DESKTOP-JOULENUKE ttyACM1 +41791234567 [INF] DISPLAY TEXT (Command Code 33)
2020-04-12 20:04:45,222 16768@DESKTOP-JOULENUKE ttyACM1 +41791234567 [DBG] >>> TX at^sstgi=33
2020-04-12 20:04:45,272 16768@DESKTOP-JOULENUKE ttyACM1 +41791234567 [DBG] <<< RX ^SSTGI: 33,129,"0074006500730074002E0063006F006D003A00200044006F00200079006F0075002000770061006E007400200074006F0020006C006F00670069006E00200074006F00200063006F00720070006F0072006100740065002000560050004E003F002000280038003100700051005200780029",0,1,0
2020-04-12 20:04:45,273 16768@DESKTOP-JOULENUKE ttyACM1 +41791234567 [INF] TEXT: "test.com: Do you want to login to corporate VPN? (81pQRx)"
2020-04-12 20:04:45,273 16768@DESKTOP-JOULENUKE ttyACM1 +41791234567 [DBG] <<< RX OK
2020-04-12 20:04:45,273 16768@DESKTOP-JOULENUKE ttyACM1 +41791234567 [DBG] >>> TX at^sstr=33,0
2020-04-12 20:04:45,324 16768@DESKTOP-JOULENUKE ttyACM1 +41791234567 [DBG] <<< RX OK
2020-04-12 20:04:45,374 16768@DESKTOP-JOULENUKE ttyACM1 +41791234567 [DBG] <<< RX ^SSTN: 35
2020-04-12 20:04:45,374 16768@DESKTOP-JOULENUKE ttyACM1 +41791234567 [INF] GET INPUT (Command Code 35)
2020-04-12 20:04:45,374 16768@DESKTOP-JOULENUKE ttyACM1 +41791234567 [DBG] >>> TX at^sstgi=35
2020-04-12 20:04:45,424 16768@DESKTOP-JOULENUKE ttyACM1 +41791234567 [DBG] <<< RX ^SSTGI: 35,4,"00410075007400680065006E0074006900630061007400650020007700690074006800200079006F007500720020004D006F00620069006C0065002000490044002000500049004E",1,15,"",1,0
2020-04-12 20:04:45,424 16768@DESKTOP-JOULENUKE ttyACM1 +41791234567 [INF] TEXT: "Authenticate with your Mobile ID PIN"
2020-04-12 20:04:45,424 16768@DESKTOP-JOULENUKE ttyACM1 +41791234567 [DBG] <<< RX OK
2020-04-12 20:04:45,424 16768@DESKTOP-JOULENUKE ttyACM1 +41791234567 [DBG] >>> TX at^sstr=35,0,,003100320033003400350036
2020-04-12 20:04:45,475 16768@DESKTOP-JOULENUKE ttyACM1 +41791234567 [DBG] <<< RX OK
2020-04-12 20:04:45,778 16768@DESKTOP-JOULENUKE ttyACM1 +41791234567 [DBG] <<< RX ^SSTN: 19
2020-04-12 20:04:45,778 16768@DESKTOP-JOULENUKE ttyACM1 +41791234567 [INF] SEND MESSAGE (Command Code 19)
2020-04-12 20:04:45,778 16768@DESKTOP-JOULENUKE ttyACM1 +41791234567 [DBG] >>> TX at^sstgi=19
2020-04-12 20:04:45,828 16768@DESKTOP-JOULENUKE ttyACM1 +41791234567 [DBG] <<< RX ^SSTGI: 19,0,"",1,0
2020-04-12 20:04:45,828 16768@DESKTOP-JOULENUKE ttyACM1 +41791234567 [DBG] <<< RX OK
2020-04-12 20:04:45,828 16768@DESKTOP-JOULENUKE ttyACM1 +41791234567 [DBG] >>> TX at^sstr=19,0
2020-04-12 20:04:46,079 16768@DESKTOP-JOULENUKE ttyACM1 +41791234567 [DBG] <<< RX ^SSTR: 19,0,""
2020-04-12 20:04:46,079 16768@DESKTOP-JOULENUKE ttyACM1 +41791234567 [DBG] <<< RX OK
2020-04-12 20:04:46,130 16768@DESKTOP-JOULENUKE ttyACM1 +41791234567 [DBG] <<< RX ^SSTN: 254
2020-04-12 20:04:46,130 16768@DESKTOP-JOULENUKE ttyACM1 +41791234567 [INF] SIM Applet returns to main menu (Command Code 254)
2020-04-12 20:04:46,130 16768@DESKTOP-JOULENUKE ttyACM1 +41791234567 [DBG] >>> TX AT+COPS?
2020-04-12 20:04:46,180 16768@DESKTOP-JOULENUKE ttyACM1 +41791234567 [DBG] <<< RX +COPS: 0,2,"22801",7
2020-04-12 20:04:46,180 16768@DESKTOP-JOULENUKE ttyACM1 +41791234567 [INF] Radio Access Technology: E-UTRAN = 4G/LTE
2020-04-12 20:04:46,180 16768@DESKTOP-JOULENUKE ttyACM1 +41791234567 [DBG] <<< RX OK
2020-04-12 20:04:46,180 16768@DESKTOP-JOULENUKE ttyACM1 +41791234567 [DBG] >>> TX AT+CSQ
2020-04-12 20:04:46,231 16768@DESKTOP-JOULENUKE ttyACM1 +41791234567 [DBG] <<< RX +CSQ: 15,99
2020-04-12 20:04:46,231 16768@DESKTOP-JOULENUKE ttyACM1 +41791234567 [INF] Signal strength: 15/15-19/31 = GOOD
2020-04-12 20:04:46,231 16768@DESKTOP-JOULENUKE ttyACM1 +41791234567 [DBG] <<< RX OK

```
