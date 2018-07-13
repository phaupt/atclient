# atclient
MobileID USAT Responder for [Raspberry PI 3](https://www.raspberrypi.org/products/raspberry-pi-3-model-b-plus) to operate one or multiple SIM/LTE wireless terminal(s).

![Raspberry PI 3 B+](img/raspi.jpg?raw=true "Raspberry PI 3 B+") ![HCP HIT wireless terminal](img/hitu4.jpg?raw=true "HCP HIT wireless terminal")

ATClient application can be used to respond to SIM Toolkit requests from the Mobile ID application in a fully automated manner. It does not require any user interaction to respond to mobile phone STK screens (e.g. input Mobile ID PIN). 

Serial port is auto detected at startup and re-initialized in case of communication interruption.

This setup can be useful for automated e2e Mobile ID signature monitoring purpose.

### What you will need (recommended)

- [Mobile ID SIM card](https://mobileid.ch)
- [Raspberry PI 3 B+](https://www.raspberrypi.org/products/raspberry-pi-3-model-b-plus) ~ EUR 35.-
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

Usage: GsmClient [<MODE>] [<PORT>]

<PORT>	Serial Port
<MODE>	Switch operation mode:
	ER	Switch to Explicit Response (ER) and shutdown. Requires <PORT> argument.
	AR	Switch to Automatic Response (AR) and shutdown. Requires <PORT> argument.

Default: User emulation with automatic port detection. Requires prior switch to ER operation mode.
```

##### Switch to Explicit Response (ER) mode

As a first step, you must switch the terminal from factory default Automatic Response (AR) mode to Explicit Response (ER) mode.

`pi@raspberypi:~/atclient $ java -Dlog.file=GsmClient.log -Dlog4j.configurationFile=log4j2.xml -cp "./class:./lib/*" com.swisscom.atclient.GsmClient ER /dev/ttyACM1 `

##### User Emulation

Once your terminal is in Explicit Response (ER) mode you can run the application in continuous User Emulation mode.

Serial port is auto detected at start and re-initialized in case of communication interruption.

Note that any GET-INPUT proactive STK commands will be responded with default code '123456'.

`pi@raspberypi:~/atclient $ java -Dlog.file=GsmClient.log -Dlog4j.configurationFile=log4j2.xml -cp "./class:./lib/*" com.swisscom.atclient.GsmClient`

##### Nohup

Nohup will detach a process you run from your current console and let it continue when you close the terminal. This might be useful when you run the User Emulation mode for continuous end-to-end monitoring purpose.

`pi@raspberypi:~/atclient $ nohup java -Dlog.file=GsmClient-ttyACM1.log -Dlog4j.configurationFile=log4j2.xml -cp "./class:./lib/*" com.swisscom.atclient.GsmClient &`

### Logfile

Edit the `log4j2.xml` to configure a different log level, if needed. DEBUG level is default.

With the default log4j configuration you can pass the log file name as a command line parameter: `-Dlog.file=GsmClient.log`

#### Example logfile output

Example log in case of a Mobile ID signature processing. It's lightning fast (less than a second).
```
2018-07-13 16:12:01,222 13820@U278884 COM16 [D] <<< RX ^SSTN: 33
2018-07-13 16:12:01,222 13820@U278884 COM16 [I] ### 33: DISPLAY TEXT ####
2018-07-13 16:12:01,222 13820@U278884 COM16 [D] >>> TX at^sstgi=33
2018-07-13 16:12:01,237 13820@U278884 COM16 [D] <<< RX ^SSTGI: 33,129,"0074006500730074",0,1,0
2018-07-13 16:12:01,237 13820@U278884 COM16 [I] TEXT: "test"
2018-07-13 16:12:01,237 13820@U278884 COM16 [D] <<< RX OK
2018-07-13 16:12:01,237 13820@U278884 COM16 [D] >>> TX at^sstr=33,0
2018-07-13 16:12:01,253 13820@U278884 COM16 [D] <<< RX OK
2018-07-13 16:12:01,300 13820@U278884 COM16 [D] <<< RX ^SSTN: 35
2018-07-13 16:12:01,300 13820@U278884 COM16 [I] ### 35: GET INPUT ####
2018-07-13 16:12:01,300 13820@U278884 COM16 [D] >>> TX at^sstgi=35
2018-07-13 16:12:01,315 13820@U278884 COM16 [D] <<< RX ^SSTGI: 35,4,"00410075007400680065006E0074006900630061007400650020007700690074006800200079006F007500720020004D006F00620069006C0065002000490044002000500049004E",1,15,"",1,0
2018-07-13 16:12:01,315 13820@U278884 COM16 [I] TEXT: "Authenticate with your Mobile ID PIN"
2018-07-13 16:12:01,315 13820@U278884 COM16 [D] <<< RX OK
2018-07-13 16:12:01,315 13820@U278884 COM16 [D] >>> TX at^sstr=35,0,,003100320033003400350036
2018-07-13 16:12:01,331 13820@U278884 COM16 [D] <<< RX OK
2018-07-13 16:12:01,675 13820@U278884 COM16 [D] <<< RX ^SSTN: 19
2018-07-13 16:12:01,675 13820@U278884 COM16 [I] ### 19: SEND MESSAGE ###
2018-07-13 16:12:01,675 13820@U278884 COM16 [D] >>> TX at^sstgi=19
2018-07-13 16:12:01,690 13820@U278884 COM16 [D] <<< RX ^SSTGI: 19,0,"",1,0
2018-07-13 16:12:01,690 13820@U278884 COM16 [D] <<< RX OK
2018-07-13 16:12:01,690 13820@U278884 COM16 [D] >>> TX at^sstr=19,0
2018-07-13 16:12:01,909 13820@U278884 COM16 [D] <<< RX ^SSTR: 19,0,""
2018-07-13 16:12:01,909 13820@U278884 COM16 [D] <<< RX OK
2018-07-13 16:12:01,940 13820@U278884 COM16 [D] <<< RX ^SSTN: 254
2018-07-13 16:12:01,940 13820@U278884 COM16 [I] ### 254: SIM Applet returns to main menu ####
```
