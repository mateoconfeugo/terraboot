curl -sL https://repos.influxdata.com/influxdb.key | sudo apt-key add -
source /etc/lsb-release
echo "deb https://repos.influxdata.com/${DISTRIB_ID,,} ${DISTRIB_CODENAME} stable" | sudo tee /etc/apt/sources.list.d/influxdb.list
sudo apt-get update && sudo apt-get install influxdb
sudo service influxdb start


apt-get install nginx

edit `/etc/nginx/sites-enabled/default`

```
server {

        map $http_x_forwarded_proto $real_scheme {
          default $http_x_forwarded_proto;
          ''      $scheme;
        }
        listen 80 default_server;
        server_name _;
        location = /status {
          return 200;
        }
        location / {
          auth_basic "Restricted";
          auth_basic_user_file /etc/nginx/htpasswd;
          if ($http_x_forwarded_proto != "https") {
            rewrite ^(.*)$ https://$host$1 permanent;
          }
          proxy_pass https://localhost:10000;
        }
}
```