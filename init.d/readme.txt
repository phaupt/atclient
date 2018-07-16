# atclient auto start

Add 'ATClient' script to /etc/init.d
`$ sudo cp ATClient /etc/init.d/`
`$ sudo chmod 775 /etc/init.d/ATClient`

Invoke the script: 
`$ sudo etc/init.d/ATClient start`

For removing services you must use the -f parameter:
`$ sudo update-rc.d -f ATClient remove`

For configuring startup on boot, try:
`$ sudo update-rc.d ATClient defaults`
