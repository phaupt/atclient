# atclient auto start

Add 'ATClient' script to /etc/init.d
`$ sudo cp ATClient /etc/init.d/`
`$ sudo chmod 775 /etc/init.d/ATClient`

Invoke the script: 
`$ sudo etc/init.d/ATClient start`

Make it start automatically:
`$ sudo update-rc.d ATClient defaults`