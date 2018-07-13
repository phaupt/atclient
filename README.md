# atclient
MobileID USAT Responder for [Raspberry PI 3](https://www.raspberrypi.org/products/raspberry-pi-3-model-b-plus) to operate one or multiple SIM/LTE wireless terminal(s).

![Raspberry PI 3 B+](img/raspi.jpg?raw=true "Raspberry PI 3 B+") ![HCP HIT wireless terminal](img/hitu4.jpg?raw=true "HCP HIT wireless terminal")

ATClient application can be used to respond to SIM Toolkit requests from the Mobile ID application in a fully automated manner. It does not require any user interaction to respond to mobile phone STK screens (e.g. input Mobile ID PIN). 

Very useful for automated e2e monitoring purpose.

### What you will need (recommended)

- [Mobile ID SIM card](https://mobileid.ch)
- [Raspberry PI 3 B+](https://www.raspberrypi.org/products/raspberry-pi-3-model-b-plus) ~ EUR 35.-
- [SIM based wireless terminal](http://electronicshcp.com/product/hit-u4-lte) ~ EUR 100.-

### Wireless terminal

This application has been successfully tested with Raspberry PI 3 B+ (raspbian), Windows 10 desktop PC and a PLS8-E LTE wireless terminal: [HCP HIT U4 LTE terminal](http://electronicshcp.com/product/hit-u4-lte) ([Technical documentation](https://developer.gemalto.com/documentation/pls8-e-technical-documentation))

### Serial port configuration

1. Unplug the modem
2. Run: `sudo dmesg -c`
3. Plug in the modem and wait a few seconds
4. Run: `sudo dmesg`

You may also trace the syslog while connecting the device: `sudo tail -f /var/log/syslog`

Port descriptor must be something like "/dev/ttyUSB0" (Linux) or "COM4" (Windows).

#### Baud rate

We recommend default baud rate 9600. 
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

##### Show client parameters
```
pi@raspberypi:~/atclient $ java -Dlog.file=GsmClient-ttyACM1.log -Dlog4j.configurationFile=log4j2.xml -cp "./class:./lib/*" com.swisscom.atclient.GsmClient /dev/ttyACM1 UE

*** GSM AT Client ***

Usage: GsmClient <PORT> <CMD>

<PORT>	Serial Port
<CMD>	List of supported commands:
	ER	Switch to Explicit Response (ER) and shutdown
	AR	Switch to Automatic Response (AR, Factory Default) and shutdown
	UE	Run continues Alauda User Emulation (UE) in Explicit Response Mode
```

##### Example usage: Switch to Explicit Response (ER) mode

As a first step, you should switch the terminal from default Automatic Response (AR) mode to Explicit Response (ER) mode.

`pi@raspberypi:~/atclient $ java -Dlog.file=GsmClient-ttyACM1.log -Dlog4j.configurationFile=log4j2.xml -cp "./class:./lib/*" com.swisscom.atclient.GsmClient /dev/ttyACM1 ER`

##### Example usage: User Emulation mode (UE)

Once your terminal is in Explicit Response (ER) mode you can run the application in continuous User Emulation (UE) mode.

Note that GET-INPUT proactive STK commands will be responded with default code '123456'.

`pi@raspberypi:~/atclient $ java -Dlog.file=GsmClient-ttyACM1.log -Dlog4j.configurationFile=log4j2.xml -cp "./class:./lib/*" com.swisscom.atclient.GsmClient /dev/ttyACM1 UE`

##### Nohup

Nohup will detach a process you run from your current console and let it continue when you close the terminal. This might be usefuly when you run the User Emulation (UE) mode for continuous end-to-end monitoring purpose.

`pi@raspberypi:~/atclient $ nohup java -Dlog.file=GsmClient-ttyACM1.log -Dlog4j.configurationFile=log4j2.xml -cp "./class:./lib/*" com.swisscom.atclient.GsmClient /dev/ttyACM1 UE &`

### Logfile

Edit the `log4j2.xml` to configure a different log level, if needed. DEBUG level is default.

With the default log4j configuration you can pass the log file name as a command line parameter: `-Dlog.file=GsmClient-ttyACM1.log`

#### Example logfile output

Mobile ID signature processing in less than a second:
```
2018-07-13 10:30:22,574 [DEBUG] <<< RX ^SSTN: 33
2018-07-13 10:30:22,574 [INFO] ### 33: DISPLAY TEXT ####
2018-07-13 10:30:22,574 [DEBUG] >>> TX at^sstgi=33
2018-07-13 10:30:22,585 [DEBUG] <<< RX ^SSTGI: 33,129,"0074006500730074002E0063006F006D003A00200044006F00200079006F0075002000770061006E007400200074006F0020006C006F00670069006E00200074006F00200063006F00720070006F0072006100740065002000560050004E003F0020002800650059004F0038007000790029",0,1,0
2018-07-13 10:30:22,585 [INFO] TEXT: "test.com: Do you want to login to corporate VPN? (eYO8py)"
2018-07-13 10:30:22,585 [DEBUG] <<< RX OK
2018-07-13 10:30:22,585 [DEBUG] >>> TX at^sstr=33,0
2018-07-13 10:30:22,596 [DEBUG] <<< RX OK
2018-07-13 10:30:22,627 [DEBUG] <<< RX ^SSTN: 35
2018-07-13 10:30:22,627 [INFO] ### 35: GET INPUT ####
2018-07-13 10:30:22,627 [DEBUG] >>> TX at^sstgi=35
2018-07-13 10:30:22,638 [DEBUG] <<< RX ^SSTGI: 35,4,"00410075007400680065006E0074006900630061007400650020007700690074006800200079006F007500720020004D006F00620069006C0065002000490044002000500049004E",1,15,"",1,0
2018-07-13 10:30:22,639 [INFO] TEXT: "Authenticate with your Mobile ID PIN"
2018-07-13 10:30:22,639 [DEBUG] <<< RX OK
2018-07-13 10:30:22,639 [DEBUG] >>> TX at^sstr=35,0,,003100320033003400350036
2018-07-13 10:30:22,650 [DEBUG] <<< RX OK
2018-07-13 10:30:22,991 [DEBUG] <<< RX ^SSTN: 19
2018-07-13 10:30:22,991 [INFO] ### 19: SEND MESSAGE ###
2018-07-13 10:30:22,991 [DEBUG] >>> TX at^sstgi=19
2018-07-13 10:30:23,002 [DEBUG] <<< RX ^SSTGI: 19,0,"",1,0
2018-07-13 10:30:23,002 [DEBUG] <<< RX OK
2018-07-13 10:30:23,002 [DEBUG] >>> TX at^sstr=19,0
2018-07-13 10:30:23,184 [DEBUG] <<< RX ^SSTR: 19,0,""
2018-07-13 10:30:23,184 [DEBUG] <<< RX OK
2018-07-13 10:30:23,205 [DEBUG] <<< RX ^SSTN: 254
2018-07-13 10:30:23,205 [INFO] ### 254: SIM Applet returns to main menu ####
```
