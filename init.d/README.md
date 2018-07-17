# atclient auto start

Application startup on boot can be configured in several different ways. We recommend to use init.d.

Follow the steps below.

### Add 'ATClient' script to /etc/init.d
`$ sudo cp ATClient /etc/init.d/`
`$ sudo chmod 775 /etc/init.d/ATClient`

### Start/stop/restart 
`$ sudo /etc/init.d/ATClient start`
`$ sudo /etc/init.d/ATClient stop`
`$ sudo /etc/init.d/ATClient restart`

### Configure service to startup on boot
`$ sudo update-rc.d ATClient defaults`

### Remove service
`$ sudo update-rc.d -f ATClient remove`
