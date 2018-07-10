# atclient
AT Client for Raspberry PI

```
javac -d ./class -cp "./lib/*" ./src/com/swisscom/atclient/*.java

java -Dlog4j.configuration=file:log4j.properties -cp "./class:./lib/*" com.swisscom.atclient.GsmClient /dev/ttyUSB0 UE

########### LTE ########### 

root@raspberrypi:~ # dmesg
[  124.081576] usb 1-1.2: new high-speed USB device number 8 using dwc_otg
[  124.212688] usb 1-1.2: New USB device found, idVendor=1e2d, idProduct=0061
[  124.212706] usb 1-1.2: New USB device strings: Mfr=1, Product=2, SerialNumber=0
[  124.212715] usb 1-1.2: Product: LTE Modem
[  124.212723] usb 1-1.2: Manufacturer: Cinterion
[  124.222865] cdc_acm 1-1.2:1.0: ttyACM0: USB ACM device
[  124.234398] cdc_acm 1-1.2:1.2: ttyACM1: USB ACM device
[  124.246836] cdc_acm 1-1.2:1.4: ttyACM2: USB ACM device
[  124.267097] cdc_acm 1-1.2:1.6: ttyACM3: USB ACM device
[  124.288873] cdc_acm 1-1.2:1.8: ttyACM4: USB ACM device
[  124.292051] cdc_ether 1-1.2:1.10 usb0: register 'cdc_ether' at usb-3f980000.usb-1.2, CDC Ethernet Device, de:ad:be:ef:00:00
[  124.294766] cdc_ether 1-1.2:1.12 usb1: register 'cdc_ether' at usb-3f980000.usb-1.2, CDC Ethernet Device, de:ad:be:ef:00:01
[  124.422069] cdc_ether 1-1.2:1.10 usb0: CDC: unexpected notification 01!
[  124.451051] cdc_ether 1-1.2:1.12 usb1: CDC: unexpected notification 01!
[  124.454066] cdc_ether 1-1.2:1.10 usb0: CDC: unexpected notification 01!
[  124.483027] cdc_ether 1-1.2:1.12 usb1: CDC: unexpected notification 01!
[  124.486037] cdc_ether 1-1.2:1.10 usb0: CDC: unexpected notification 01!
[  124.515041] cdc_ether 1-1.2:1.12 usb1: CDC: unexpected notification 01!
[  128.483062] cdc_ether 1-1.2:1.12 usb1: CDC: unexpected notification 01!
[  128.486048] cdc_ether 1-1.2:1.10 usb0: CDC: unexpected notification 01!


root@raspberrypi:~ # lsusb
Bus 001 Device 008: ID 1e2d:0061  
Bus 001 Device 006: ID 046d:c050 Logitech, Inc. RX 250 Optical Mouse
Bus 001 Device 004: ID 046d:c312 Logitech, Inc. DeLuxe 250 Keyboard
Bus 001 Device 007: ID 0424:7800 Standard Microsystems Corp. 
Bus 001 Device 003: ID 0424:2514 Standard Microsystems Corp. USB 2.0 Hub
Bus 001 Device 002: ID 0424:2514 Standard Microsystems Corp. USB 2.0 Hub
Bus 001 Device 001: ID 1d6b:0002 Linux Foundation 2.0 root hub

########### PHS8 3G ########### 

root@raspberrypi:~ # dmesg
[  519.091789] usb 1-1.2: new high-speed USB device number 9 using dwc_otg
[  519.224073] usb 1-1.2: New USB device found, idVendor=1e2d, idProduct=0053
[  519.224092] usb 1-1.2: New USB device strings: Mfr=3, Product=2, SerialNumber=0
[  519.224100] usb 1-1.2: Product: PH8
[  519.224108] usb 1-1.2: Manufacturer: Cinterion
[  519.306677] usbcore: registered new interface driver usbserial
[  519.306760] usbcore: registered new interface driver usbserial_generic
[  519.306822] usbserial: USB Serial support registered for generic
[  519.313778] usbcore: registered new interface driver cdc_wdm
[  519.327341] usbcore: registered new interface driver option
[  519.327417] usbserial: USB Serial support registered for GSM modem (1-port)
[  519.328603] option 1-1.2:1.0: GSM modem (1-port) converter detected
[  519.330149] usb 1-1.2: GSM modem (1-port) converter now attached to ttyUSB0
[  519.331616] qmi_wwan 1-1.2:1.4: cdc-wdm0: USB WDM device
[  519.332739] qmi_wwan 1-1.2:1.4 wwan0: register 'qmi_wwan' at usb-3f980000.usb-1.2, WWAN/QMI device, b6:15:8f:83:77:e4
[  519.333172] usbcore: registered new interface driver qmi_wwan
[  519.343524] option 1-1.2:1.1: GSM modem (1-port) converter detected
[  519.345952] usb 1-1.2: GSM modem (1-port) converter now attached to ttyUSB1
[  519.346820] option 1-1.2:1.2: GSM modem (1-port) converter detected
[  519.347398] usb 1-1.2: GSM modem (1-port) converter now attached to ttyUSB2
[  519.348135] option 1-1.2:1.3: GSM modem (1-port) converter detected
[  519.348649] usb 1-1.2: GSM modem (1-port) converter now attached to ttyUSB3

root@raspberrypi:~ # lsusb
Bus 001 Device 009: ID 1e2d:0053  
Bus 001 Device 006: ID 046d:c050 Logitech, Inc. RX 250 Optical Mouse
Bus 001 Device 004: ID 046d:c312 Logitech, Inc. DeLuxe 250 Keyboard
Bus 001 Device 007: ID 0424:7800 Standard Microsystems Corp. 
Bus 001 Device 003: ID 0424:2514 Standard Microsystems Corp. USB 2.0 Hub
Bus 001 Device 002: ID 0424:2514 Standard Microsystems Corp. USB 2.0 Hub
Bus 001 Device 001: ID 1d6b:0002 Linux Foundation 2.0 root hub

root@raspberrypi:~ # tail -f /var/log/syslog
Jul 10 07:52:40 raspberrypi kernel: [ 1059.504059] usb 1-1.2: new high-speed USB device number 10 using dwc_otg
Jul 10 07:52:40 raspberrypi kernel: [ 1059.643515] usb 1-1.2: New USB device found, idVendor=1e2d, idProduct=0053
Jul 10 07:52:40 raspberrypi kernel: [ 1059.643535] usb 1-1.2: New USB device strings: Mfr=3, Product=2, SerialNumber=0
Jul 10 07:52:40 raspberrypi kernel: [ 1059.643543] usb 1-1.2: Product: PH8
Jul 10 07:52:40 raspberrypi kernel: [ 1059.643551] usb 1-1.2: Manufacturer: Cinterion
Jul 10 07:52:40 raspberrypi kernel: [ 1059.648322] option 1-1.2:1.0: GSM modem (1-port) converter detected
Jul 10 07:52:40 raspberrypi kernel: [ 1059.649871] usb 1-1.2: GSM modem (1-port) converter now attached to ttyUSB0
Jul 10 07:52:40 raspberrypi kernel: [ 1059.650787] option 1-1.2:1.1: GSM modem (1-port) converter detected
Jul 10 07:52:40 raspberrypi kernel: [ 1059.651220] usb 1-1.2: GSM modem (1-port) converter now attached to ttyUSB1
Jul 10 07:52:40 raspberrypi kernel: [ 1059.652075] option 1-1.2:1.2: GSM modem (1-port) converter detected
Jul 10 07:52:40 raspberrypi kernel: [ 1059.652439] usb 1-1.2: GSM modem (1-port) converter now attached to ttyUSB2
Jul 10 07:52:40 raspberrypi kernel: [ 1059.653309] option 1-1.2:1.3: GSM modem (1-port) converter detected
Jul 10 07:52:40 raspberrypi kernel: [ 1059.653675] usb 1-1.2: GSM modem (1-port) converter now attached to ttyUSB3
Jul 10 07:52:40 raspberrypi kernel: [ 1059.656173] qmi_wwan 1-1.2:1.4: cdc-wdm0: USB WDM device
Jul 10 07:52:40 raspberrypi kernel: [ 1059.657224] qmi_wwan 1-1.2:1.4 wwan0: register 'qmi_wwan' at usb-3f980000.usb-1.2, WWAN/QMI device, b6:15:8f:83:77:e4
Jul 10 07:52:40 raspberrypi mtp-probe: checking bus 1, device 10: "/sys/devices/platform/soc/3f980000.usb/usb1/1-1/1-1.2"
Jul 10 07:52:40 raspberrypi mtp-probe: bus: 1, device: 10 was not an MTP device
Jul 10 07:52:40 raspberrypi dhcpcd-run-hooks[1461]: wwan0: starting wpa_supplicant
Jul 10 07:52:41 raspberrypi dhcpcd[383]: wwan0: waiting for carrier
Jul 10 07:52:41 raspberrypi dhcpcd[383]: wwan0: carrier acquired
Jul 10 07:52:41 raspberrypi dhcpcd[383]: wwan0: IAID 8f:83:77:e4
Jul 10 07:52:41 raspberrypi dhcpcd[383]: wwan0: adding address fe80::d657:4bf1:e078:19b2
Jul 10 07:52:41 raspberrypi dhcpcd[383]: wwan0: carrier lost
Jul 10 07:52:41 raspberrypi dhcpcd[383]: wwan0: deleting address fe80::d657:4bf1:e078:19b2

root@raspberrypi:~/atclient # modprobe -r ftdi_sio
Jul 10 08:24:54 raspberrypi kernel: [ 2993.746436] usbserial: USB Serial deregistering driver FTDI USB Serial Device
Jul 10 08:24:54 raspberrypi kernel: [ 2993.746574] usbcore: deregistering interface driver ftdi_sio

root@raspberrypi:~/atclient # modprobe ftdi_sio vendor=0x0403 product=0xfac6
Jul 10 08:25:32 raspberrypi kernel: [ 3031.399221] ftdi_sio: unknown parameter 'vendor' ignored
Jul 10 08:25:32 raspberrypi kernel: [ 3031.399237] ftdi_sio: unknown parameter 'product' ignored
Jul 10 08:25:32 raspberrypi kernel: [ 3031.402105] usbcore: registered new interface driver ftdi_sio
Jul 10 08:25:32 raspberrypi kernel: [ 3031.403868] usbserial: USB Serial support registered for FTDI USB Serial Device

sh -c 'echo "1e2d 0053" > /sys/bus/usb-serial/drivers/ftdi_sio/new_id'

```
