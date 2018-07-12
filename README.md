# atclient
MobileID ATClient for [Raspberry PI 3](https://www.raspberrypi.org/products/raspberry-pi-3-model-b-plus) to operate one or multiple SIM based wireless terminal(s).

![Raspberry PI 3 B+](img/raspi.jpg?raw=true "Raspberry PI 3 B+") ![HCP HIT wireless terminal](img/hitu4.jpg?raw=true "HCP HIT wireless terminal")

ATClient application can be used to respond to SIM Toolkit requests from the Mobile ID application in a fully automated manner. It does not require any user interaction to respond to mobile phone STK screens (e.g. input Mobile ID PIN). 

Very useful for automated e2e monitoring purpose.

### What you will need (recommended)

- [Mobile ID SIM card](https://mobileid.ch)
- [Raspberry PI 3 B+](https://www.raspberrypi.org/products/raspberry-pi-3-model-b-plus) ~ EUR 35.-
- [SIM based wireless terminal](http://electronicshcp.com/product/hit-u4-lte) ~ EUR 100.-

### Wireless terminal

This application has been successfully tested on Raspberry PI 3 B+ (raspbian) and Windows 10 desktop PC. A wireless terminal from the list below was used. 

- [HCP HIT U8 PHS8 3G terminal](http://electronicshcp.com/product/hit-u8)
- [HCP HIT U4 LTE terminal](http://electronicshcp.com/product/hit-u4-lte)

### Serial port configuration

1. Unplug the modem
2. Run: `sudo dmesg -c`
3. Plug in the modem and wait a few seconds
4. Run: `sudo dmesg`

You may also trace the syslog while connecting the device: `sudo tail -f /var/log/syslog`

Port descriptor must be something like "/dev/ttyUSB0" (Linux) or "COM4" (Windows).

#### Baud rate

Default baud rate is 9600.
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
pi@raspberypi:~/atclient $ java -Dlog.file=GsmClient-COM4.log -Dlog4j.configurationFile=log4j2.xml -cp "./class:./lib/*" com.swisscom.atclient.GsmClient /dev/ttyACM1 UE

*** GSM AT Client ***

Usage: GsmClient <PORT> <CMD>

<PORT>	Serial Port
<CMD>	List of supported commands:
	ER	Switch to Explicit Response (ER) and reboot
	AR	Switch to Automatic Response (AR, Factory Default) and reboot
	UE	Run Alauda User Emulation (UE) in Explicit Response Mode
```

##### Example usage: User Emulation mode (UE)

`pi@raspberypi:~/atclient $ java -Dlog.file=GsmClient-COM4.log -Dlog4j.configurationFile=log4j2.xml -cp "./class:./lib/*" com.swisscom.atclient.GsmClient /dev/ttyACM1 UE`

##### Nohup

Nohup will detach a process you run from your current console and let it continue when you close the terminal:

`pi@raspberypi:~/atclient $ nohup java -Dlog.file=GsmClient-COM4.log -Dlog4j.configurationFile=log4j2.xml -cp "./class:./lib/*" com.swisscom.atclient.GsmClient /dev/ttyACM1 UE &`

### Logfile

Edit the `log4j2.xml` to configure a different log level, if needed. DEBUG level is default.
If you set TRACE level, it will log all RX and TX traffic.

With the default log4j configuration you can pass the log file name as a command line parameter: `-Dlog.file=GsmClient-ttyACM1.log`

#### Example logfile output
```
2018-07-11 10:47:52,391 [INFO] Application started...
2018-07-11 10:47:52,405 [DEBUG] Attached Shutdown Hook
2018-07-11 10:47:52,405 [INFO] Init Serial Port in progress.
2018-07-11 10:47:52,522 [DEBUG] Index: 0; COM3; Serial0; Intel(R) Active Management Technology - SOL (COM3)
2018-07-11 10:47:52,522 [DEBUG] Index: 1; COM4; PH8; Cinterion PH8 HSPA USB Com Port (COM4)
2018-07-11 10:47:52,522 [DEBUG] Index: 2; COM9; PH8; Cinterion PH8 HSPA USB reserved Com Port (COM9)
2018-07-11 10:47:52,522 [DEBUG] Index: 3; COM8; PH8; Cinterion PH8 HSPA USB NMEA Com Port (COM8)
2018-07-11 10:47:52,522 [DEBUG] Index: 4; COM11; PH8; Cinterion PH8 HSPA USB Modem
2018-07-11 10:47:52,522 [DEBUG] Selected Port: COM4
2018-07-11 10:47:53,529 [DEBUG] Selected Port successfully opened.
2018-07-11 10:47:53,945 [DEBUG] Set DTR: true
2018-07-11 10:47:53,945 [INFO] Connection successfully established.
2018-07-11 10:47:53,945 [INFO] Wait for 5 seconds...
2018-07-11 10:47:58,945 [DEBUG] ### Set SMS text mode ###
2018-07-11 10:47:58,946 [DEBUG] TX1: AT+CMGF=1
2018-07-11 10:47:59,262 [DEBUG] RX2: AT+CMGF=1
2018-07-11 10:47:59,331 [DEBUG] ### Activate the display of a URC on every received SMS ###
2018-07-11 10:47:59,647 [DEBUG] RX2: AT+CNMI=1,1
2018-07-11 10:47:59,710 [DEBUG] ### Retrieve Provider Details ###
2018-07-11 10:48:00,048 [DEBUG] RX2: AT+COPS?
2018-07-11 10:48:00,055 [DEBUG] RX2: +COPS: 0,0,"Swisscom",2
2018-07-11 10:48:00,055 [INFO] Operator: Swisscom
2018-07-11 10:48:00,133 [DEBUG] ### Retrieve Signal Strength Details ###
2018-07-11 10:48:00,450 [DEBUG] RX2: AT+CSQ
2018-07-11 10:48:00,450 [DEBUG] RX2: +CSQ: 30,99
2018-07-11 10:48:00,450 [INFO] SignalStrength: 30,99
2018-07-11 10:48:00,516 [DEBUG] ### Retrieve Wireless Data Service Details ###
2018-07-11 10:48:00,835 [DEBUG] RX2: AT+WS46=?
2018-07-11 10:48:00,835 [DEBUG] RX2: +WS46: (12,22,25)
2018-07-11 10:48:00,835 [INFO] Wireless Service:  (12,22,25)
2018-07-11 10:48:00,898 [DEBUG] ### Retrieve IMSI ###
2018-07-11 10:48:01,208 [DEBUG] RX2: AT+CIMI
2018-07-11 10:48:01,208 [DEBUG] RX2: 228012123638957
2018-07-11 10:48:01,208 [INFO] IMSI: 228012123638957
2018-07-11 10:48:01,267 [DEBUG] ### Retrieve IMEI ###
2018-07-11 10:48:01,599 [DEBUG] RX2: AT+CGSN
2018-07-11 10:48:01,599 [DEBUG] RX2: 359998040020283
2018-07-11 10:48:01,599 [INFO] IMEI: 359998040020283
2018-07-11 10:48:01,653 [DEBUG] ### Retrieve MSISDN ###
2018-07-11 10:48:01,985 [DEBUG] RX2: AT+CNUM
2018-07-11 10:48:01,985 [DEBUG] RX2: +CNUM: "\00\00\00\00\00\00\00\00\00\00\00\00\00\00\00\00","+41796691746",145
2018-07-11 10:48:02,047 [INFO] Ready to receive incoming data...
2018-07-11 10:49:18,100 [DEBUG] RX1: ^SSTN: 33
2018-07-11 10:49:18,100 [INFO] ### 33: DISPLAY TEXT ####
2018-07-11 10:49:18,101 [DEBUG] TX1: AT^SSTGI=33
2018-07-11 10:49:18,415 [DEBUG] RX2: at^sstgi=33
2018-07-11 10:49:18,415 [DEBUG] RX2: ^SSTGI: 33,129,"0074006500730074002E0063006F006D003A00200044006F00200079006F0075002000770061006E007400200074006F0020006C006F00670069006E00200074006F00200063006F00720070006F0072006100740065002000560050004E003F",0,1,0
2018-07-11 10:49:18,415 [INFO] UI Text="test.com: Do you want to login to corporate VPN?"
2018-07-11 10:49:18,478 [DEBUG] CANCEL: false | STKTIMEOUT: false | RESET: false
2018-07-11 10:49:18,479 [DEBUG] TX1: AT^SSTR=33,0
2018-07-11 10:49:18,531 [DEBUG] RX1: at^sstr=33,0
2018-07-11 10:49:18,531 [DEBUG] RX1: ^SSTN: 35
2018-07-11 10:49:18,531 [INFO] ### 35: GET INPUT ####
2018-07-11 10:49:18,531 [DEBUG] TX1: AT^SSTGI=35
2018-07-11 10:49:18,917 [DEBUG] CANCEL: false | STKTIMEOUT: false | RESET: false
2018-07-11 10:49:18,917 [DEBUG] TX1: AT^SSTR=35,0,,003100320033003400350036
2018-07-11 10:49:19,232 [DEBUG] RX2: at^sstgi=35
2018-07-11 10:49:19,233 [DEBUG] RX2: ^SSTGI: 35,4,"002000410075007400680065006E0074006900630061007400650020007700690074006800200079006F007500720020004D006F00620069006C0065002000490044002000500049004E",1,15,"",1,0
2018-07-11 10:49:19,233 [INFO] UI Text=" Authenticate with your Mobile ID PIN"
2018-07-11 10:49:19,233 [DEBUG] RX2: at^sstr=35,0,,003100320033003400350036
2018-07-11 10:49:19,602 [DEBUG] RX1: ^SSTN: 19
2018-07-11 10:49:19,602 [INFO] ### 19: SEND MESSAGE (Acknowledge) ###
2018-07-11 10:49:19,602 [DEBUG] TX1: AT^SSTGI=19
2018-07-11 10:49:19,981 [DEBUG] TX1: AT^SSTR=19,0
2018-07-11 10:49:20,303 [DEBUG] RX2: at^sstgi=19
2018-07-11 10:49:20,304 [DEBUG] RX2: ^SSTGI: 19,0,"",1,0
2018-07-11 10:49:20,304 [DEBUG] RX2: at^sstr=19,0
2018-07-11 10:49:21,321 [DEBUG] RX1: ^SSTN: 19
2018-07-11 10:49:21,322 [INFO] ### 19: SEND MESSAGE (Acknowledge) ###
2018-07-11 10:49:21,322 [DEBUG] TX1: AT^SSTGI=19
2018-07-11 10:49:21,637 [DEBUG] RX2: at^sstgi=19
2018-07-11 10:49:21,637 [DEBUG] RX2: ^SSTGI: 19,0,"",1,0
2018-07-11 10:49:21,705 [DEBUG] TX1: AT^SSTR=19,0
2018-07-11 10:49:22,022 [DEBUG] RX2: at^sstr=19,0
2018-07-11 10:49:22,986 [DEBUG] RX1: ^SSTN: 19
2018-07-11 10:49:22,987 [INFO] ### 19: SEND MESSAGE (Acknowledge) ###
2018-07-11 10:49:22,987 [DEBUG] TX1: AT^SSTGI=19
2018-07-11 10:49:23,308 [DEBUG] RX2: at^sstgi=19
2018-07-11 10:49:23,308 [DEBUG] RX2: ^SSTGI: 19,0,"",1,0
2018-07-11 10:49:23,370 [DEBUG] TX1: AT^SSTR=19,0
2018-07-11 10:49:23,687 [DEBUG] RX2: at^sstr=19,0
2018-07-11 10:49:24,524 [DEBUG] RX1: ^SSTN: 254
2018-07-11 10:49:24,525 [INFO] ### 254: SIM Applet returns to main menu ####
```
