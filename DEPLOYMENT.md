# StationlyUI Web App - Oracle Server Deployment Guide

## Overview
This guide explains how to deploy the StationlyUI static web app to your Oracle server using Nginx with HTTPS support.

**Server Configuration:**
- **Server**: ubuntu@141.147.91.64
- **Port**: 3000 (web app)
- **SSH Key**: `/Users/nikhilkumar/workspace/Projects/ssh_oracle_staionly_backend.key`
- **Domain**: stationly.co.uk (with HTTPS)

**Architecture:**
- Web app runs on localhost:3000
- Nginx proxies stationly.co.uk (port 80/443) → localhost:3000
- API continues on localhost:8080 → api.stationly.co.uk

---

## Prerequisites
- SSH access to Oracle server
- Nginx installed and running
- `www-data` user exists (standard Nginx user)

---

## Quick Start (Automated)

### Step 1: Make the script executable
```bash
chmod +x deploy.sh
```

### Step 2: Run the deployment script

**Using pre-configured values (recommended):**
```bash
./deploy.sh
```

**Or with custom values:**
```bash
./deploy.sh <server_ip> <server_user> <port> <ssh_key_path>
```

Example with custom values:
```bash
./deploy.sh 141.147.91.64 ubuntu 3000 /path/to/ssh/key
```

The script will:
1. Build the web app
2. Create a tarball
3. Upload it to your server via SSH key
4. Print step-by-step instructions

---

## Manual Deployment

### Step 1: Build locally
```bash
./gradlew :web:build -q
```
Output will be in: `web/build/dist/`

### Step 2: Create deployment package
```bash
tar -czf stationly-web.tar.gz -C web/build/dist .
```

### Step 3: Upload to server
```bash
scp -i /Users/nikhilkumar/workspace/Projects/ssh_oracle_staionly_backend.key stationly-web.tar.gz ubuntu@141.147.91.64:/tmp/
```

### Step 4: SSH into your server
```bash
ssh -i /Users/nikhilkumar/workspace/Projects/ssh_oracle_staionly_backend.key ubuntu@141.147.91.64
```

### Step 5: Extract files
```bash
mkdir -p /var/www/stationly-web
sudo tar -xzf /tmp/stationly-web.tar.gz -C /var/www/stationly-web/
sudo chown -R www-data:www-data /var/www/stationly-web
sudo chmod -R 755 /var/www/stationly-web
```

### Step 6: Create Nginx configuration
```bash
sudo nano /etc/nginx/sites-available/stationly-web
```

Paste this configuration:
```nginx
# HTTP -> HTTPS redirect
server {
    listen 80;
    server_name stationly.co.uk www.stationly.co.uk;
    return 301 https://$host$request_uri;
}

# HTTPS proxy to web app on port 3000
server {
    listen 443 ssl http2;
    server_name stationly.co.uk www.stationly.co.uk;

    location / {
        proxy_pass http://localhost:3000;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }

    # SSL Certificate - Use existing or generate new
    ssl_certificate /etc/letsencrypt/live/api.stationly.co.uk/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/api.stationly.co.uk/privkey.pem;
    include /etc/letsencrypt/options-ssl-nginx.conf;
    ssl_dhparam /etc/letsencrypt/ssl-dhparams.pem;
}

# Direct access on port 3000
server {
    listen 3000;
    server_name _;

    root /var/www/stationly-web;
    index index.html;

    # Enable gzip compression
    gzip on;
    gzip_types text/plain text/css text/javascript application/javascript application/json;
    gzip_min_length 1000;

    # Cache static assets
    location ~* \.(js|css|png|jpg|jpeg|gif|ico|svg|woff|woff2|ttf|eot)$ {
        expires 30d;
        add_header Cache-Control "public, immutable";
    }

    # Route all requests to index.html for SPA routing
    location / {
        try_files $uri $uri/ /index.html;
        add_header Cache-Control "no-cache, no-store, must-revalidate";
    }

    # Security headers
    add_header X-Frame-Options "SAMEORIGIN" always;
    add_header X-Content-Type-Options "nosniff" always;
    add_header X-XSS-Protection "1; mode=block" always;
    add_header Referrer-Policy "no-referrer-when-downgrade" always;
}
```

Save and exit (Ctrl+X, then Y, then Enter).

### Step 7: Enable the site
```bash
sudo ln -s /etc/nginx/sites-available/stationly-web /etc/nginx/sites-enabled/
```

### Step 8: Test Nginx configuration
```bash
sudo nginx -t
```

You should see:
```
nginx: the configuration file /etc/nginx/nginx.conf syntax is ok
nginx: configuration will be successful
```

### Step 9: Restart Nginx
```bash
sudo systemctl restart nginx
```

---

## Configure Domain Routing

### Update your main Nginx config to route `stationly.co.uk` to the web app

```bash
sudo nano /etc/nginx/sites-available/stationly
```

Add or update this configuration:
```nginx
upstream stationly_web {
    server localhost:3000;
}

upstream stationly_api {
    server localhost:8080;
}

server {
    listen 80;
    server_name stationly.co.uk www.stationly.co.uk;

    # Serve web app on root
    location / {
        proxy_pass http://stationly_web;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }

    # Route API requests to port 8080
    location /api {
        proxy_pass http://stationly_api;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }
}
```

Save and test:
```bash
sudo nginx -t
sudo systemctl restart nginx
```

---

## Verification

### Check if web app is running on port 3000
```bash
curl http://localhost:3000
```

### Check if domain is routing correctly
```bash
curl http://stationly.co.uk
```

### View Nginx logs if there are issues
```bash
sudo tail -f /var/log/nginx/error.log
sudo tail -f /var/log/nginx/access.log
```

---

## Updating the Web App

To update with a new version:

1. **Locally:**
   ```bash
   ./deploy.sh
   ```

2. **On server:**
   ```bash
   ssh -i /Users/nikhilkumar/workspace/Projects/ssh_oracle_staionly_backend.key ubuntu@141.147.91.64
   
   # Extract new files
   sudo rm -rf /var/www/stationly-web/*
   sudo tar -xzf /tmp/stationly-web-3000.tar.gz -C /var/www/stationly-web/
   sudo chown -R www-data:www-data /var/www/stationly-web
   
   # Restart nginx
   sudo systemctl restart nginx
   ```

---

## Troubleshooting

### Port 3000 already in use
```bash
# Find what's using it
lsof -i :3000
# Kill the process if needed
kill -9 <PID>
```

### Nginx won't restart
```bash
sudo nginx -t  # Check for syntax errors
sudo systemctl status nginx  # Check status
journalctl -u nginx -n 50  # View recent logs
```

### 502 Bad Gateway
- Check if web app is running on port 3000: `curl http://localhost:3000`
- Check Nginx config: `sudo nginx -t`
- View error logs: `sudo tail -f /var/log/nginx/error.log`

### CSS/JS files not loading
- Check file permissions: `ls -la /var/www/stationly-web/`
- Check browser cache (Ctrl+Shift+R for hard refresh)
- Check gzip compression in Nginx config

---

## Performance Tips

1. **Enable HTTPS (SSL/TLS)**
   - Use Let's Encrypt with Certbot
   ```bash
   sudo apt install certbot python3-certbot-nginx
   sudo certbot --nginx -d stationly.co.uk -d www.stationly.co.uk
   ```

2. **Enable HTTP/2**
   - Change `listen 80;` to `listen 80 http2;`

3. **Add CDN**
   - Consider Cloudflare for DDoS protection and caching

---

## Support

For issues or questions, check:
- Nginx documentation: https://nginx.org/en/docs/
- Your server provider's documentation (Oracle Cloud)
