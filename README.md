# Beckn Reference Gateway 
The beckn gateway is a beckn network entity that routes search requests to the right bpps on the network. 
To access the reference Gateway included here you need to call /bg/search , 


NOTE: This repo is a succinct web framework plugin. To use like an application, you need to use the 
[standalone-gw app](https://github.com/venkatramanm/beckn-gateway-app) 

To register the Bg to a network: 
===
1. Bring up your bg application.
2. Login as root
3. Change the url to /bg/subscribe  ( This will initiate the subscription to the registry) If the challenges are resolved, the BG is marked SUBSCRIBED.

