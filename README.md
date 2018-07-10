# atclient
MobileID ATClient for [Raspberry PI 3](https://www.raspberrypi.org/products/raspberry-pi-3-model-b-plus) to operate one or multiple SIM based wireless terminal(s).

![Raspberry PI 3 B+](img/raspi.jpg?raw=true "Raspberry PI 3 B+") ![HCP HIT wireless terminal](img/hitu4.jpg?raw=true "HCP HIT wireless terminal")

Responds to SIM Toolkit Requests from the Mobile ID application. Useful for automated e2e monitoring purpose.

### What you will need

- [Mobile ID SIM card](https://mobileid.ch)
- [Raspberry PI 3 B+](https://www.raspberrypi.org/products/raspberry-pi-3-model-b-plus) ~ EUR 35.-
- [SIM based wireless terminal](http://electronicshcp.com/product/hit-u4-lte) ~ EUR 100.-

### Wireless terminal

This application has been successfully tested on Raspberry PI 3 B+ (raspbian) as well as Windows 10. A wireless terminal from the list below was used. Baudrate set to default 9600.

- [HCP HIT U8 PHS8 3G terminal](http://electronicshcp.com/product/hit-u8)
- [HCP HIT U4 LTE terminal](http://electronicshcp.com/product/hit-u4-lte)

### Port selection

1. Unplug the modem
2. Run: `sudo dmesg -c`
3. Plug in the modem and wait a few seconds
4. Run: `sudo dmesg`

You may also trace the syslog while connecting the device: `sudo tail -f /var/log/syslog`

Port descriptor must be something like "/dev/ttyUSB0" (Linux) or "COM4" (Windows).

### Clone GIT, compile and run

```
pi@raspberypi:~ $ git clone https://github.com/phaupt/atclient.git
pi@raspberypi:~ $ cd atclient
pi@raspberypi:~/atclient $ mkdir class
pi@raspberypi:~/atclient $ javac -d ./class -cp "./lib/*" ./src/com/swisscom/atclient/*.java
pi@raspberypi:~/atclient $ java -Dlog4j.configuration=file:log4j.properties -cp "./class:./lib/*" com.swisscom.atclient.GsmClient /dev/ttyACM1 UE
```

### Logfile

Edit the `log4j.properties` to configure a different log level, if needed. DEBUG level is default.
If you set TRACE level, it will log the complete RX and TX traffic.

```
pi@raspberypi:~/atclient $ tail -f GsmClient.log
```

#### Example logfile output
```
2018-07-10 14:31:34,316 [ATRESP] INFO  atclient.ATresponder - Application started...
2018-07-10 14:31:34,332 [ATRESP] DEBUG atclient.ATresponder - Attached Shutdown Hook
2018-07-10 14:31:34,332 [ATRESP] INFO  atclient.ATresponder - Init Serial Port in progress.
2018-07-10 14:31:34,416 [ATRESP] DEBUG atclient.ATresponder - Index: 0; COM3; Serial0; Intel(R) Active Management Technology - SOL (COM3)
2018-07-10 14:31:34,416 [ATRESP] DEBUG atclient.ATresponder - Index: 1; COM4; PH8; Cinterion PH8 HSPA USB Com Port (COM4)
2018-07-10 14:31:34,416 [ATRESP] DEBUG atclient.ATresponder - Index: 2; COM11; PH8; Cinterion PH8 HSPA USB Modem
2018-07-10 14:31:34,416 [ATRESP] DEBUG atclient.ATresponder - Index: 3; COM8; PH8; Cinterion PH8 HSPA USB NMEA Com Port (COM8)
2018-07-10 14:31:34,416 [ATRESP] DEBUG atclient.ATresponder - Index: 4; COM9; PH8; Cinterion PH8 HSPA USB reserved Com Port (COM9)
2018-07-10 14:31:34,416 [ATRESP] DEBUG atclient.ATresponder - Selected Port: COM4
2018-07-10 14:31:35,417 [ATRESP] DEBUG atclient.ATresponder - Opened Port: true
2018-07-10 14:31:35,818 [ATRESP] DEBUG atclient.ATresponder - Set DTR: true
2018-07-10 14:31:35,818 [ATRESP] INFO  atclient.ATresponder - Connection successfully established.
2018-07-10 14:31:35,818 [ATRESP] INFO  atclient.ATresponder - Wait for 5 seconds...
2018-07-10 14:31:40,823 [ATRESP] DEBUG atclient.ATresponder - ### Set SMS text mode ###
2018-07-10 14:31:40,823 [ATRESP] DEBUG atclient.ATresponder - TX1: AT+CMGF=1
2018-07-10 14:31:41,340 [ATRESP] DEBUG atclient.ATresponder - RX2: AT+CMGF=1
2018-07-10 14:31:41,593 [ATRESP] DEBUG atclient.ATresponder - ### Activate the display of a URC on every received SMS ###
2018-07-10 14:31:42,125 [ATRESP] DEBUG atclient.ATresponder - RX2: AT+CNMI=1,1
2018-07-10 14:31:42,378 [ATRESP] DEBUG atclient.ATresponder - ### Retrieve Provider Details ###
2018-07-10 14:31:42,894 [ATRESP] DEBUG atclient.ATresponder - RX2: AT+COPS?
2018-07-10 14:31:42,894 [ATRESP] DEBUG atclient.ATresponder - RX2: +COPS: 0,0,"Swisscom",2
2018-07-10 14:31:42,894 [ATRESP] INFO  atclient.ATresponder - Operator: Swisscom
2018-07-10 14:31:43,164 [ATRESP] DEBUG atclient.ATresponder - ### Retrieve Signal Strength Details ###
2018-07-10 14:31:43,680 [ATRESP] DEBUG atclient.ATresponder - RX2: AT+CSQ
2018-07-10 14:31:43,680 [ATRESP] DEBUG atclient.ATresponder - RX2: +CSQ: 31,99
2018-07-10 14:31:43,680 [ATRESP] INFO  atclient.ATresponder - SignalStrength: 31,99
2018-07-10 14:31:43,943 [ATRESP] DEBUG atclient.ATresponder - ### Retrieve Wireless Data Service Details ###
2018-07-10 14:31:44,466 [ATRESP] DEBUG atclient.ATresponder - RX2: AT+WS46=?
2018-07-10 14:31:44,466 [ATRESP] DEBUG atclient.ATresponder - RX2: +WS46: (12,22,25)
2018-07-10 14:31:44,466 [ATRESP] INFO  atclient.ATresponder - Wireless Service:  (12,22,25)
2018-07-10 14:31:44,729 [ATRESP] DEBUG atclient.ATresponder - ### Retrieve IMSI ###
2018-07-10 14:31:45,246 [ATRESP] DEBUG atclient.ATresponder - RX2: AT+CIMI
2018-07-10 14:31:45,247 [ATRESP] DEBUG atclient.ATresponder - RX2: 228012123638957
2018-07-10 14:31:45,247 [ATRESP] INFO  atclient.ATresponder - IMSI: 228012123638957
2018-07-10 14:31:45,501 [ATRESP] DEBUG atclient.ATresponder - ### Retrieve IMEI ###
2018-07-10 14:31:46,032 [ATRESP] DEBUG atclient.ATresponder - RX2: AT+CGSN
2018-07-10 14:31:46,032 [ATRESP] DEBUG atclient.ATresponder - RX2: 359998040020283
2018-07-10 14:31:46,032 [ATRESP] INFO  atclient.ATresponder - IMEI: 359998040020283
2018-07-10 14:31:46,287 [ATRESP] INFO  atclient.ATresponder - Ready to receive incoming data...
2018-07-10 14:31:46,550 [ATRESP] DEBUG atclient.ATresponder - RX1: +CMTI: "SM", 1
2018-07-10 14:31:46,551 [ATRESP] INFO  atclient.ATresponder - ### Incoming Short Message ###
2018-07-10 14:31:46,551 [ATRESP] DEBUG atclient.ATresponder - ### Read SMS Details ###
2018-07-10 14:31:46,551 [ATRESP] DEBUG atclient.ATresponder - TX1: AT+CMGR=1
2018-07-10 14:31:47,336 [ATRESP] DEBUG atclient.ATresponder - ### Delete SMS storage ###
2018-07-10 14:31:47,336 [ATRESP] DEBUG atclient.ATresponder - TX1: AT+CMGD=0,4
2018-07-10 14:31:47,852 [ATRESP] DEBUG atclient.ATresponder - RX2: AT+CMGR=1
2018-07-10 14:31:47,852 [ATRESP] DEBUG atclient.ATresponder - RX2: +CMGR: "REC UNREAD","+41797895164",,"18/07/10,14:31:44+08"
2018-07-10 14:31:47,852 [ATRESP] DEBUG atclient.ATresponder - RX2: The OTP is xxx
2018-07-10 14:31:47,853 [ATRESP] INFO  atclient.ATresponder - Text SMS: THE OTP IS XXX
2018-07-10 14:31:48,105 [ATRESP] DEBUG atclient.ATresponder - RX1: AT+CMGD=0,4
```
